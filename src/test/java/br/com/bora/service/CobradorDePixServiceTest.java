package br.com.bora.service;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.PedidoRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checkout abandonado: o cliente gera o PIX, fecha a página e some. Antes isso virava uma venda
 * eterna no faturamento e um pedido fantasma apitando na cozinha.
 */
class CobradorDePixServiceTest {

    private Pedido pedido(boolean aguardando, OffsetDateTime pagoEm, StatusPedido status) {
        Pedido p = new Pedido();
        p.id = 1L;
        p.lojaId = 18L;
        p.status = status;
        p.aguardandoPagamento = aguardando;
        p.pagoEm = pagoEm;
        p.criadoEm = OffsetDateTime.now().minusHours(2);
        return p;
    }

    @Test
    void pixNaoPagoDepoisDoPrazoEhCancelado() {
        PedidoRepository repo = mock(PedidoRepository.class);
        Pedido p = pedido(true, null, StatusPedido.RECEBIDO);
        when(repo.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));

        new CobradorDePixService(repo, mock(FidelidadeService.class), mock(br.com.bora.repository.LojaRepository.class), mock(br.com.bora.repository.IntegracaoCanalRepository.class), mock(PixService.class), mock(EstornoDaVendaService.class), GerenciadorDeTransacaoFalso.novo(), 30).expirarAbandonados();

