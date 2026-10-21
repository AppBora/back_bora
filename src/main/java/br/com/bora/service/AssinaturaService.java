package br.com.bora.service;

import br.com.bora.entity.Assinatura;
import br.com.bora.entity.Loja;
import br.com.bora.entity.PagamentoAssinatura;
import br.com.bora.entity.Papel;
import br.com.bora.entity.Plano;
import br.com.bora.entity.StatusAssinatura;
import br.com.bora.entity.Usuario;
import br.com.bora.repository.AssinaturaRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

/** Cobrança recorrente via Asaas: cria a assinatura e reage aos webhooks de pagamento. */
@Slf4j
@Service
public class AssinaturaService {

    private final AssinaturaRepository repo;
    private final LojaRepository lojas;
    private final UsuarioRepository usuarios;
    /** Fuso do negocio. O container roda em UTC: sem isto, "hoje" vira amanha depois das 21h. */
    private static final java.time.ZoneId ZONE = java.time.ZoneId.of("America/Sao_Paulo");

    private static final org.slf4j.Logger AUDITORIA = org.slf4j.LoggerFactory.getLogger("AUDITORIA");

    private final AsaasClient asaas;
    private final AuthContext ctx;
    private final br.com.bora.repository.PagamentoAssinaturaRepository pagamentos;
    /** Dias entre a fatura vencer e o acesso acabar. Os Termos publicados prometem 10. */
    private final int carenciaDias;

    // Um construtor só: com dois, o Spring não sabe qual usar e a aplicação nem sobe.
    public AssinaturaService(AssinaturaRepository repo, LojaRepository lojas, UsuarioRepository usuarios,
                             AsaasClient asaas, AuthContext ctx,
                             br.com.bora.repository.PagamentoAssinaturaRepository pagamentos,
                             @org.springframework.beans.factory.annotation.Value("${bora.cobranca.carencia-dias:10}") int carenciaDias) {
        this.carenciaDias = carenciaDias;
        this.pagamentos = pagamentos;
        this.repo = repo;
        this.lojas = lojas;
        this.usuarios = usuarios;
        this.asaas = asaas;
        this.ctx = ctx;
    }

    /** Assinatura da loja logada (ou null se ainda não assinou). */
    public Assinatura status() {
        return repo.findByLojaId(ctx.lojaId()).orElse(null);
    }

