package br.com.bora.service;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.PedidoRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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

        new CobradorDePixService(repo, mock(FidelidadeService.class), mock(br.com.bora.repository.LojaRepository.class), mock(br.com.bora.repository.IntegracaoCanalRepository.class), mock(PixService.class), mock(DevolucaoDeEstoqueService.class), 30).expirarAbandonados();

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

        new CobradorDePixService(repo, mock(FidelidadeService.class), mock(br.com.bora.repository.LojaRepository.class), mock(br.com.bora.repository.IntegracaoCanalRepository.class), mock(PixService.class), mock(DevolucaoDeEstoqueService.class), 30).expirarAbandonados();

        assertEquals(StatusPedido.RECEBIDO, p.status, "cancelar venda ja paga seria o pior erro possivel");
        verify(repo, never()).save(any(Pedido.class));
    }

    @Test
    void nadaParaExpirar_naoMexeEmNada() {
        PedidoRepository repo = mock(PedidoRepository.class);
        when(repo.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of());

        new CobradorDePixService(repo, mock(FidelidadeService.class), mock(br.com.bora.repository.LojaRepository.class), mock(br.com.bora.repository.IntegracaoCanalRepository.class), mock(PixService.class), mock(DevolucaoDeEstoqueService.class), 30).expirarAbandonados();

        verify(repo, never()).save(any(Pedido.class));
    }

    @Test
    void pedidoJaCanceladoSoPerdeAMarcaDeEspera() {
        PedidoRepository repo = mock(PedidoRepository.class);
        Pedido p = pedido(true, null, StatusPedido.CANCELADO);
        when(repo.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));

        new CobradorDePixService(repo, mock(FidelidadeService.class), mock(br.com.bora.repository.LojaRepository.class), mock(br.com.bora.repository.IntegracaoCanalRepository.class), mock(PixService.class), mock(DevolucaoDeEstoqueService.class), 30).expirarAbandonados();

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
}
