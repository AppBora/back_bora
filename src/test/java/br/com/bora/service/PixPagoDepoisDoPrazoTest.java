package br.com.bora.service;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pedido de PIX que expira precisa fechar a cobrança no Asaas.
 *
 * <p>O comentário do próprio cobrador dizia que "o QR do Asaas costuma valer bem mais que isso, então
 * ninguém paga tarde". Era o contrário: por valer mais (ele nasce com vencimento no dia), dava para
 * pagar DEPOIS do cancelamento. O dinheiro caía na conta do lojista, o pedido seguia cancelado, o
 * cliente esperava a comida e a cozinha não tinha pedido nenhum.</p>
 */
class PixPagoDepoisDoPrazoTest {

    private PedidoRepository pedidos;
    private PixService pix;
    private LojaRepository lojas;
    private IntegracaoCanalRepository integracoes;
    private FidelidadeService fidelidade;
    private CobradorDePixService cobrador;

    @BeforeEach
    void montar() {
        pedidos = mock(PedidoRepository.class);
        pix = mock(PixService.class);
        lojas = mock(LojaRepository.class);
        integracoes = mock(IntegracaoCanalRepository.class);
        fidelidade = mock(FidelidadeService.class);
        cobrador = new CobradorDePixService(pedidos, fidelidade, lojas, integracoes, pix, mock(DevolucaoDeEstoqueService.class), GerenciadorDeTransacaoFalso.novo(), 30);
        when(pedidos.save(any(Pedido.class))).thenAnswer(i -> i.getArgument(0));
        when(lojas.findById(1L)).thenReturn(Optional.of(new Loja()));
        when(integracoes.findByLojaIdAndCanal(1L, "PIX")).thenReturn(Optional.empty());
    }

    private Pedido abandonado() {
        Pedido p = new Pedido();
        p.id = 7L;
        p.lojaId = 1L;
        p.status = StatusPedido.RECEBIDO;
        p.aguardandoPagamento = true;
        p.canalExterno = "PIX_ASAAS";
        p.idExterno = "pay_1";
        p.cashbackUsado = new BigDecimal("20.00");
        p.criadoEm = OffsetDateTime.now().minusHours(2);
        when(pedidos.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));
        return p;
    }

    @Test
    void aoExpirar_fechaACobrancaNoAsaas() {
        Pedido p = abandonado();

        cobrador.expirarAbandonados();

        assertEquals(StatusPedido.CANCELADO, p.status);
        verify(pix).cancelarCobranca(any(), any(), eq("pay_1"));
    }

    @Test
    void aoExpirar_devolveOCashbackAoCliente() {
        Pedido p = abandonado();
        p.clienteId = 42L;

        cobrador.expirarAbandonados();

        verify(fidelidade).devolver(1L, 42L, new BigDecimal("20.00"));
    }

    @Test
    void pedidoJaPago_naoEhCancelado_eNadaEhFechadoNoAsaas() {
        Pedido p = abandonado();
        p.pagoEm = OffsetDateTime.now();

        cobrador.expirarAbandonados();

        assertEquals(StatusPedido.RECEBIDO, p.status, "venda paga nao vira cancelamento");
        verify(pix, never()).cancelarCobranca(any(), any(), anyString());
    }

    @Test
    void falhaAoFecharNoAsaas_naoDerrubaOCancelamento() {
        Pedido p = abandonado();
        when(pix.cancelarCobranca(any(), any(), anyString())).thenReturn(false);

        assertDoesNotThrow(() -> cobrador.expirarAbandonados());
        assertEquals(StatusPedido.CANCELADO, p.status);
    }

    @Test
    void semNadaVencido_naoChamaOAsaas() {
        when(pedidos.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of());

        cobrador.expirarAbandonados();

        verify(pix, never()).cancelarCobranca(any(), any(), anyString());
    }
}