    /**
     * Assina o plano atual da loja no Asaas (cria cliente + assinatura mensal).
     *
     * <h2>Os dois defeitos que isto fecha (medidos em 20/10/2026)</h2>
     *
     * <p><b>1. Teste gratis infinito.</b> So saia cedo com status ATIVA. Com PENDENTE, INADIMPLENTE ou
     * CANCELADA, cancelava a assinatura no Asaas e criava outra com vencimento em {@code hoje + 7}.
     * O lojista inadimplente clicava "Reativar assinatura", a fatura atrasada desaparecia e ele
     * ganhava mais 7 dias de cortesia -- quantas vezes quisesse. E o caminho esta aberto de proposito
     * para quem esta bloqueado ({@code BloqueioPorAssinaturaFilter} libera {@code /api/assinatura}),
     * ou seja, estava aberto justamente para quem tinha motivo de usar.</p>
     *
     * <p><b>2. Cancela aqui, falha ali, loja sem cobranca.</b> Cancelava no Asaas e SO DEPOIS criava,
     * tudo numa transacao. Se a criacao falhasse, o nosso banco fazia rollback e voltava a apontar
     * para a assinatura antiga -- que la fora ja estava morta. O painel dizia que estava tudo certo e
     * a receita daquela loja sumia, calado. <b>E o estado em que a loja da Zira esta.</b></p>
     *
     * <h2>A regra agora</h2>
     * <ol>
     *   <li><b>ATIVA ou PENDENTE</b> -> devolve a que existe, sem tocar no Asaas. Clicar de novo nao
     *       pode criar cobranca nem mover vencimento. Idempotente.</li>
     *   <li><b>INADIMPLENTE com a assinatura VIVA no Asaas</b> -> <b>recusa</b>. Tem fatura em aberto,
     *       e o caminho e pagar aquela fatura, nao criar outra. O painel mostra o link de pagamento
     *       (ver {@link #faturaEmAberto}), que antes nao existia -- a unica acao oferecida ao
     *       inadimplente era justamente a que apagava a divida.</li>
     *   <li><b>A assinatura NAO EXISTE mais no Asaas</b> (deletada por fora, como a da Zira) -> cria
     *       outra. Nao ha o que cancelar, e sem isto a loja ficaria sem nenhuma forma de voltar a
     *       pagar.</li>
     *   <li><b>CANCELADA</b> -> reassina.</li>
     *   <li><b>Nao deu para falar com o Asaas</b> -> recusa com 503. Nunca cancelar nem criar no
     *       escuro: foi assim que a assinatura da Zira se perdeu.</li>
     * </ol>
     *
     * <p><b>A cortesia de 7 dias vale uma vez so</b>, na primeira assinatura da loja. Reassinando, o
     * vencimento e amanha -- quem ja usou o periodo de teste nao ganha outro por clicar de novo.</p>
     *
     * <p><b>A ordem e CRIA, depois cancela.</b> Se a criacao falhar, nada foi cancelado e o estado
     * continua consistente. O risco que sobra e o inverso: cancelamento falhando deixa duas
     * assinaturas vivas e o lojista cobrado em dobro. Entre os dois, dobro e melhor que zero -- dobro
     * e visivel, o lojista reclama e da para estornar; zero e invisivel. E o id orfao fica GRAVADO em
     * {@code asaasSubscriptionAnterior} (V52), nao numa linha de log que rotaciona.</p>
     */
    @Transactional
    public Assinatura assinar(String cpfCnpj) {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        Long lojaId = ctx.lojaId();
        Assinatura existente = repo.findByLojaId(lojaId).orElse(null);
        // O estado da assinatura ANTERIOR no Asaas, descoberto uma vez. Ele decide duas coisas: se
        // reassinar e permitido (abaixo) e, mais tarde, se ha o que cancelar -- a antiga da Zira esta
        // deletada la fora, e mandar cancelar o que ja morreu faria o cancelamento "falhar" e o
        // sistema anunciar uma cobranca em duplicidade que nao existe.
        AsaasClient.EstadoDaAssinatura estadoAnterior = null;
        if (existente != null && existente.getAsaasSubscriptionId() != null) {
            estadoAnterior = estadoParaDecidir(existente);
            Assinatura recusaOuDevolve = decidirSobreAssinaturaQueJaExiste(existente, estadoAnterior);
            if (recusaOuDevolve != null) return recusaOuDevolve;
        }
        Loja loja = lojas.findById(lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
        Plano plano = loja.getPlano() == null ? Plano.UNICO : loja.getPlano();
        // Pelos vinculos, nao so pela coluna loja_id: na rede multi-lojas o dono tem uma conta so, e da
        // segunda loja em diante ele nao aparecia aqui. A cobranca nascia sem e-mail e o lojista nunca
        // recebia a fatura -- e e exatamente o caso das outras duas lojas da Zira.
        String email = usuarios.donosDaLoja(lojaId).stream()
                .findFirst().map(Usuario::getEmail).orElse(null);

        Assinatura a = repo.findByLojaId(lojaId).orElseGet(() -> {
            Assinatura nova = new Assinatura();
            nova.setLojaId(lojaId);
            return nova;
        });
        a.setPlano(plano);
        // precoComModulos, não precoEfetivo: o Módulo IA (+R$ 99) precisa entrar na cobrança.
        a.setValor(loja.precoComModulos()); // respeita preço negociado por loja (ex.: fundador R$149)
        if (a.getAsaasCustomerId() == null) {
            a.setAsaasCustomerId(asaas.criarCliente(loja.getNome(), email, documentoDaCobranca(loja, cpfCnpj)));
        }

        // A cortesia de 7 dias vale UMA VEZ, na primeira assinatura da loja. Reassinando, o vencimento
        // e amanha: quem ja usou o periodo de teste nao ganha outro por clicar de novo. Era aqui que
        // nascia o teste gratis infinito.
        String anterior = a.getAsaasSubscriptionId();
        boolean primeiraVez = anterior == null;
        String nextDue = LocalDate.now().plusDays(primeiraVez ? 7 : 1).toString();

        // CRIA primeiro, cancela depois. Ver o javadoc deste metodo: na ordem antiga, uma falha na
        // criacao deixava a loja sem cobranca nenhuma e o painel dizendo que estava tudo certo.
        Map<String, Object> sub = asaas.criarAssinatura(a.getAsaasCustomerId(), loja.precoComModulos().doubleValue(),
                "BoraHapp " + plano.name() + " - " + loja.getNome(), nextDue);
        String novoId = sub == null ? null : (String) sub.get("id");
        if (novoId == null) {
            // Nada foi cancelado, entao a assinatura antiga (se havia) continua valendo. Recusar aqui e
            // melhor que gravar uma assinatura sem id e achar que esta tudo bem.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "O Asaas nao criou a cobranca agora. Nada foi alterado; tente de novo em alguns minutos.");
        }
        a.setAsaasSubscriptionId(novoId);
        a.setAsaasSubscriptionAnterior(null);

        // So cancela o que esta VIVO. A antiga deletada por fora (o caso da Zira) nao tem o que
        // cancelar: mandar cancelar devolveria falha e o sistema anunciaria cobranca em duplicidade
        // que nao existe -- alarme falso no painel do lojista e no log de auditoria.
        boolean anteriorEstaViva = estadoAnterior == AsaasClient.EstadoDaAssinatura.VIVA;
        if (anterior != null && anteriorEstaViva && asaas.configurado() && !anterior.equals(novoId)) {
            if (!asaas.cancelarAssinatura(anterior)) {
                // Duas assinaturas vivas: o lojista vai ser cobrado em dobro. Fica GRAVADO, nao so no
                // log, porque alguem precisa cancelar a antiga a mao no Asaas.
                a.setAsaasSubscriptionAnterior(anterior);
                AUDITORIA.error("COBRANCA EM DUPLICIDADE na loja {}: criei a assinatura {} mas NAO consegui "
                                + "cancelar a anterior {}. O lojista sera cobrado DUAS vezes. Cancele a "
                                + "anterior no Asaas e limpe assinatura.asaas_subscription_anterior.",
                        lojaId, novoId, anterior);
            }
        }

        a.setStatus(StatusAssinatura.PENDENTE);
        a.setAtualizadoEm(OffsetDateTime.now());
        return repo.save(a);
    }

    /**
     * O que fazer quando a loja JA tem assinatura no Asaas.
     *
     * @return a assinatura a devolver sem mexer em nada, ou {@code null} para seguir e reassinar
     * @throws ResponseStatusException quando reassinar seria errado (fatura em aberto) ou arriscado
     *                                 (Asaas indisponivel)
     */
    private Assinatura decidirSobreAssinaturaQueJaExiste(Assinatura a,
                                                        AsaasClient.EstadoDaAssinatura estado) {
        StatusAssinatura st = a.getStatus();

        // Clicar de novo em cima de algo que ja existe e esta em ordem nao pode criar cobranca nem
        // mover vencimento. PENDENTE entra aqui junto com ATIVA: a assinatura e a fatura ja existem,
        // o lojista so ainda nao pagou a primeira -- recriar daria mais 7 dias de cortesia.
        if (st == StatusAssinatura.ATIVA || st == StatusAssinatura.PENDENTE) return a;

        if (estado == AsaasClient.EstadoDaAssinatura.INDISPONIVEL) {
            // Nao decidir no escuro. Cancelar/criar sem saber o estado la fora foi o que perdeu a
            // assinatura da Zira.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Nao consegui confirmar a sua assinatura no sistema de cobranca agora. "
                            + "Nada foi alterado; tente de novo em alguns minutos.");
        }

        if (estado == AsaasClient.EstadoDaAssinatura.VIVA && st == StatusAssinatura.INADIMPLENTE) {
            // A divida existe e a cobranca esta de pe: o caminho e PAGAR aquela fatura. Recriar
            // apagava a fatura atrasada e abria outra cortesia de 7 dias.
            var fatura = faturaEmAberto(a);
            String comoPagar = fatura != null && fatura.link() != null
                    ? " Pague pelo link que esta na tela de Planos."
                    : " O link de pagamento foi enviado por e-mail pelo Asaas.";
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Voce tem uma fatura em aberto. Reativar nao apaga essa fatura." + comoPagar);
        }

        // NAO_EXISTE (deletada por fora, como a da Zira) ou CANCELADA: segue e cria outra.
        return null;
    }

