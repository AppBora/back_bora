package br.com.bora.controller;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.service.EstornoDaVendaService;
import br.com.bora.service.FidelidadeService;
import br.com.bora.service.RepetirPedidoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O webhook do PIX reabre o pedido cancelado, baixa o estoque de novo, e a tela do cliente confirma.
 *
 * <h2>Por que esta classe existe</h2>
 * <p>Achado por mutação em 21/10/2026, e é a <b>segunda vez no mesmo dia</b> que o mesmo buraco
 * aparece: duas mutações dentro do {@code pixWebhook} — apagar a volta para {@code RECEBIDO} e apagar
 * a chamada do {@code rebaixarDeNovo} — passaram com os <b>19 testes</b> das classes de serviço
 * verdes. Teste de serviço prova o serviço; ele não prova que alguém o chama. O conserto do item 2.7
 * poderia ser removido inteiro do controller sem nenhum teste reclamar.</p>
 *
 * <p>Esta classe cobre só a fiação do webhook: o que acontece com o pedido e quem é chamado.
 * A regra de "produto com ficha consome insumo, produto sem ficha baixa o próprio estoque" é provada
 * em {@code PedidoPagoDepoisDoCancelamentoReabreTest}.</p>
 *
 * <h2>O terceiro defeito, achado ao escrever isto</h2>
 * <p>O {@code statusPedido} dizia {@code pago} comparando o <b>texto</b> do {@code formaPagamento} com
 * {@code "PIX (pago)"}. Quando o dinheiro entra depois do cancelamento o texto é
 * {@code "PIX (pago APOS o cancelamento)"} — então a tela do cliente, que pergunta por esse campo a
 * cada 5 segundos ({@code cardapio.js:447}), <b>nunca</b> confirmava. Dinheiro na conta, pedido na
 * cozinha, e o cliente olhando "aguardando pagamento". Esse texto não é novo; o que mudou é que agora
 * o pedido reabre, e deixar o cliente no escuro passou a ser o erro mais visível dos três.</p>
 */
class PedidoReabreNoWebhookDoPixTest {

    private PedidoRepository pedidos;
    private EstornoDaVendaService devolucaoDeEstoque;
    private FidelidadeService fidelidade;
    private RepetirPedidoService repetir;
    private PublicController controller;

    private static final Long LOJA = 18L;
    private static final String TOKEN = "tok-da-loja";
    private static final String PAGAMENTO = "pay_abc";

