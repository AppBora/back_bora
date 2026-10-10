package br.com.bora.service;

import br.com.bora.entity.Cliente;
import br.com.bora.entity.ConfiguracaoLoja;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.ClienteRepository;
import br.com.bora.repository.ConfiguracaoLojaRepository;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.ProdutoRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Cancelar um pedido devolve o cashback que ele consumiu, e tira o que ele gerou.
 *
 * <p><b>O bug que isto fecha (09/10/2026):</b> cancelar no painel devolvia estoque e NAO devolvia
 * cashback. O {@code fidelidade.devolver} era chamado na mao em dois dos quatro caminhos de
 * cancelamento (PIX que falhou e PIX expirado) e os outros dois esqueciam. Quem usou R$ 6,57 de saldo
 * num pedido que a loja cancelou perdia os R$ 6,57.</p>
 *
 * <p>O lado inverso tambem estava aberto: pedido "Na entrega" credita cashback na hora. Dez pedidos
 * falsos de R$ 100, cancelados depois, deixavam o cliente com 5% de cada um — saldo fabricado em cima
 * de venda que nunca existiu, descontado do caixa do lojista na proxima compra.</p>
 *
 * <p>Aqui a fidelidade NAO e mockada de proposito: o que precisa de prova e a conta, e um mock de
 * {@code FidelidadeService} provaria apenas que alguem chamou alguem. O {@code ClienteRepository} e o
 * unico mock, no lugar do banco.</p>
 */
class CancelamentoDevolveCashbackTest {

    private static final Long LOJA = 18L;
    private static final Long CLIENTE = 3L;

    private final ClienteRepository clientes = mock(ClienteRepository.class);
    private final ConfiguracaoLojaRepository configs = mock(ConfiguracaoLojaRepository.class);
    private final PedidoItemRepository itens = mock(PedidoItemRepository.class);

    /** Cliente com o saldo dado, e a loja com 5% de cashback (o padrao semeado). */
    private Cliente comSaldo(String saldo, String totalGasto, int qtdPedidos) {
        Cliente c = new Cliente();
        c.id = CLIENTE;
        c.lojaId = LOJA;
        c.nome = "Maria";
        c.telefone = "15999990001";
        c.cashback = new BigDecimal(saldo);
        c.totalGasto = new BigDecimal(totalGasto);
        c.qtdPedidos = qtdPedidos;
        when(clientes.findByIdAndLojaId(CLIENTE, LOJA)).thenReturn(Optional.of(c));
        when(clientes.save(any(Cliente.class))).thenAnswer(i -> i.getArgument(0));
        ConfiguracaoLoja cfg = new ConfiguracaoLoja();
        cfg.cashbackPercentual = new BigDecimal("5");
        when(configs.findByLojaId(LOJA)).thenReturn(Optional.of(cfg));
        return c;
    }

    private EstornoDaVendaService estorno() {
        return new EstornoDaVendaService(itens, mock(ProdutoRepository.class), mock(InsumoService.class),
                new FidelidadeService(clientes, configs));
    }

    private Pedido pedido(String total, String cashbackUsado) {
        Pedido p = new Pedido();
        p.id = 500L;
        p.lojaId = LOJA;
        p.clienteId = CLIENTE;
        p.valorTotal = new BigDecimal(total);
        if (cashbackUsado != null) p.cashbackUsado = new BigDecimal(cashbackUsado);
        when(itens.findByLojaIdAndPedidoIdOrderById(LOJA, 500L)).thenReturn(List.of());
        return p;
    }

    @Test
    void cancelarDevolveOCashbackQueOClienteGastou() {
        // Saldo tinha 20, o pedido abateu 6,57: sobraram 13,43. A venda foi de 43,43 e gerou 2,17 (5%).
        Cliente c = comSaldo("15.60", "243.43", 4);

        estorno().devolver(pedido("43.43", "6.57"), StatusPedido.RECEBIDO, true);

        // 15,60 + 6,57 devolvidos - 2,17 do cashback que a venda gerou = 20,00 (o saldo de antes)
        assertEquals(0, c.cashback.compareTo(new BigDecimal("20.00")));
    }