    /**
     * Consulta o Asaas UMA VEZ sobre a assinatura anterior.
     *
     * <p>Devolve null quando nem precisa perguntar: assinatura em ordem (ATIVA ou PENDENTE) nao vai
     * ser recriada, e perguntar seria uma ida a rede por nada num botao que o lojista clica sem
     * pensar.</p>
     */
    private AsaasClient.EstadoDaAssinatura estadoParaDecidir(Assinatura a) {
        StatusAssinatura st = a.getStatus();
        if (st == StatusAssinatura.ATIVA || st == StatusAssinatura.PENDENTE) return null;
        return asaas.estadoDaAssinatura(a.getAsaasSubscriptionId());
    }

    /** A fatura em aberto da assinatura da loja, com o link de pagamento. Null quando nao ha. */
    public AsaasClient.FaturaAberta faturaEmAberto(Assinatura a) {
        if (a == null || a.getAsaasSubscriptionId() == null) return null;
        if (a.getStatus() != StatusAssinatura.INADIMPLENTE && a.getStatus() != StatusAssinatura.PENDENTE) {
            // So consulta o Asaas quando faz diferenca. Assinatura em dia nao tem fatura para mostrar,
            // e esta chamada custa uma ida a rede numa tela que o lojista abre a toda hora.
            return null;
        }
        return asaas.faturaAbertaDaAssinatura(a.getAsaasSubscriptionId());
    }

    /**
     * Põe a cobrança do Asaas no valor que a loja paga hoje (plano + add-on).
     *
     * <p>Chamado quando o Módulo IA é ligado ou desligado. Sem isto, o recurso era liberado na tela e
     * a cobrança continuava no valor antigo: o cliente usava R$ 99/mês de graça, ou pagava por um
     * add-on que já tinha sido desligado.</p>
     *
     * @return true se a cobrança lá fora foi atualizada.
     */
    @Transactional
    public boolean sincronizarValor(Long lojaId) {
        return "ATUALIZADA".equals(sincronizarValorComMotivo(lojaId));
    }

    /**
     * O mesmo, mas dizendo POR QUE a cobrança não mudou.
     *
     * <p>Um "não atualizou" tem causas muito diferentes — a loja nem assinou ainda, ou assinou e a
     * cobrança no Asaas ficou para trás. A tela do administrador mostrava a mesma frase para as duas,
     * e a segunda é um cliente usando R$ 99/mês de graça sem ninguém perceber.</p>
     *
     * @return ATUALIZADA · SEM_ASSINATURA · SEM_ID_NO_ASAAS · ASAAS_NAO_CONFIGURADO
     */
    @Transactional
    public String sincronizarValorComMotivo(Long lojaId) {
        Loja loja = lojas.findById(lojaId).orElse(null);
        Assinatura a = repo.findByLojaId(lojaId).orElse(null);
        if (loja == null || a == null) return "SEM_ASSINATURA";
        if (a.getAsaasSubscriptionId() == null) return "SEM_ID_NO_ASAAS";
        if (!asaas.configurado()) return "ASAAS_NAO_CONFIGURADO";
        BigDecimal novo = loja.precoComModulos();
        try {
            asaas.atualizarAssinatura(a.getAsaasSubscriptionId(), novo.doubleValue(),
                    "BoraHapp " + (loja.getPlano() == null ? Plano.UNICO : loja.getPlano()).name()
                            + " - " + loja.getNome() + (Boolean.TRUE.equals(loja.moduloIa) ? " + Modulo IA" : ""));
        } catch (Exception e) {
            log.warn("Loja {}: o Asaas recusou a troca de valor para {}: {}", lojaId, novo, e.getMessage());
            return "FALHA_NO_ASAAS";
        }
        // Só agora. Gravar antes fazia o nosso banco dizer R$ 298 enquanto o Asaas seguia cobrando
        // R$ 199 — exatamente a divergência que este método existe para evitar.
        a.setValor(novo);
        a.setAtualizadoEm(OffsetDateTime.now());
        repo.save(a);
        return "ATUALIZADA";
    }

    /** Reage aos eventos de pagamento do Asaas (webhook): ativa/suspende a loja conforme o pagamento. */
    @Transactional
    public void processarWebhook(String event, String subscriptionId) {
        processarWebhook(event, subscriptionId, null, null, null);
    }