    @BeforeEach
    void montar() {
        pedidos = mock(PedidoRepository.class);
        devolucaoDeEstoque = mock(EstornoDaVendaService.class);
        fidelidade = mock(FidelidadeService.class);
        repetir = mock(RepetirPedidoService.class);
        LojaRepository lojas = mock(LojaRepository.class);
        IntegracaoCanalRepository integracoes = mock(IntegracaoCanalRepository.class);

        Loja l = new Loja();
        l.id = LOJA;
        l.nome = "Zirá Açaíteria";
        l.asaasWebhookToken = TOKEN;
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));
        when(integracoes.findByLojaIdAndCanal(LOJA, "PIX")).thenReturn(Optional.of(new IntegracaoCanal()));

        var gerenciador = mock(org.springframework.transaction.PlatformTransactionManager.class);

        controller = new PublicController(lojas, mock(br.com.bora.repository.ProdutoRepository.class),
                pedidos, mock(br.com.bora.repository.PedidoItemRepository.class), integracoes,
                mock(br.com.bora.service.PixService.class),
                mock(br.com.bora.repository.ComplementoGrupoRepository.class),
                mock(br.com.bora.repository.ComplementoItemRepository.class),
                mock(br.com.bora.service.ComplementoService.class),
                mock(br.com.bora.repository.CupomRepository.class),
                mock(br.com.bora.service.DescontoService.class),
                mock(br.com.bora.security.FreioDoCardapio.class), fidelidade,
                mock(br.com.bora.service.OperacaoService.class),
                mock(br.com.bora.repository.TaxaEntregaRepository.class),
                mock(br.com.bora.service.InsumoService.class), devolucaoDeEstoque,
                mock(br.com.bora.security.RegraDeAcesso.class),
                mock(br.com.bora.repository.ConfiguracaoLojaRepository.class), repetir,
                gerenciador, false);
    }

    /** Um pedido de PIX que o cancelamento automático já fechou. */
    private Pedido cancelado() {
        Pedido p = new Pedido();
        p.id = 500L;
        p.lojaId = LOJA;
        p.status = StatusPedido.CANCELADO;
        p.canceladoEm = OffsetDateTime.now().minusMinutes(5);
        p.motivoCancelamento = "PIX nao pago em 30 minutos";
        p.clienteId = 77L;
        p.valorTotal = new BigDecimal("40.00");
        p.cashbackUsado = new BigDecimal("5.00");
        p.aguardandoPagamento = true;
        return p;
    }

    private Pedido vivo() {
        Pedido p = cancelado();
        p.status = StatusPedido.RECEBIDO;
        p.canceladoEm = null;
        p.motivoCancelamento = null;
        return p;
    }

    private void noBanco(Pedido p) {
        when(pedidos.findFirstByLojaIdAndCanalExternoAndIdExterno(LOJA, "PIX_ASAAS", PAGAMENTO))
                .thenReturn(Optional.of(p));
    }

    private Map<String, Object> aviso(String evento) {
        Map<String, Object> pagamento = new HashMap<>();
        pagamento.put("id", PAGAMENTO);
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("event", evento);
        corpo.put("payment", pagamento);
        return corpo;
    }

    // =============================================================== o pedido volta a valer

    @Test
    void pedidoCANCELADOvoltaParaRECEBIDO() {
        // A mutação que passou verde: apagar esta volta. O cliente pagaria, o dinheiro entraria, e o
        // pedido seguiria invisível para a cozinha -- a pior forma de perder uma venda.
        Pedido p = cancelado();
        noBanco(p);

        controller.pixWebhook(LOJA, TOKEN, aviso("PAYMENT_RECEIVED"));

        assertEquals(StatusPedido.RECEBIDO, p.status, "o pedido tem que aparecer no quadro da cozinha");
        assertNull(p.canceladoEm);
        assertNull(p.motivoCancelamento);
        verify(pedidos).save(p);
    }

    @Test
    void oESTOQUEeBAIXADOdeNOVO() {
        // A outra mutação que passou verde: apagar esta chamada. O cancelamento DEVOLVEU o estoque; se
        // ninguem baixa de novo, a comida sai e a contagem segue cheia.
        noBanco(cancelado());

        controller.pixWebhook(LOJA, TOKEN, aviso("PAYMENT_RECEIVED"));

        verify(devolucaoDeEstoque).rebaixarDeNovo(any(Pedido.class));
    }

    @Test
    void aOBSERVACAOcontaOqueAconteceu() {
        // E o unico lugar que a cozinha e o entregador leem no card. Sem isto, um pedido reaparece do
        // nada na fila e ninguem sabe por que.
        Pedido p = cancelado();
        p.observacao = "sem cebola";
        noBanco(p);

        controller.pixWebhook(LOJA, TOKEN, aviso("PAYMENT_RECEIVED"));

        assertTrue(p.observacao.startsWith("sem cebola"), "o recado do cliente nao se perde");
        assertTrue(p.observacao.contains("REABERTO"), "a observacao foi: " + p.observacao);
    }

    @Test
    void oCASHBACKusadoEreCONSUMIDO_eNENHUMnovoEgerado() {
        // O cancelamento devolveu o resgate ao cliente, mas ele pagou o valor JA descontado. Sem
        // reconsumir, o desconto valeria duas vezes. E cashback NOVO so para venda que de fato
        // aconteceu -- esta ainda pode ser cancelada de novo.
        noBanco(cancelado());

        controller.pixWebhook(LOJA, TOKEN, aviso("PAYMENT_RECEIVED"));

        verify(fidelidade).consumir(LOJA, 77L, new BigDecimal("5.00"));
        verify(fidelidade, never()).registrar(anyLong(), anyLong(), any(), any());
    }

    // =============================================================== o pedido normal nao muda

    @Test
    void pedidoQueNAOestavaCanceladoSEGUEoCaminhoDeSEMPRE() {
        // O conserto nao pode mexer no caso comum: PIX pago dentro do prazo nao rebaixa estoque (a
        // baixa aconteceu quando o pedido foi criado) e ganha cashback novo.
        Pedido p = vivo();
        noBanco(p);

        controller.pixWebhook(LOJA, TOKEN, aviso("PAYMENT_RECEIVED"));

        assertEquals(StatusPedido.RECEBIDO, p.status);
        assertEquals("PIX (pago)", p.formaPagamento);
        verify(devolucaoDeEstoque, never()).rebaixarDeNovo(any());
        verify(fidelidade).registrar(eq(LOJA), eq(77L), eq(new BigDecimal("40.00")), any());
        verify(fidelidade, never()).consumir(anyLong(), anyLong(), any());
    }

    @Test
    void oREENVIOdoMESMOavisoNAObaixaOestoqueDUASvezes() {
        // O Asaas reenvia o aviso quando nao recebe 200. O que impede a baixa dobrada e o status JA
        // ter sido gravado como RECEBIDO: no reenvio, `jaCancelado` e falso. Se alguem trocar a ordem
        // e salvar depois, este teste cai.
        Pedido p = cancelado();
        noBanco(p);

        controller.pixWebhook(LOJA, TOKEN, aviso("PAYMENT_RECEIVED"));
        controller.pixWebhook(LOJA, TOKEN, aviso("PAYMENT_RECEIVED"));

        verify(devolucaoDeEstoque, times(1)).rebaixarDeNovo(any(Pedido.class));
        verify(fidelidade, times(1)).consumir(anyLong(), anyLong(), any());
    }

    // =============================================================== a tela do cliente

    @Test
    void aTELAdoCLIENTEconfirmaMESMOpagandoDEPOISdoCancelamento() {
        // O defeito que apareceu ao escrever esta classe: a tela pergunta `pago` a cada 5 segundos, e
        // o campo vinha de comparar o TEXTO do formaPagamento. Pago depois do cancelamento, o texto e
        // outro, e a tela girava para sempre.
        Pedido p = cancelado();
        noBanco(p);
        when(repetir.tokenValido(LOJA, 500L, "t")).thenReturn(true);
        when(pedidos.findByIdAndLojaId(500L, LOJA)).thenReturn(Optional.of(p));

        controller.pixWebhook(LOJA, TOKEN, aviso("PAYMENT_RECEIVED"));
        Map<String, Object> tela = controller.statusPedido(LOJA, 500L, "t");

        assertEquals("PIX (pago APOS o cancelamento)", p.formaPagamento, "o rastro no texto fica");
        assertEquals(Boolean.TRUE, tela.get("pago"), "e a tela do cliente confirma do mesmo jeito");
        assertEquals("RECEBIDO", tela.get("status"));
    }

    @Test
    void pedidoAINDAnaoPAGOcontinuaDizendoQueNAOfoiPago() {
        // O outro lado: `pagoEm` nulo e pedido sem pagamento nenhum. Se este teste cair, a tela passa
        // a confirmar pagamento que nao entrou.
        Pedido p = vivo();
        p.pagoEm = null;
        when(repetir.tokenValido(LOJA, 500L, "t")).thenReturn(true);
        when(pedidos.findByIdAndLojaId(500L, LOJA)).thenReturn(Optional.of(p));

        assertEquals(Boolean.FALSE, controller.statusPedido(LOJA, 500L, "t").get("pago"));
    }

    // =============================================================== as guardas que ja existiam

    @Test
    void EVENTOqueNAOeDEpagamentoNAOmexeEMnada() {
        Pedido p = cancelado();
        noBanco(p);

        controller.pixWebhook(LOJA, TOKEN, aviso("PAYMENT_OVERDUE"));

        assertEquals(StatusPedido.CANCELADO, p.status);
        verify(devolucaoDeEstoque, never()).rebaixarDeNovo(any());
        verify(pedidos, never()).save(any(Pedido.class));
    }

    @Test
    void TOKENerradoNAOreabrePedidoNENHUM() {
        // Sem isto, qualquer um na internet reabriria pedido cancelado e faria estoque sair.
        noBanco(cancelado());

        var erro = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.pixWebhook(LOJA, "token-de-outro", aviso("PAYMENT_RECEIVED")));

        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, erro.getStatusCode());
        verify(devolucaoDeEstoque, never()).rebaixarDeNovo(any());
    }

    // =============================================================== item 9.6: pagamento sem pedido

    /** Pendura um coletor no log de AUDITORIA e devolve o que ele escrever. */
    private java.util.List<String> coletandoAauditoria(Runnable acao) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("AUDITORIA");
        var coletor = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        coletor.start();
        logger.addAppender(coletor);
        try {
            acao.run();
        } finally {
            logger.detachAppender(coletor);
            coletor.stop();
        }
        return coletor.list.stream().map(e -> e.getFormattedMessage()).toList();
    }

    private Map<String, Object> avisoComValor(String evento, String valor) {
        Map<String, Object> corpo = aviso(evento);
        @SuppressWarnings("unchecked")
        Map<String, Object> pagamento = (Map<String, Object>) corpo.get("payment");
        pagamento.put("value", valor);
        return corpo;
    }

    @org.junit.jupiter.api.Test
    void pagamentoSEMpedido_DEIXArastroNAauditoria() {
        // ITEM 9.6 DA VARREDURA: isto era um `ifPresent` seco. Pagamento confirmado para um id que
        // nao casa com pedido nenhum devolvia 200 e NAO deixava rastro em lugar nenhum -- o dinheiro
        // entrou na conta do lojista, nao ha pedido, e ninguem tem como ligar uma coisa na outra
        // depois. Acontece de verdade: pedido apagado, cobranca criada e o pedido perdido antes de
        // gravar, cliente pagando um link antigo.
        when(pedidos.findFirstByLojaIdAndCanalExternoAndIdExterno(LOJA, "PIX_ASAAS", PAGAMENTO))
                .thenReturn(Optional.empty());

        var linhas = coletandoAauditoria(
                () -> controller.pixWebhook(LOJA, TOKEN, avisoComValor("PAYMENT_RECEIVED", "40.00")));

        assertEquals(1, linhas.size(), "esperava uma linha de auditoria; foi: " + linhas);
        String linha = linhas.get(0);
        assertTrue(linha.contains(PAGAMENTO), "tem que dizer QUAL pagamento, para dar conciliar: " + linha);
        assertTrue(linha.contains("40.00"), "e o valor que entrou: " + linha);
        assertTrue(linha.contains(String.valueOf(LOJA)), "e de qual loja: " + linha);
    }

    @org.junit.jupiter.api.Test
    void pagamentoSEMpedido_CONTINUAdevolvendo200() {
        // De proposito: o Asaas reenvia o que nao recebe 200, e reenviar nao vai fazer o pedido
        // aparecer. Devolver erro aqui so criaria uma fila de reentrega crescendo sozinha.
        when(pedidos.findFirstByLojaIdAndCanalExternoAndIdExterno(LOJA, "PIX_ASAAS", PAGAMENTO))
                .thenReturn(Optional.empty());

        var r = controller.pixWebhook(LOJA, TOKEN, aviso("PAYMENT_RECEIVED"));

        assertEquals("true", r.get("received"));
        verify(pedidos, never()).save(any());
    }

    @org.junit.jupiter.api.Test
    void pagamentoCOMpedido_naoESCREVEnaAUDITORIA() {
        // O risco do outro lado: avisar em todo pagamento faria o log de auditoria virar ruido, e aí
        // o caso que importa se perde no meio.
        when(pedidos.findFirstByLojaIdAndCanalExternoAndIdExterno(LOJA, "PIX_ASAAS", PAGAMENTO))
                .thenReturn(Optional.of(vivo()));

        var linhas = coletandoAauditoria(
                () -> controller.pixWebhook(LOJA, TOKEN, aviso("PAYMENT_RECEIVED")));

        assertTrue(linhas.isEmpty(), "pagamento que casou com pedido nao e caso de auditoria: " + linhas);
    }
}
