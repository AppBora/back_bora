package br.com.bora.service;

import br.com.bora.entity.Cliente;
import br.com.bora.repository.ClienteRepository;
import br.com.bora.repository.ConfiguracaoLojaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * Cashback e histórico do cliente.
 *
 * <p>Vale nos dois caminhos: o pedido lançado no painel (com cliente escolhido) e o pedido feito
 * pelo próprio cliente no cardápio online, onde a identificação é o telefone.</p>
 *
 * <p>O percentual é por loja ({@code configuracao_loja.cashback_percentual}, padrão 5%). Isso
 * importa porque no cardápio online <b>todo</b> pedido tem telefone — o cashback incide em 100%
 * das vendas desse canal, e o lojista precisa poder ajustar ou desligar.</p>
 */
@Service
public class FidelidadeService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FidelidadeService.class);

    /** Usado quando a loja ainda não tem configuração — o mesmo 5% que valia antes. */
    private static final BigDecimal PADRAO = new BigDecimal("5");

    /** O resgate nunca zera o pedido: sobra este mínimo a pagar. */
    private static final BigDecimal MINIMO_A_PAGAR = new BigDecimal("0.01");

    private final ClienteRepository clientes;
    private final ConfiguracaoLojaRepository configs;

    public FidelidadeService(ClienteRepository clientes, ConfiguracaoLojaRepository configs) {
        this.clientes = clientes;
        this.configs = configs;
    }

    /** Percentual de cashback da loja, em %. Zero desliga o recurso. */
    public BigDecimal percentual(Long lojaId) {
        return configs.findByLojaId(lojaId)
                .map(c -> c.cashbackPercentual)
                .filter(v -> v != null && v.signum() >= 0)
                .orElse(PADRAO);
    }

    public boolean ativo(Long lojaId) {
        return percentual(lojaId).signum() > 0;
    }

    /** Só dígitos — o cliente digita o telefone de um jeito diferente a cada pedido. */
    public static String normalizar(String telefone) {
        if (telefone == null) return null;
        String so = telefone.replaceAll("\\D", "");
        return so.isBlank() ? null : so;
    }

    /**
     * Encontra o cliente pelo telefone ou cria um novo. É o que permite o cashback funcionar no
     * cardápio online, onde não existe cadastro nem login.
     */
    public Long identificarPeloTelefone(Long lojaId, String nome, String telefone, String endereco) {
        String tel = normalizar(telefone);
        if (tel == null) return null;
        return clientes.findFirstByLojaIdAndTelefone(lojaId, tel)
                .map(c -> {
                    // ITEM 8.6 DA VARREDURA. Antes disto, QUALQUER pessoa que digitasse o telefone de
                    // um cliente reescrevia o endereco cadastrado dele. Isso foi fechado em parte:
                    // passou a so completar o que estava em branco.
                    //
                    // So que "em branco" tambem e uma porta: telefone de cliente nao e segredo (esta
                    // no grupo do bairro, no comprovante, na agenda de muita gente). Um estranho
                    // identificado so pelo telefone gravava o endereco DELE no cadastro de um cliente
                    // que estava sem endereco -- e a proxima entrega da loja ia para la.
                    //
                    // Agora o cardapio NAO escreve endereco em cadastro que ja existe. E nao perde
                    // nada: desde a V48 o endereco da entrega fica no PROPRIO pedido
                    // (`pedido.endereco_entrega`), que e o que o motoboy le. O cadastro se corrige na
                    // tela de Clientes, por alguem logado.
                    if ((c.nome == null || c.nome.isBlank()) && nome != null) {
                        c.nome = nome;
                        return clientes.save(c).id;
                    }
                    // Nada a completar: nem grava. Uma escrita a menos em cadastro de cliente a cada
                    // pedido do cardapio.
                    return c.id;
                })
                .orElseGet(() -> {
                    Cliente novo = new Cliente();
                    novo.lojaId = lojaId;
                    novo.nome = nome == null || nome.isBlank() ? "Cliente do cardápio" : nome.trim();
                    novo.telefone = tel;
                    novo.endereco = endereco;
                    return clientes.save(novo).id;
                });
    }

    public BigDecimal saldo(Long lojaId, Long clienteId) {
        if (clienteId == null) return BigDecimal.ZERO;
        return clientes.findByIdAndLojaId(clienteId, lojaId)
                .map(c -> c.cashback == null ? BigDecimal.ZERO : c.cashback)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Saldo de quem se identificou no cardapio.
     *
     * <p>So o telefone nao basta: telefone de cliente nao e segredo (esta no grupo do bairro, no
     * comprovante, na agenda de muita gente), e com ele qualquer pessoa consultava o saldo alheio e
     * gastava o cashback do vizinho. Pedir tambem o <b>primeiro nome</b> do cadastro nao transforma
     * isto em autenticacao de verdade — isso exige um codigo por WhatsApp, que depende do numero da
     * loja estar ligado na Meta —, mas tira o caso de quem so tem o telefone na mao.</p>
     */
    public BigDecimal saldoPeloTelefone(Long lojaId, String telefone, String nomeInformado) {
        String tel = normalizar(telefone);
        if (tel == null) return BigDecimal.ZERO;
        return clientes.findFirstByLojaIdAndTelefone(lojaId, tel)
                .filter(c -> nomeConfere(c.nome, nomeInformado))
                .map(c -> c.cashback == null ? BigDecimal.ZERO : c.cashback)
                .orElse(BigDecimal.ZERO);
    }

    /** O saldo e deste cliente mesmo? Confere o nome informado no checkout contra o do cadastro. */
    public boolean ehOMesmoCliente(Long lojaId, Long clienteId, String nomeInformado) {
        if (clienteId == null) return false;
        return clientes.findByIdAndLojaId(clienteId, lojaId)
                .map(c -> nomeConfere(c.nome, nomeInformado))
                .orElse(false);
    }

    /**
     * O primeiro nome informado bate com o do cadastro? Compara sem acento, sem caixa e so o primeiro
     * nome: quem se cadastrou como "Maria Eduarda" nao pode perder o cashback por digitar "maria".
     */
    public static boolean nomeConfere(String nomeCadastrado, String nomeInformado) {
        String a = primeiroNome(nomeCadastrado), b = primeiroNome(nomeInformado);
        return a != null && b != null && a.equals(b);
    }

    private static String primeiroNome(String nome) {
        if (nome == null || nome.isBlank()) return null;
        String limpo = java.text.Normalizer.normalize(nome.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")       // tira acentos
                .toLowerCase()
                .replaceAll("[^a-z ]", " ")      // tira pontuacao e numeros
                .trim();
        if (limpo.isEmpty()) return null;
        return limpo.split(" ")[0];
    }

    /**
     * Quanto do saldo pode ser abatido deste pedido. Limita ao total menos um centavo para o
     * pedido nunca ficar zerado — um pagamento de R$ 0,00 não existe no PIX nem no caixa.
     */
    public BigDecimal resgatePossivel(Long lojaId, Long clienteId, BigDecimal total) {
        if (clienteId == null || total == null || total.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal saldo = saldo(lojaId, clienteId);
        if (saldo.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal teto = total.subtract(MINIMO_A_PAGAR);
        if (teto.signum() <= 0) return BigDecimal.ZERO;
        return saldo.min(teto).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Tira do saldo o cashback que o cliente acabou de usar, sem creditar nada.
     *
     * <p>Serve para o PIX: ali o desconto sai do total na hora do pedido, mas quem credita o cashback
     * novo é o webhook, depois do pagamento. Debitar só no webhook deixava o saldo intacto entre um
     * pedido e outro — o cliente usava os mesmos R$ 20 em quantos pedidos quisesse.</p>
     */
    public void consumir(Long lojaId, Long clienteId, BigDecimal valor) {
        mexerNoSaldo(lojaId, clienteId, valor, true);
    }

    /** Devolve o cashback de um pedido que não vai acontecer (PIX abandonado ou cancelado). */
    public void devolver(Long lojaId, Long clienteId, BigDecimal valor) {
        mexerNoSaldo(lojaId, clienteId, valor, false);
    }

    /**
     * Mexe no saldo, no BANCO. Ver o javadoc de {@link #registrar} para o porque.
     *
     * <p>Tirar e CONDICIONAL (so se o saldo cobrir) e estoura quando nao cobre: quem tira esta
     * cobrando um desconto que o cliente ja levou. Creditar nunca e condicional nem estoura: devolver
     * cashback de venda que nao aconteceu nao pode ser recusado.</p>
     */
    private void mexerNoSaldo(Long lojaId, Long clienteId, BigDecimal valor, boolean tirar) {
        if (clienteId == null || valor == null || valor.signum() <= 0) return;
        BigDecimal v = valor.setScale(2, RoundingMode.HALF_UP);
        if (!tirar) {
            clientes.creditar(clienteId, lojaId, v);
            return;
        }
        if (clientes.debitarSeTiver(clienteId, lojaId, v) == 0 && clienteAindaExiste(lojaId, clienteId)) {
            log.warn("Loja {}: o cliente {} nao tinha os R$ {} de cashback que este pedido ja havia "
                    + "descontado. Confira o saldo dele.", lojaId, clienteId, v);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "O seu saldo de cashback mudou enquanto você fechava o pedido. "
                            + "Confira o valor e tente de novo.");
        }
    }

    /**
     * O cliente ainda existe nesta loja?
     *
     * <h2>Por que esta pergunta existe (medido em 21/10/2026)</h2>
     * <p>As consultas de saldo devolvem "zero linha mexida" por DUAS razoes diferentes, e tratar as
     * duas igual foi um defeito que eu mesmo criei no item 1.5, mais cedo no mesmo dia:</p>
     * <ul>
     *   <li><b>O saldo nao cobre</b> — disputa de verdade. Outro pedido gastou primeiro, e o pedido
     *       tem que ser recusado: o desconto ja saiu do total.</li>
     *   <li><b>O cliente nao existe mais</b> — nao ha o que debitar. Apagar cliente nao apaga os
     *       pedidos dele ({@code pedido.cliente_id} nao tem chave estrangeira), entao sobra pedido
     *       apontando para cliente que foi embora.</li>
     * </ul>
     *
     * <p>Antes do item 1.5 isto era um {@code findByIdAndLojaId(...).ifPresent(...)}: cliente que nao
     * existe era silencio. Depois, passou a estourar 409 — e o caminho que isso quebra e de dinheiro:
     * PIX pago DEPOIS do cancelamento, num pedido que usou cashback, de um cliente que o lojista
     * apagou no meio. O webhook do Asaas recebia 409, e o Asaas reenvia enquanto nao recebe 200: o
     * pagamento nunca seria registrado. Uma consulta a mais, so quando a primeira falha, e barata
     * demais para pagar esse risco.</p>
     */
    private boolean clienteAindaExiste(Long lojaId, Long clienteId) {
        if (clientes.existsByIdAndLojaId(clienteId, lojaId)) return true;
        log.warn("Loja {}: o cliente {} nao existe mais, e um pedido ainda aponta para ele. Nao ha "
                        + "saldo de cashback para mexer -- seguindo sem recusar o pedido.",
                lojaId, clienteId);
        return false;
    }

    /**
     * Fecha o ciclo do pedido: soma ao histórico, debita o que foi resgatado e credita o cashback
     * novo sobre o valor efetivamente pago.
     */
    public void registrar(Long lojaId, Long clienteId, BigDecimal totalPago, BigDecimal resgate) {
        registrar(lojaId, clienteId, totalPago, resgate, true);
    }

    /**
     * Desfaz o que o pedido fez na fidelidade, porque a venda nao aconteceu. E o inverso exato de
     * {@link #registrar}: devolve o resgate, tira o cashback que a venda gerou e desconta a compra do
     * historico do cliente.
     *
     * <p><b>Por que isto existe:</b> cancelar um pedido no painel devolvia estoque e nao devolvia
     * cashback. Quem usou R$ 6,57 de saldo num pedido cancelado perdia o saldo. E, do outro lado, quem
     * fizesse dez pedidos "Na entrega" de R$ 100 e cancelasse depois ficava com os 5% de cada um — saldo
     * fabricado em cima de venda que nunca existiu, pago pelo lojista.</p>
     *
     * @param vendaJaContabilizada o {@code registrar} desta venda chegou a rodar? Pedido PIX que
     *                             expirou ou que nem gerou cobranca so teve o DEBITO do resgate
     *                             ({@link #consumir}); nele nao ha historico nem cashback novo para
     *                             desfazer, e tentar desfazer tiraria saldo que o cliente ganhou em
     *                             outro pedido.
     *
     * <p><b>Limite conhecido:</b> o cashback gerado nao fica gravado no pedido, entao o estorno
     * recalcula pelo percentual de HOJE. Se a loja mudou o percentual entre a venda e o cancelamento, a
     * parte estornada difere da creditada. O {@code max(ZERO)} garante que isso nunca deixa saldo
     * negativo. A correcao definitiva e um razao de movimentos, nao um saldo mutavel.</p>
     */
    public void estornar(Long lojaId, Long clienteId, BigDecimal totalPago, BigDecimal resgate,
                         boolean vendaJaContabilizada) {
        if (clienteId == null) return;
        BigDecimal usado = resgate == null ? BigDecimal.ZERO : resgate;
        BigDecimal pago = totalPago == null ? BigDecimal.ZERO : totalPago;
        BigDecimal taxa = vendaJaContabilizada
                ? percentual(lojaId).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        // UMA instrucao no banco. Era ler-somar-gravar, e o `.max(ZERO)` em Java so funcionava se
        // mais ninguem mexesse no saldo no meio -- ver o javadoc de `registrar`.
        //
        // NUNCA e condicional e NUNCA estoura: devolver cashback de venda que nao aconteceu nao pode
        // ser recusado. O `greatest(0, ...)` no SQL impede saldo negativo quando o cashback que a
        // venda gerou ja foi gasto em outro pedido.
        BigDecimal credito = pago.multiply(taxa).setScale(2, RoundingMode.HALF_UP);
        clientes.desfazerVenda(clienteId, lojaId,
                vendaJaContabilizada ? pago : BigDecimal.ZERO,
                usado, credito,
                vendaJaContabilizada ? 1 : 0);
    }

    /**
     * {@code creditarCashback=false}: soma a compra no histórico do cliente sem gerar saldo. É o caso do
     * cliente de marketplace sem telefone de verdade — ele nunca conseguiria usar esse saldo aqui.
     */
    /**
     * Registra a venda no historico do cliente: debita o resgate e credita o cashback novo.
     *
     * <h2>O defeito que isto fecha (varredura 1.5, medido em 21/10/2026)</h2>
     * <p>Era ler-somar-gravar em Java: {@code findByIdAndLojaId}, mexe nos campos, {@code save}. Dois
     * checkouts do mesmo cliente ao mesmo tempo liam o MESMO saldo antes de qualquer um gravar, e o
     * mesmo cashback valia nos dois pedidos. E o defeito que o estoque ja teve, agora no dinheiro. O
     * caso realista nao e dois aparelhos: e o cliente clicando duas vezes em "confirmar".</p>
     *
     * <h2>Por que ESTOURA quando o saldo nao cobre</h2>
     * <p>O desconto ja saiu do total que o cliente vai pagar quando esta linha roda. Se o saldo nao
     * cobre mais o resgate -- porque outro pedido gastou primeiro --, gravar o pedido daria um desconto
     * que ninguem pagou. Os dois chamadores que debitam resgate estao em transacao
     * ({@code PedidoService.criar} e o checkout do cardapio), entao estourar aqui desfaz o pedido
     * inteiro e o cliente recebe "seu saldo mudou, confira e tente de novo" -- que e a verdade.</p>
     *
     * <p>Com {@code resgate} zero nao ha o que disputar, e o metodo nunca estoura: e o caso dos
     * webhooks de pagamento e do pedido de marketplace.</p>
     */
    public void registrar(Long lojaId, Long clienteId, BigDecimal totalPago, BigDecimal resgate, boolean creditarCashback) {
        if (clienteId == null) return;
        BigDecimal usado = resgate == null ? BigDecimal.ZERO : resgate;
        BigDecimal pago = totalPago == null ? BigDecimal.ZERO : totalPago;
        BigDecimal taxa = creditarCashback
                ? percentual(lojaId).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal credito = pago.multiply(taxa).setScale(2, RoundingMode.HALF_UP);

        // UMA instrucao no banco, nao quatro leituras e uma gravacao.
        int mexeu = clientes.registrarVenda(clienteId, lojaId, pago, usado, credito, OffsetDateTime.now());
        if (mexeu == 0 && usado.signum() > 0 && clienteAindaExiste(lojaId, clienteId)) {
            log.warn("Loja {}: o cliente {} nao tinha mais R$ {} de cashback na hora de fechar o pedido "
                            + "-- outro pedido gastou o saldo primeiro. O pedido foi recusado.",
                    lojaId, clienteId, usado);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "O seu saldo de cashback mudou enquanto você fechava o pedido. "
                            + "Confira o valor e tente de novo.");
        }
    }
}