    /**
     * Mesma coisa, mas também guarda a mensalidade recebida.
     *
     * <p>O pagamento não deixava rastro nenhum: virava um status e acabou. Sem o identificador da
     * cobrança, o valor e a data, não havia como dizer o que faturamos no mês nem onde pendurar a
     * nota fiscal de cada mensalidade.</p>
     */
    @Transactional
    public void processarWebhook(String event, String subscriptionId, String paymentId,
                                 BigDecimal valor, OffsetDateTime pagoEm) {
        if (event == null) return;
        // O estorno e tratado ANTES, e pela COBRANCA: o aviso de devolucao nem sempre carrega o numero
        // da assinatura, e sem isto ele caia fora na linha seguinte. A cobranca sabe de qual loja e.
        if (EVENTOS_DE_DEVOLUCAO.contains(event)) {
            tratarDevolucao(event, paymentId, subscriptionId);
            return;
        }
        if (subscriptionId == null) return;
        if ("PAYMENT_CONFIRMED".equals(event) || "PAYMENT_RECEIVED".equals(event)) {
            repo.findByAsaasSubscriptionId(subscriptionId)
                    .ifPresent(a -> registrarPagamento(a, paymentId, valor, pagoEm));
        }
        repo.findByAsaasSubscriptionId(subscriptionId).ifPresent(a -> {
            switch (event) {
                case "PAYMENT_CONFIRMED", "PAYMENT_RECEIVED" -> {
                    a.setStatus(StatusAssinatura.ATIVA);
                    ativarLoja(a.getLojaId(), true);
                    prazoDeAcesso(a.getLojaId(), null); // pagando, o acesso nao tem data de fim
                }
                case "PAYMENT_OVERDUE" -> {
                    a.setStatus(StatusAssinatura.INADIMPLENTE);
                    // A carencia comeca UMA vez, no primeiro atraso. Antes, cada aviso regravava
                    // "agora + 10 dias" -- e o Asaas reenvia o aviso, e a fatura do mes seguinte gera
                    // outro. Na pratica quem nunca pagava ganhava uns 10 dias de graca todo mes, para
                    // sempre. So define quando ainda nao ha prazo nenhum.
                    boolean semPrazo = lojas.findById(a.getLojaId())
                            .map(l -> l.acessoAte == null).orElse(false);
                    if (semPrazo) {
                        prazoDeAcesso(a.getLojaId(), OffsetDateTime.now().plusDays(carenciaDias));
                    }
                }
                case "SUBSCRIPTION_DELETED" -> {
                    a.setStatus(StatusAssinatura.CANCELADA);
                    ativarLoja(a.getLojaId(), false);
                    // Fechar so o cardapio nao bastava: o painel continuava aberto para sempre, porque
                    // quem estava pagando tem acessoAte NULL e o bloqueio so olha essa data. Cancelou,
                    // ganha o mesmo prazo da carencia e depois para -- em vez de usar de graca sem fim.
                    prazoDeAcesso(a.getLojaId(), OffsetDateTime.now().plusDays(carenciaDias));
                }
                case "PAYMENT_DELETED" -> {
                    // Uma cobranca avulsa apagada no painel do Asaas NAO e o fim da assinatura. Isto
                    // cancelava a assinatura inteira e derrubava a loja de um cliente que estava em dia.
                }
                default -> { /* demais eventos: ignorados */ }
            }
            a.setAtualizadoEm(OffsetDateTime.now());
            repo.save(a);
        });
    }

    /**
     * Guarda a mensalidade recebida, uma linha por cobrança paga.
     *
     * <p>O Asaas reenvia o mesmo aviso enquanto não recebe 200, então o mesmo pagamento chega mais de
     * uma vez. Sem a trava do identificador, a mesma mensalidade entraria duas vezes no faturamento do
     * mês — e dois pedidos de nota fiscal sairiam para o mesmo cliente.</p>
     */
    /** O que aconteceu no registro manual, para a tela contar a verdade ao dono. */
    public record PagamentoRegistrado(boolean baixadoNoAsaas, String cobranca, String recado) {}

    /**
     * Registra um pagamento que o lojista fez FORA do Asaas (PIX direto na conta, dinheiro).
     *
     * <p><b>Por que isto existe:</b> quem muda o estado da assinatura e o webhook do Asaas. Pagamento
     * feito fora dele nunca dispara webhook, entao o lojista paga e o sistema continua achando que
     * nao — e o corte por falta de pagamento tira a loja do ar na data da carencia. Foi o que
     * aconteceu com a Zira em 14/10/2026: ela pagou direto e o acesso dela vencia em 23/10.</p>
     *
     * <h2>Dar baixa no Asaas e parte do conserto, nao enfeite</h2>
     * <p>Registrar so no nosso banco deixaria a cobranca ABERTA la. O Asaas continua cobrando e
     * reenvia o aviso de atraso, que cai no nosso webhook como {@code PAYMENT_OVERDUE} — marcando a
     * assinatura INADIMPLENTE outra vez e abrindo nova carencia. O registro manual seria desfeito pelo
     * proprio Asaas, e a loja cortada dez dias depois. Por isso tentamos a baixa primeiro.</p>
     *
     * <p>Se a baixa der certo, o Asaas ainda dispara {@code PAYMENT_RECEIVED} e o webhook passa por
     * aqui de novo — sem duplicar, porque o {@link #registrarPagamento} ja ignora id repetido.</p>
     *
     * <p>Se nao houver cobranca aberta, ou o Asaas nao responder, o registro e feito do mesmo jeito
     * (com id {@code manual:...}) e o recado diz que a cobranca continua aberta la. Melhor a loja no
     * ar com a cobranca pendente do que a loja fora do ar por um pagamento que aconteceu.</p>
     */
    @Transactional
    public PagamentoRegistrado registrarPagamentoRecebidoForaDoAsaas(
            Long lojaId, BigDecimal valor, java.time.LocalDate pagoEm, String observacao) {
        return registrarPagamentoRecebidoForaDoAsaas(lojaId, valor, pagoEm, observacao, false);
    }