    @Test
    void cancelarTiraOCashbackQueAVendaGerou() {
        // Pedido de R$ 100 sem resgate: gerou 5,00. Cancelado, o saldo tem que voltar para 0.
        Cliente c = comSaldo("5.00", "100.00", 1);

        estorno().devolver(pedido("100.00", null), StatusPedido.RECEBIDO, true);

        assertEquals(0, c.cashback.compareTo(new BigDecimal("0.00")));
    }

    @Test
    void dezPedidosFalsosCanceladosNaoDeixamSaldoFabricado() {
        // O abuso que isto fecha: 10 pedidos "Na entrega" de R$ 100 geram R$ 50 de saldo.
        Cliente c = comSaldo("50.00", "1000.00", 10);
        EstornoDaVendaService svc = estorno();

        for (int i = 0; i < 10; i++) svc.devolver(pedido("100.00", null), StatusPedido.RECEBIDO, true);

        assertEquals(0, c.cashback.compareTo(new BigDecimal("0.00")));
        assertEquals(0, c.totalGasto.compareTo(new BigDecimal("0.00")));
        assertEquals(0, c.qtdPedidos);
    }

    @Test
    void cancelarDescontaACompraDoHistoricoDoCliente() {
        Cliente c = comSaldo("10.00", "243.43", 4);

        estorno().devolver(pedido("43.43", null), StatusPedido.RECEBIDO, true);

        assertEquals(0, c.totalGasto.compareTo(new BigDecimal("200.00")));
        assertEquals(3, c.qtdPedidos);
    }

    @Test
    void pixQueNuncaFoiPago_devolveOResgateESoIsso() {
        // Aqui houve apenas o DEBITO do resgate (consumir). Nao ha historico nem cashback novo: dizer
        // "true" tiraria do cliente um saldo que ele ganhou em OUTRO pedido.
        Cliente c = comSaldo("13.43", "500.00", 9);

        estorno().devolver(pedido("43.43", "6.57"), StatusPedido.RECEBIDO, false);

        assertEquals(0, c.cashback.compareTo(new BigDecimal("20.00")));
        assertEquals(0, c.totalGasto.compareTo(new BigDecimal("500.00"))); // intacto
        assertEquals(9, c.qtdPedidos);                   // intacto
    }

    @Test
    void oSaldoNuncaFicaNegativo() {
        // Loja mudou o percentual depois da venda, ou o cliente ja gastou o que ganhou. O estorno nao
        // pode criar divida: trava em zero.
        Cliente c = comSaldo("0.50", "100.00", 1);

        estorno().devolver(pedido("100.00", null), StatusPedido.RECEBIDO, true);

        assertEquals(0, c.cashback.compareTo(new BigDecimal("0.00")));
    }

    @Test
    void pedidoSemClienteNaoEstornaNada() {
        Pedido p = pedido("43.43", "6.57");
        p.clienteId = null;

        estorno().devolver(p, StatusPedido.RECEBIDO, true);

        verify(clientes, never()).save(any());
    }

    @Test
    void cancelarDepoisDeEntregarTambemDevolveOCashback() {
        // O estagio decide o que volta de ESTOQUE (comida feita nao volta). O cashback volta sempre:
        // nao e ingrediente, e dinheiro que o cliente pos num pedido que nao aconteceu.
        Cliente c = comSaldo("13.43", "243.43", 4);

        estorno().devolver(pedido("43.43", "6.57"), StatusPedido.SAIU_PARA_ENTREGA, true);

        assertEquals(0, c.cashback.compareTo(new BigDecimal("17.83"))); // 13,43 + 6,57 - 2,17
    }
}
