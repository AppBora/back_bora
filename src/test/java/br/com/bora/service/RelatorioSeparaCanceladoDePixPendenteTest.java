package br.com.bora.service;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.*;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * No relatorio, "cancelado" e o pedido cancelado — nao o que esta esperando PIX.
 *
 * <p><b>O bug que isto fecha (09/10/2026):</b> a conta era
 * {@code cancelados = todos.size() - vendas.size()}, e {@code vendas} exclui duas coisas diferentes:
 * o que foi cancelado E o PIX gerado que ainda nao foi pago. Resultado: todo checkout aberto
 * aparecia na tela como cancelamento. Numa loja com PIX ligado isso inflava o numero todo dia, e o
 * lojista lia como venda perdida o pedido que ainda podia entrar.</p>
 *
 * <p>Os dois numeros passaram a sair separados, porque sao decisoes diferentes: cancelamento se
 * investiga, PIX pendente se cobra.</p>
 */
class RelatorioSeparaCanceladoDePixPendenteTest {

    private static final Long LOJA = 18L;

    private Pedido ped(StatusPedido status, boolean aguardandoPix, boolean pago, String valor) {
        Pedido p = new Pedido();
        p.id = (long) (Math.random() * 1_000_000);
        p.lojaId = LOJA;
        p.status = status;
        p.valorTotal = new BigDecimal(valor);
        p.criadoEm = OffsetDateTime.now().minusHours(3);
        p.aguardandoPagamento = aguardandoPix;
        if (pago) p.pagoEm = OffsetDateTime.now().minusHours(2);
        return p;
    }

    private Map<String, Object> relatorio(List<Pedido> doBanco) {
        PedidoRepository pedidos = mock(PedidoRepository.class);
        when(pedidos.findByLojaIdAndCriadoEmAfterOrderByCriadoEmDesc(eq(LOJA), any())).thenReturn(doBanco);
        PedidoItemRepository itens = mock(PedidoItemRepository.class);
        when(itens.findByLojaIdAndPedidoIdIn(any(), any())).thenReturn(List.of());
        LojaRepository lojas = mock(LojaRepository.class);
        Loja l = new Loja();
        l.setNome("Zirá Açaíteria");
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, LOJA, "ADMINISTRADOR_LOJA", "admin@bora.app"));
        return new RelatorioService(pedidos, itens, mock(UsuarioLojaRepository.class), lojas, ctx)
                .gerar(30, null);
    }

    @Test
    void pixGeradoEAindaNaoPagoNaoEhCancelamento() {
        Map<String, Object> r = relatorio(List.of(
                ped(StatusPedido.ENTREGUE, false, false, "50.00"),
                ped(StatusPedido.RECEBIDO, true, false, "30.00"),   // esperando PIX
                ped(StatusPedido.RECEBIDO, true, false, "20.00"),   // esperando PIX
                ped(StatusPedido.CANCELADO, false, false, "40.00")  // cancelado de verdade
        ));

        assertEquals(1, r.get("cancelados"), "so o pedido CANCELADO conta como cancelado");
        assertEquals(2, r.get("aguardandoPagamento"));
    }

    @Test
    void pixPagoEntraNoFaturamentoENaoContaEmNenhumDosDois() {
        Map<String, Object> r = relatorio(List.of(
                ped(StatusPedido.ENTREGUE, true, true, "50.00") // PIX gerado E pago
        ));

        assertEquals(0, r.get("cancelados"));
        assertEquals(0, r.get("aguardandoPagamento"));
        assertEquals(1, r.get("pedidos"));
        assertEquals(0, ((BigDecimal) r.get("faturamento")).compareTo(new BigDecimal("50.00")));
    }

    @Test
    void pedidoCanceladoQueTambemEsperavaPixContaUmaVezSo() {
        // Caminho real: o cobrador cancela o PIX abandonado. Nao pode entrar nas duas contas.
        Pedido p = ped(StatusPedido.CANCELADO, true, false, "30.00");
        Map<String, Object> r = relatorio(List.of(p));

        assertEquals(1, r.get("cancelados"));
        assertEquals(0, r.get("aguardandoPagamento"));
    }

    @Test
    void lojaSemPedidoNenhumNaoInventaNumero() {
        Map<String, Object> r = relatorio(List.of());

        assertEquals(0, r.get("cancelados"));
        assertEquals(0, r.get("aguardandoPagamento"));
        assertEquals(0, r.get("pedidos"));
    }
}