    /**
     * O mesmo, podendo CONFIRMAR que e um segundo pagamento do mesmo dia e valor.
     *
     * <h2>O defeito que isto fecha (medido em 21/10/2026)</h2>
     * <p>Sem cobranca aberta no Asaas, a chave do registro era
     * {@code "manual:" + lojaId + ":" + dia} -- a mesma para todo pagamento daquela loja naquele dia.
     * E o {@code registrarPagamento} descarta id repetido <b>em silencio</b>. Dois pagamentos
     * registrados no mesmo dia: o segundo desaparecia do faturamento, e a tela dizia "Pagamento
     * registrado".</p>
     *
     * <p><b>Conferido na producao:</b> a loja 18 tem dois registros de R$ 199 no mesmo dia
     * (15/10/2026), somando os R$ 398 que ela pagou. <b>Nao se perdeu nada</b> -- por sorte: um dos
     * dois achou cobranca aberta no Asaas e ficou com o id de la, entao as chaves nao colidiram. Hoje
     * a assinatura dela esta deletada no Asaas, ou seja: nao ha cobranca aberta, e o proximo par de
     * registros no mesmo dia COLIDIRIA.</p>
     *
     * <h2>Os dois erros opostos</h2>
     * <p>Chave unica por registro resolve a perda, mas abre a porta para o clique duplo contar duas
     * vezes -- e contar receita que nao entrou e tao ruim quanto perder a que entrou. Como distinguir
     * um do outro e impossivel pelo dado, quem decide e o dono:</p>
     * <ul>
     *   <li>mesmo dia e <b>mesmo valor</b> -> ambiguo. <b>Recusa</b> e pede confirmacao explicita.</li>
     *   <li>mesmo dia e <b>valor diferente</b> -> sao dois pagamentos distintos. Registra.</li>
     *   <li>confirmado -> registra com chave propria, e <b>nada e descartado em silencio</b>.</li>
     * </ul>
     */
    @Transactional
    public PagamentoRegistrado registrarPagamentoRecebidoForaDoAsaas(
            Long lojaId, BigDecimal valor, java.time.LocalDate pagoEm, String observacao,
            boolean confirmarSegundoPagamento) {

        Assinatura a = repo.findByLojaId(lojaId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Esta loja não tem assinatura; não há pagamento a registrar."));
        if (valor == null || valor.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o valor recebido.");
        }
        java.time.LocalDate dia = pagoEm != null ? pagoEm : java.time.LocalDate.now(ZONE);
        if (dia.isAfter(java.time.LocalDate.now(ZONE))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A data do pagamento não pode ser no futuro.");
        }

        // Ambiguidade entre clique duplo e segundo pagamento de verdade: so o dono sabe. Antes, o
        // sistema escolhia sozinho pelo pior caminho -- jogava o segundo fora e dizia que registrou.
        var doDia = pagamentosDaLojaNoDia(lojaId, dia);
        if (!confirmarSegundoPagamento) {
            var igual = doDia.stream()
                    .filter(pg -> pg.valor != null && pg.valor.compareTo(valor) == 0)
                    .findFirst();
            if (igual.isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Já há um pagamento de R$ " + valor.setScale(2) + " registrado para esta loja em "
                                + dia + ". Se for OUTRO pagamento, confirme para registrar os dois.");
            }
        }

        // 1) Baixa no Asaas, para ele parar de cobrar e de reenviar o aviso de atraso.
        boolean baixado = false;
        String cobranca = null;
        if (asaas.configurado() && a.getAsaasSubscriptionId() != null) {
            cobranca = asaas.cobrancaAbertaDaAssinatura(a.getAsaasSubscriptionId());
            if (cobranca != null) {
                baixado = asaas.receberEmDinheiro(cobranca, valor.doubleValue(), dia.toString());
            }
        }

        // 2) O mesmo efeito do PAYMENT_RECEIVED: a loja volta a ter acesso sem data de fim.
        a.setStatus(StatusAssinatura.ATIVA);
        ativarLoja(lojaId, true);
        prazoDeAcesso(lojaId, null);

        // 3) O registro, para o faturamento da plataforma contar este dinheiro.
        // A chave do registro manual tem um numero no fim. Era so "manual:loja:dia", igual para todo
        // pagamento daquela loja naquele dia, e o segundo caia no descarte silencioso.
        String idDoRegistro = baixado && cobranca != null && !pagamentos.existsByAsaasPaymentId(cobranca)
                ? cobranca
                : chaveManualLivre(lojaId, dia);
        registrarPagamento(a, idDoRegistro,
                valor, dia.atStartOfDay(ZONE).toOffsetDateTime());
        if (observacao != null && !observacao.isBlank()) {
            pagamentos.findFirstByAsaasPaymentId(idDoRegistro).ifPresent(p -> {
                p.descricao = descricaoComObservacao(p.descricao, observacao, lojaId);
                pagamentos.save(p);
            });
        }

        AUDITORIA.warn("AUDITORIA cobranca: pagamento de R$ {} da loja {} registrado A MAO (recebido "
                        + "fora do Asaas em {}); baixa no Asaas: {} (cobranca {})",
                valor, lojaId, dia, baixado ? "FEITA" : "NAO FEITA", cobranca);

        String recado = baixado
                ? "Pagamento registrado e cobrança baixada no Asaas. O acesso da loja voltou a não ter prazo."
                : cobranca == null
                    ? "Pagamento registrado. Não havia cobrança aberta no Asaas para dar baixa."
                    : "Pagamento registrado, MAS a baixa no Asaas falhou: a cobrança " + cobranca
                      + " continua aberta lá. Dê a baixa no painel do Asaas, senão o aviso de atraso "
                      + "dele vai marcar a loja como inadimplente de novo.";
        return new PagamentoRegistrado(baixado, cobranca, recado);
    }

