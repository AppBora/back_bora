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

    /** Assina o plano atual da loja no Asaas (cria cliente + assinatura mensal). */
    @Transactional
    public Assinatura assinar(String cpfCnpj) {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        Long lojaId = ctx.lojaId();
        Assinatura jaAtiva = repo.findByLojaId(lojaId).orElse(null);
        if (jaAtiva != null && jaAtiva.getStatus() == StatusAssinatura.ATIVA) {
            return jaAtiva; // já há assinatura ativa — não recria nem reverte para PENDENTE
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
        // Reassinando (cancelada ou inadimplente): encerra a assinatura anterior no Asaas antes de
        // criar outra. Sem isto, o id antigo era sobrescrito e o cliente ficava com DUAS cobranças.
        if (a.getAsaasSubscriptionId() != null && asaas.configurado()) {
            asaas.cancelarAssinatura(a.getAsaasSubscriptionId());
        }
        if (a.getAsaasCustomerId() == null) {
            a.setAsaasCustomerId(asaas.criarCliente(loja.getNome(), email, documentoDaCobranca(loja, cpfCnpj)));
        }
        String nextDue = LocalDate.now().plusDays(7).toString(); // 7 dias de cortesia antes da 1ª cobrança
        Map<String, Object> sub = asaas.criarAssinatura(a.getAsaasCustomerId(), loja.precoComModulos().doubleValue(),
                "BoraHapp " + plano.name() + " - " + loja.getNome(), nextDue);
        a.setAsaasSubscriptionId(sub == null ? null : (String) sub.get("id"));
        a.setStatus(StatusAssinatura.PENDENTE);
        a.setAtualizadoEm(OffsetDateTime.now());
        return repo.save(a);
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
        // O estorno e tratado ANTES, e so pela cobranca: o aviso de devolucao nem sempre carrega o
        // numero da assinatura, e sem isto ele caia fora na linha seguinte. Dinheiro que voltou nao e
        // faturamento e nao pede nota fiscal.
        if ("PAYMENT_REFUNDED".equals(event) || "PAYMENT_CHARGEBACK_REQUESTED".equals(event)
                || "PAYMENT_CHARGEBACK_DISPUTE".equals(event)) {
            marcarEstorno(paymentId, event);
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