        assertEquals(StatusPedido.CANCELADO, p.status);
        assertFalse(Boolean.TRUE.equals(p.aguardandoPagamento));
        assertNotNull(p.canceladoEm);
        assertTrue(p.motivoCancelamento != null && p.motivoCancelamento.contains("30 minutos"),
                "o lojista precisa entender por que o pedido sumiu: " + p.motivoCancelamento);
        verify(repo).save(p);
    }

    @Test
    void pagoEntreAConsultaEOCancelamentoNaoEhCancelado() {
        PedidoRepository repo = mock(PedidoRepository.class);
        Pedido p = pedido(true, OffsetDateTime.now(), StatusPedido.RECEBIDO);
        when(repo.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));

        new CobradorDePixService(repo, mock(FidelidadeService.class), mock(br.com.bora.repository.LojaRepository.class), mock(br.com.bora.repository.IntegracaoCanalRepository.class), mock(PixService.class), mock(EstornoDaVendaService.class), GerenciadorDeTransacaoFalso.novo(), 30).expirarAbandonados();

        assertEquals(StatusPedido.RECEBIDO, p.status, "cancelar venda ja paga seria o pior erro possivel");
        verify(repo, never()).save(any(Pedido.class));
    }

    @Test
    void nadaParaExpirar_naoMexeEmNada() {
        PedidoRepository repo = mock(PedidoRepository.class);
        when(repo.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of());

        new CobradorDePixService(repo, mock(FidelidadeService.class), mock(br.com.bora.repository.LojaRepository.class), mock(br.com.bora.repository.IntegracaoCanalRepository.class), mock(PixService.class), mock(EstornoDaVendaService.class), GerenciadorDeTransacaoFalso.novo(), 30).expirarAbandonados();

        verify(repo, never()).save(any(Pedido.class));
    }

    @Test
    void pedidoJaCanceladoSoPerdeAMarcaDeEspera() {
        PedidoRepository repo = mock(PedidoRepository.class);
        Pedido p = pedido(true, null, StatusPedido.CANCELADO);
        when(repo.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));

        new CobradorDePixService(repo, mock(FidelidadeService.class), mock(br.com.bora.repository.LojaRepository.class), mock(br.com.bora.repository.IntegracaoCanalRepository.class), mock(PixService.class), mock(EstornoDaVendaService.class), GerenciadorDeTransacaoFalso.novo(), 30).expirarAbandonados();

        assertNull(p.motivoCancelamento, "nao reescreve o motivo de um cancelamento que ja existia");
        verify(repo, never()).save(any(Pedido.class));
    }

    @Test
    void pedidoQueEsperaPagamentoNaoEhFaturamento() {
        Pedido esperando = pedido(true, null, StatusPedido.RECEBIDO);
        Pedido pago = pedido(true, OffsetDateTime.now(), StatusPedido.RECEBIDO);
        Pedido balcao = pedido(false, null, StatusPedido.ENTREGUE);

        assertTrue(esperando.pagamentoPendente());
        assertFalse(pago.pagamentoPendente(), "pagou, virou venda");
        assertFalse(balcao.pagamentoPendente(), "balcao e WhatsApp nunca esperam PIX online");
    }

    // ---------------------------------------------------------------------------------------------
    // O webhook falhou: o dinheiro entrou e o aviso nunca chegou.
    //
    // Medido em producao em 15/10/2026: a subconta da loja 18 tinha o token gravado no nosso banco e
    // ZERO webhooks registrados no Asaas. Nesse estado, todo PIX pago virava pedido CANCELADO em 30
    // minutos -- estoque e cashback devolvidos, cliente sem a comida e sem o dinheiro.
    // ---------------------------------------------------------------------------------------------

    /** Monta o servico com um PixService que responde o que o teste mandar. */
    private CobradorDePixService cobrador(PedidoRepository repo, PixService pix, FidelidadeService fid,
                                          EstornoDaVendaService estorno) {
        return new CobradorDePixService(repo, fid,
                mock(br.com.bora.repository.LojaRepository.class),
                mock(br.com.bora.repository.IntegracaoCanalRepository.class),
                pix, estorno, GerenciadorDeTransacaoFalso.novo(), 30);
    }

    @Test
    void cobrancaPAGAnoAsaasNaoEhCancelada_mesmoSemOAvisoTerChegado() {
        PedidoRepository repo = mock(PedidoRepository.class);
        Pedido p = pedido(true, null, StatusPedido.RECEBIDO);
        p.idExterno = "pay_123";
        p.valorTotal = new java.math.BigDecimal("54.90");
        when(repo.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));
        when(repo.findById(1L)).thenReturn(java.util.Optional.of(p));
        PixService pix = mock(PixService.class);
        when(pix.cobrancaFoiPaga(any(), any(), eq("pay_123"))).thenReturn(true);
        EstornoDaVendaService estorno = mock(EstornoDaVendaService.class);

        cobrador(repo, pix, mock(FidelidadeService.class), estorno).expirarAbandonados();

        assertEquals(StatusPedido.RECEBIDO, p.status, "o dinheiro entrou; cancelar seria tirar a venda do lojista");
        assertNotNull(p.pagoEm, "o pedido tem que virar venda paga");
        assertFalse(Boolean.TRUE.equals(p.aguardandoPagamento), "sai da espera e aparece na cozinha");
        assertEquals("PIX (pago)", p.formaPagamento);
        verify(estorno, never()).devolver(any(), any(), anyBoolean());
        verify(pix, never()).cancelarCobranca(any(), any(), anyString());
    }

    @Test
    void cobrancaPagaCreditaOCashbackUMAvezSo() {
        PedidoRepository repo = mock(PedidoRepository.class);
        Pedido p = pedido(true, null, StatusPedido.RECEBIDO);
        p.idExterno = "pay_123";
        p.clienteId = 99L;
        p.valorTotal = new java.math.BigDecimal("54.90");
        when(repo.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));
        when(repo.findById(1L)).thenReturn(java.util.Optional.of(p));
        PixService pix = mock(PixService.class);
        when(pix.cobrancaFoiPaga(any(), any(), eq("pay_123"))).thenReturn(true);
        FidelidadeService fid = mock(FidelidadeService.class);

        CobradorDePixService c = cobrador(repo, pix, fid, mock(EstornoDaVendaService.class));
        c.expirarAbandonados();
        c.expirarAbandonados(); // a rodada seguinte, 5 minutos depois

        verify(fid, times(1)).registrar(eq(18L), eq(99L), eq(new java.math.BigDecimal("54.90")), any());
    }

    @Test
    void asaasDizQueNAOfoiPago_segueCancelandoComoSempre() {
        PedidoRepository repo = mock(PedidoRepository.class);
        Pedido p = pedido(true, null, StatusPedido.RECEBIDO);
        p.idExterno = "pay_123";
        when(repo.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));
        PixService pix = mock(PixService.class);
        when(pix.cobrancaFoiPaga(any(), any(), anyString())).thenReturn(false);
        EstornoDaVendaService estorno = mock(EstornoDaVendaService.class);

        cobrador(repo, pix, mock(FidelidadeService.class), estorno).expirarAbandonados();

        assertEquals(StatusPedido.CANCELADO, p.status);
        verify(estorno).devolver(eq(p), any(), eq(false));
    }

    @Test
    void asaasForaDoAr_naoInventaPagamento() {
        // cobrancaFoiPaga nunca lanca e devolve false quando nao conseguiu perguntar. O pedido segue
        // o caminho de sempre: melhor cancelar um checkout abandonado do que liberar comida de graca.
        PedidoRepository repo = mock(PedidoRepository.class);
        Pedido p = pedido(true, null, StatusPedido.RECEBIDO);
        p.idExterno = "pay_123";
        when(repo.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));
        PixService pix = mock(PixService.class);
        when(pix.cobrancaFoiPaga(any(), any(), anyString())).thenReturn(false);

        cobrador(repo, pix, mock(FidelidadeService.class), mock(EstornoDaVendaService.class)).expirarAbandonados();

        assertEquals(StatusPedido.CANCELADO, p.status);
        assertNull(p.pagoEm);
    }

    @Test
    void doisPedidos_umPagoEOutroNao_cadaUmSegueOSeuCaminho() {
        PedidoRepository repo = mock(PedidoRepository.class);
        Pedido pago = pedido(true, null, StatusPedido.RECEBIDO);
        pago.idExterno = "pay_pago";
        Pedido abandonado = pedido(true, null, StatusPedido.RECEBIDO);
        abandonado.id = 2L;
        abandonado.idExterno = "pay_nada";
        when(repo.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(pago, abandonado));
        when(repo.findById(1L)).thenReturn(java.util.Optional.of(pago));
        PixService pix = mock(PixService.class);
        when(pix.cobrancaFoiPaga(any(), any(), eq("pay_pago"))).thenReturn(true);
        when(pix.cobrancaFoiPaga(any(), any(), eq("pay_nada"))).thenReturn(false);

        cobrador(repo, pix, mock(FidelidadeService.class), mock(EstornoDaVendaService.class)).expirarAbandonados();

        assertEquals(StatusPedido.RECEBIDO, pago.status);
        assertNotNull(pago.pagoEm);
        assertEquals(StatusPedido.CANCELADO, abandonado.status);
    }

    @Test
    void pedidoSemCobrancaNoAsaasNaoEhPerguntado() {
        PedidoRepository repo = mock(PedidoRepository.class);
        Pedido p = pedido(true, null, StatusPedido.RECEBIDO);
        p.idExterno = null;
        when(repo.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));
        PixService pix = mock(PixService.class);

        cobrador(repo, pix, mock(FidelidadeService.class), mock(EstornoDaVendaService.class)).expirarAbandonados();

        assertEquals(StatusPedido.CANCELADO, p.status);
    }
}