    /** O que ja foi registrado para esta loja neste dia, por intervalo (ver o repositorio). */
    private java.util.List<PagamentoAssinatura> pagamentosDaLojaNoDia(Long lojaId, java.time.LocalDate dia) {
        return pagamentos.findByLojaIdAndPagoEmGreaterThanEqualAndPagoEmLessThan(lojaId,
                dia.atStartOfDay(ZONE).toOffsetDateTime(),
                dia.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
    }

    /**
     * A primeira chave manual livre para esta loja neste dia: {@code manual:18:2026-10-15:1},
     * {@code :2}, {@code :3}...
     *
     * <p>Por que procurar a livre em vez de usar um numero aleatorio: a chave continua LEGIVEL para
     * quem for conferir o faturamento a mao, e continua contando quantos registros houve naquele dia.
     * Um UUID resolveria a colisao e tornaria a conferencia impossivel.</p>
     *
     * <p>O limite de 50 nao e um numero bonito, e sim um teto para o laco nao virar infinito se
     * alguma coisa der muito errado -- cinquenta registros manuais da mesma loja no mesmo dia nao
     * acontece, e se acontecer e melhor estourar que gravar errado.</p>
     */
    private String chaveManualLivre(Long lojaId, java.time.LocalDate dia) {
        String base = "manual:" + lojaId + ":" + dia;
        // Sempre procura a primeira livre, do 1 em diante. A primeira versao disto tinha um retorno
        // antecipado -- "se a chave antiga sem numero nao existe, devolve :1" -- que devolvia :1
        // SEMPRE, porque a chave sem numero quase nunca existe. Isso reintroduzia exatamente o
        // descarte silencioso que este metodo veio consertar. O teste pegou.
        //
        // A chave antiga ("manual:18:2026-10-15", sem numero) nao colide com nenhuma ":n", entao nao
        // precisa de tratamento: ela so continua la, do formato anterior.
        for (int n = 1; n <= 50; n++) {
            String tentativa = base + ":" + n;
            if (!pagamentos.existsByAsaasPaymentId(tentativa)) return tentativa;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Ja ha 50 pagamentos registrados a mao para esta loja em " + dia
                        + ". Confira o faturamento antes de registrar outro.");
    }

    private void registrarPagamento(Assinatura a, String paymentId, BigDecimal valor, OffsetDateTime pagoEm) {
        if (paymentId == null || paymentId.isBlank()) return; // aviso sem cobrança: nada a guardar
        if (pagamentos.existsByAsaasPaymentId(paymentId)) return;
        PagamentoAssinatura p = new PagamentoAssinatura();
        p.lojaId = a.getLojaId();
        p.assinaturaId = a.getId();
        p.asaasPaymentId = paymentId;
        p.valor = valor != null ? valor : a.getValor();
        p.pagoEm = pagoEm != null ? pagoEm : OffsetDateTime.now();
        p.descricao = lojas.findById(a.getLojaId()).map(Loja::getNome).orElse(null);
        pagamentos.save(p);
    }

    /**
     * O nome da loja mais a observação, dentro do que a coluna aceita.
     *
     * <h2>Item 4.4 da varredura, e a consequência era pior do que ela dizia</h2>
     * <p>A observação vinha crua do corpo da requisição e era concatenada sem olhar tamanho, numa
     * coluna de {@value br.com.bora.entity.LarguraDeColuna#PAGAMENTO_DESCRICAO}. Como o método é
     * {@code @Transactional}, o Postgres recusando o UPDATE não cortava o texto: <b>desfazia o
     * registro inteiro do pagamento</b>. O dono digitaria uma observação um pouco mais longa, veria
     * um erro, e o pagamento que ele acabou de receber não entraria no faturamento da plataforma.</p>
     *
     * <h2>Quem perde espaço é a observação, não o nome</h2>
     * <p>O nome da loja é o que identifica esse dinheiro no faturamento — e {@code loja.nome} é
     * varchar(160), então sozinho ele já pode ocupar 160 dos 200. A observação é contexto extra, e
     * fica com o espaço que sobrar. Sem espaço nenhum, o nome fica sozinho e o log guarda a
     * observação inteira: melhor um registro certo com nota perdida no log que nota completa e
     * pagamento nenhum.</p>
     */
    private String descricaoComObservacao(String base, String observacao, Long lojaId) {
        String nome = base == null ? "" : base;
        String nota = observacao.trim();
        String separador = nome.isEmpty() ? "" : " · ";
        int sobra = br.com.bora.entity.LarguraDeColuna.PAGAMENTO_DESCRICAO - nome.length() - separador.length();
        if (sobra <= 0) {
            AUDITORIA.warn("AUDITORIA cobranca: a loja {} tem nome longo demais ({} caracteres) e a "
                            + "observacao do pagamento manual nao cabe na descricao. O pagamento foi "
                            + "registrado SEM a observacao. Ela era: {}",
                    lojaId, nome.length(), nota);
            return nome;
        }
        return nome + separador
                + br.com.bora.entity.LarguraDeColuna.encurtar(nota, sobra, "pagamento_assinatura.descricao da loja " + lojaId);
    }

    /**
     * O documento que vai para a cobrança.
     *
     * <p>A tela pede o CPF/CNPJ do responsável num campo vazio, digitado à mão. Em branco, o cliente
     * nascia no Asaas sem documento nenhum; digitado errado, nascia com o documento errado — foi assim
     * que uma loja foi parar lá com um CNPJ de teste. O cadastro da loja já tem o documento certo:
     * quando ninguém informa nada, é ele que vale.</p>
     */
    static String documentoDaCobranca(Loja loja, String informado) {
        if (informado != null && !informado.isBlank()) return informado.trim();
        String doCadastro = loja.getDocumento();
        if (doCadastro != null && !doCadastro.isBlank()) return doCadastro.trim();
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Informe o CPF/CNPJ do responsável: esta loja não tem documento no cadastro, e a "
                        + "cobrança não pode ser criada sem ele.");
    }

    /** Marca a mensalidade como devolvida, para ela sair do faturamento e da fila de notas. */
    /**
     * Os oito eventos de estorno e contestacao do Asaas.
     *
     * <p>Transcritos da documentacao oficial (docs.asaas.com/docs/payment-events, conferida em
     * 21/10/2026). O codigo antes tratava TRES deles, e tratava os tres igual. Nao sao a mesma coisa:
     * um diz que o dinheiro foi embora, outro que foi embora em parte, outro que a disputa foi GANHA e
     * o dinheiro esta voltando. Responder igual aos tres e errar em dois.</p>
     *
     * <p>Nao inventar nomes aqui: evento que o Asaas nao manda e codigo morto que parece tratado.</p>
     */
    private static final java.util.Set<String> EVENTOS_DE_DEVOLUCAO = java.util.Set.of(
            "PAYMENT_REFUNDED",                      // estornada integralmente
            "PAYMENT_PARTIALLY_REFUNDED",            // estornada em parte
            "PAYMENT_REFUND_IN_PROGRESS",            // estorno agendado, ainda nao executado
            "PAYMENT_REFUND_DENIED",                 // estorno negado (so boleto): o dinheiro fica
            "PAYMENT_RECEIVED_IN_CASH_UNDONE",       // desfizeram um recebimento em dinheiro
            "PAYMENT_CHARGEBACK_REQUESTED",          // chargeback recebido
            "PAYMENT_CHARGEBACK_DISPUTE",            // em disputa, documentos enviados
            "PAYMENT_AWAITING_CHARGEBACK_REVERSAL"   // disputa VENCIDA, dinheiro voltando
    );

    /** Os que querem dizer "o dinheiro desta mensalidade nao esta mais com a gente". */
    private static final java.util.Set<String> O_DINHEIRO_FOI_EMBORA = java.util.Set.of(
            "PAYMENT_REFUNDED",
            "PAYMENT_CHARGEBACK_REQUESTED",
            "PAYMENT_RECEIVED_IN_CASH_UNDONE"
    );

    /**
     * Devolucao, estorno e contestacao: o que cada um faz com o faturamento e com o ACESSO.
     *
     * <h2>O defeito que isto fecha (medido em 20/10/2026)</h2>
     * <p>Os eventos de estorno eram tratados antes de tudo e saiam da funcao. A assinatura continuava
     * ATIVA e {@code loja.acessoAte} continuava NULO -- e o bloqueio so olha essa data. Pagar, usar,
     * pedir estorno, receber o dinheiro de volta e <b>ficar com acesso sem prazo para sempre</b>. E
     * dava para repetir todo mes.</p>
     *
     * <h2>Por que cada evento tem uma resposta diferente</h2>
     * <ul>
     *   <li><b>O dinheiro foi embora</b> (estorno integral, chargeback recebido, recebimento em
     *       dinheiro desfeito): e o mesmo que nao ter pago. INADIMPLENTE e o prazo da carencia --
     *       nao corte imediato, porque pode ser engano e o lojista merece o mesmo prazo de quem
     *       atrasou.</li>
     *   <li><b>Estorno PARCIAL</b>: o lojista pagou a maior parte. Cortar o acesso por causa de uma
     *       devolucao de cortesia seria punir quem pagou.</li>
     *   <li><b>Estorno em andamento</b>: ainda nao aconteceu, e o {@code PAYMENT_REFUNDED} vem depois.
     *       Cortar agora seria cortar por uma intencao -- e o {@code PAYMENT_REFUND_DENIED} existe
     *       justamente porque o estorno pode nao sair.</li>
     *   <li><b>Estorno negado</b>: o dinheiro fica. Nada a fazer.</li>
     *   <li><b>Em disputa</b>: nenhuma informacao nova sobre o dinheiro -- o chargeback ja foi
     *       recebido e ja cortou. Reprocessar aqui REINICIARIA a carencia, que e o mesmo defeito ja
     *       corrigido no {@code PAYMENT_OVERDUE}: cada aviso regravava "agora + 10 dias".</li>
     *   <li><b>Disputa VENCIDA</b>: ganhamos, o dinheiro esta voltando. <b>Devolve o acesso</b> e o
     *       faturamento. Sem isto, o lojista que tinha razao ficaria cortado para sempre.</li>
     * </ul>
     */
    private void tratarDevolucao(String evento, String paymentId, String subscriptionId) {
        PagamentoAssinatura pg = paymentId == null || paymentId.isBlank() ? null
                : pagamentos.findByAsaasPaymentId(paymentId).orElse(null);

        // A loja vem da cobranca; se ela nao esta no nosso faturamento, tenta pela assinatura. Sem
        // nenhum dos dois nao da para agir -- so avisar.
        Long lojaId = pg != null ? pg.lojaId
                : (subscriptionId == null ? null
                   : repo.findByAsaasSubscriptionId(subscriptionId).map(Assinatura::getLojaId).orElse(null));

        if ("PAYMENT_AWAITING_CHARGEBACK_REVERSAL".equals(evento)) {
            if (pg != null && pg.estornado()) {
                pg.estornadoEm = null;                 // volta para o faturamento
                pagamentos.save(pg);
            }
            if (lojaId != null) devolverAcesso(lojaId, evento);
            else log.warn("{} chegou para a cobranca {} e nao achei a loja; o acesso NAO foi devolvido",
                    evento, paymentId);
            return;
        }

        if ("PAYMENT_PARTIALLY_REFUNDED".equals(evento)) {
            // NAO marca como estornada: isso tiraria a mensalidade INTEIRA do faturamento por causa de
            // uma devolucao parcial. Guardar quanto voltou pede uma coluna propria -- e o item 3.6 da
            // varredura, que continua aberto. Ate la, a mensalidade conta cheia e o aviso fica aqui.
            log.warn("ESTORNO PARCIAL na cobranca {} (loja {}): o acesso NAO foi cortado e a mensalidade "
                            + "continua CHEIA no faturamento. Confira o valor devolvido no Asaas e ajuste "
                            + "a mao. Ver varredura 3.6.", paymentId, lojaId);
            return;
        }

        if ("PAYMENT_REFUND_IN_PROGRESS".equals(evento) || "PAYMENT_REFUND_DENIED".equals(evento)) {
            // Nao mexe em nada. O IN_PROGRESS sera seguido de REFUNDED (que corta) ou de REFUND_DENIED
            // (que nao muda nada). Agir aqui seria agir por uma intencao.
            log.info("Asaas: {} na cobranca {} (loja {}). Nada alterado; o evento definitivo vem depois.",
                    evento, paymentId, lojaId);
            return;
        }

        if ("PAYMENT_CHARGEBACK_DISPUTE".equals(evento)) {
            // Ja cortou no CHARGEBACK_REQUESTED. Reprocessar reiniciaria a carencia.
            log.info("Asaas: chargeback da cobranca {} (loja {}) entrou em disputa. O corte e a carencia "
                    + "sao os do aviso anterior; nada foi reiniciado.", paymentId, lojaId);
            return;
        }

        // Aqui so sobra o que tirou o dinheiro de verdade.
        marcarEstorno(paymentId, evento);
        if (!O_DINHEIRO_FOI_EMBORA.contains(evento)) return;
        if (lojaId == null) {
            log.warn("{} chegou para a cobranca {} e nao achei a loja; o ACESSO NAO FOI CORTADO",
                    evento, paymentId);
            return;
        }
        cortarAcessoPorDevolucao(lojaId, evento, paymentId);
    }

    /**
     * O dinheiro voltou para o cliente: a loja passa a INADIMPLENTE e ganha a carencia.
     *
     * <p>Mesma carencia de quem atrasou, e definida <b>uma vez so</b>: um estorno seguido de disputa
     * nao pode empurrar o prazo de novo. E o mesmo cuidado que o {@code PAYMENT_OVERDUE} ja tinha.</p>
     */
    private void cortarAcessoPorDevolucao(Long lojaId, String evento, String paymentId) {
        repo.findByLojaId(lojaId).ifPresent(a -> {
            a.setStatus(StatusAssinatura.INADIMPLENTE);
            a.setAtualizadoEm(OffsetDateTime.now());
            repo.save(a);
        });
        boolean semPrazo = lojas.findById(lojaId).map(l -> l.acessoAte == null).orElse(false);
        if (semPrazo) {
            prazoDeAcesso(lojaId, OffsetDateTime.now().plusDays(carenciaDias));
        }
        AUDITORIA.warn("ACESSO COM PRAZO na loja {}: a cobranca {} foi devolvida ao cliente ({}). "
                        + "Assinatura INADIMPLENTE e acesso ate {} dias. Antes disto, estorno nao mexia "
                        + "no acesso e a loja seguia usando de graca.",
                lojaId, paymentId, evento, carenciaDias);
    }

    /** Ganhamos a disputa: o dinheiro esta voltando para nos, e o acesso volta para a loja. */
    private void devolverAcesso(Long lojaId, String evento) {
        repo.findByLojaId(lojaId).ifPresent(a -> {
            a.setStatus(StatusAssinatura.ATIVA);
            a.setAtualizadoEm(OffsetDateTime.now());
            repo.save(a);
        });
        ativarLoja(lojaId, true);
        prazoDeAcesso(lojaId, null);   // pagando, o acesso nao tem data de fim
        AUDITORIA.warn("ACESSO DEVOLVIDO a loja {}: a disputa foi vencida ({}). A mensalidade volta ao "
                + "faturamento e o acesso volta a nao ter prazo.", lojaId, evento);
    }

    private void marcarEstorno(String paymentId, String evento) {
        if (paymentId == null || paymentId.isBlank()) return;
        pagamentos.findByAsaasPaymentId(paymentId).ifPresentOrElse(pg -> {
            if (pg.estornado()) return; // o Asaas reenvia o aviso; marcar duas vezes nao muda nada
            pg.estornadoEm = OffsetDateTime.now();
            pagamentos.save(pg);
            log.warn("Mensalidade {} da loja {} ESTORNADA ({}): saiu do faturamento. Se a nota fiscal "
                    + "ja foi emitida, ela precisa ser cancelada.", paymentId, pg.lojaId, evento);
        }, () -> log.warn("Estorno {} chegou para a cobranca {}, que nao esta no nosso faturamento",
                evento, paymentId));
    }

    /**
     * Prazo de acesso de uma loja que acabou de ser reativada pela plataforma.
     *
     * <p>Se ela voltou com assinatura viva, nada muda. Se a assinatura foi cancelada na suspensão (o
     * caso normal), ela ganha a carência: usa o sistema, mas com data para reassinar. Sem isto a loja
     * voltava com acesso sem fim e sem cobrança nenhuma.</p>
     */
    @Transactional
    public String prazoAoReativar(Long lojaId) {
        Assinatura a = repo.findByLojaId(lojaId).orElse(null);
        if (a != null && a.getStatus() == StatusAssinatura.ATIVA) return "assinatura ativa, sem prazo";
        prazoDeAcesso(lojaId, OffsetDateTime.now().plusDays(carenciaDias));
        return "sem assinatura ativa: acesso liberado por " + carenciaDias + " dias";
    }

    /** Define (ou tira, com null) a data em que o acesso desta loja vence. */
    private void prazoDeAcesso(Long lojaId, OffsetDateTime ate) {
        lojas.findById(lojaId).ifPresent(l -> {
            l.acessoAte = ate;
            lojas.save(l);
        });
    }

    private void ativarLoja(Long lojaId, boolean ativo) {
        lojas.findById(lojaId).ifPresent(l -> {
            // Pagamento não levanta suspensão administrativa: quem suspendeu foi a plataforma,
            // e só a plataforma reativa. Sem esta guarda, a mensalidade seguinte desfaria a decisão.
            if (ativo && l.bloqueadaPelaPlataforma()) return;
            l.setAtivo(ativo);
            lojas.save(l);
        });
    }

    /**
     * Cancela a assinatura da loja no Asaas por decisão da plataforma (suspender/arquivar cliente).
     * Não lança quando a cobrança não está configurada ou a assinatura não existe — o cancelamento
     * do cliente aqui não pode ficar refém do gateway; devolve o que aconteceu para o admin ver.
     */
    @Transactional
    public String cancelarPorAdministracao(Long lojaId) {
        Assinatura a = repo.findByLojaId(lojaId).orElse(null);
        if (a == null) return "sem assinatura";
        if (a.getStatus() == StatusAssinatura.CANCELADA) return "assinatura já cancelada";
        String sub = a.getAsaasSubscriptionId();
        String resultado;
        if (sub == null || sub.isBlank()) {
            resultado = "assinatura sem id no Asaas";
        } else if (!asaas.configurado()) {
            resultado = "ATENÇÃO: cobrança não configurada, cancele no Asaas manualmente";
        } else {
            try {
                resultado = asaas.cancelarAssinatura(sub)
                        ? "assinatura cancelada no Asaas"
                        : "assinatura não encontrada no Asaas";
            } catch (Exception e) {
                return "ATENÇÃO: falha ao cancelar no Asaas (" + e.getMessage()
                        + ") — cancele manualmente para o cliente parar de ser cobrado";
            }
        }
        a.setStatus(StatusAssinatura.CANCELADA);
        a.setAtualizadoEm(OffsetDateTime.now());
        repo.save(a);
        return resultado;
    }
}
