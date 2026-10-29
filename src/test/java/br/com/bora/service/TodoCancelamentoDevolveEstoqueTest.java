package br.com.bora.service;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.*;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * Os quatro caminhos de cancelamento chamam a devolucao de estoque.
 *
 * <p>A regra em si vive no {@link EstornoDaVendaService} e e testada em
 * {@code EstoqueVoltaNoCancelamentoTest}. O risco que ESTE teste cobre e outro: esquecer de ligar a
 * devolucao em um dos caminhos. Foi exatamente o que aconteceu — o cardapio publico passou a baixar
 * estoque e nenhum dos cancelamentos desfazia, em nenhum lugar.</p>
 *
 * <p>Cada caminho tem que passar o status que o pedido tinha ANTES de virar CANCELADO: e ele que diz
 * se o ingrediente ainda existe. Passar o status ja cancelado devolveria tudo sempre.</p>
 */
class TodoCancelamentoDevolveEstoqueTest {

    private final EstornoDaVendaService devolucao = mock(EstornoDaVendaService.class);

    private Pedido pedido(StatusPedido status) {
        Pedido p = new Pedido();
        p.id = 1L; p.lojaId = 18L; p.status = status;
        p.criadoEm = OffsetDateTime.now().minusHours(2);
        return p;
    }

    @Test
    void pixExpirado_devolveComOStatusAnterior() {
        PedidoRepository repo = mock(PedidoRepository.class);
        when(repo.avancarSeAindaEsta(any(), any(), any(), any(), any())).thenReturn(1);
        Pedido p = pedido(StatusPedido.RECEBIDO);
        p.aguardandoPagamento = true;
        when(repo.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));

        new CobradorDePixService(repo, mock(FidelidadeService.class), mock(LojaRepository.class),
                mock(IntegracaoCanalRepository.class), mock(PixService.class), devolucao, GerenciadorDeTransacaoFalso.novo(), 30)
                .expirarAbandonados();

        // false: PIX que expirou nunca foi pago, entao nao ha historico nem cashback novo para desfazer.
        verify(devolucao).devolver(p, StatusPedido.RECEBIDO, false);
    }

    @Test
    void pixJaPago_naoDevolveNada() {
        PedidoRepository repo = mock(PedidoRepository.class);
        when(repo.avancarSeAindaEsta(any(), any(), any(), any(), any())).thenReturn(1);
        Pedido p = pedido(StatusPedido.RECEBIDO);
        p.aguardandoPagamento = true;
        p.pagoEm = OffsetDateTime.now().minusMinutes(1); // pagou entre a consulta e agora
        when(repo.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));

        new CobradorDePixService(repo, mock(FidelidadeService.class), mock(LojaRepository.class),
                mock(IntegracaoCanalRepository.class), mock(PixService.class), devolucao, GerenciadorDeTransacaoFalso.novo(), 30)
                .expirarAbandonados();

        verify(devolucao, never()).devolver(any(), any(), anyBoolean());
    }

    @Test
    void cancelamentoPeloMarketplace_devolveComOStatusAnterior() {
        PedidoRepository repo = mock(PedidoRepository.class);
        when(repo.avancarSeAindaEsta(any(), any(), any(), any(), any())).thenReturn(1);
        Pedido p = pedido(StatusPedido.CONFIRMADO);
        p.canalExterno = "IFOOD";
        p.idExterno = "ped-ext-1";
        when(repo.findFirstByLojaIdAndCanalExternoAndIdExterno(18L, "IFOOD", "ped-ext-1"))
                .thenReturn(Optional.of(p));
        when(repo.save(any(Pedido.class))).thenAnswer(i -> i.getArgument(0));

        servicoDePedido(repo).cancelarPorMarketplace(18L, "IFOOD", "ped-ext-1", "cliente desistiu");

        // true: pedido de marketplace nunca espera PIX; a venda ja estava contabilizada.
        verify(devolucao).devolver(p, StatusPedido.CONFIRMADO, true);
    }

    @Test
    void cancelamentoNoPainel_devolveComOStatusAnterior() {
        PedidoRepository repo = mock(PedidoRepository.class);
        when(repo.avancarSeAindaEsta(any(), any(), any(), any(), any())).thenReturn(1);
        Pedido p = pedido(StatusPedido.EM_PREPARO);
        when(repo.findByIdAndLojaId(1L, 1L)).thenReturn(Optional.of(p));
        when(repo.save(any(Pedido.class))).thenAnswer(i -> i.getArgument(0));

        servicoDePedido(repo).alterarStatus(1L, StatusPedido.CANCELADO, "faltou ingrediente");

        // EM_PREPARO: a devolucao e chamada, e ela decide que o insumo nao volta.
        verify(devolucao).devolver(p, StatusPedido.EM_PREPARO, true);
    }

    @Test
    void mudancaDeStatusQueNaoEhCancelamento_naoDevolveNada() {
        PedidoRepository repo = mock(PedidoRepository.class);
        when(repo.avancarSeAindaEsta(any(), any(), any(), any(), any())).thenReturn(1);
        Pedido p = pedido(StatusPedido.RECEBIDO);
        when(repo.findByIdAndLojaId(1L, 1L)).thenReturn(Optional.of(p));
        when(repo.save(any(Pedido.class))).thenAnswer(i -> i.getArgument(0));

        servicoDePedido(repo).alterarStatus(1L, StatusPedido.EM_PREPARO, null);

        verify(devolucao, never()).devolver(any(), any(), anyBoolean());
    }

    @Test
    void cancelamentoNoPainelDePedidoEsperandoPix_dizQueAVendaNaoFoiContabilizada() {
        PedidoRepository repo = mock(PedidoRepository.class);
        when(repo.avancarSeAindaEsta(any(), any(), any(), any(), any())).thenReturn(1);
        Pedido p = pedido(StatusPedido.RECEBIDO);
        p.aguardandoPagamento = true; // PIX gerado, dinheiro nunca entrou
        when(repo.findByIdAndLojaId(1L, 1L)).thenReturn(Optional.of(p));
        when(repo.save(any(Pedido.class))).thenAnswer(i -> i.getArgument(0));

        servicoDePedido(repo).alterarStatus(1L, StatusPedido.CANCELADO, "cliente desistiu");

        // Aqui so houve o DEBITO do resgate. Dizer "true" faria o estorno tirar do cliente um cashback
        // que esta venda nunca creditou — saldo que ele ganhou em outro pedido.
        verify(devolucao).devolver(p, StatusPedido.RECEBIDO, false);
    }

    /**
     * O pedido que foi CANCELADO e voltou a valer pelo PIX pago tarde.
     *
     * <p>Na reabertura o webhook so RE-CONSOME o resgate; ele nao chama {@code registrar}. Entao esse
     * pedido nunca gerou cashback novo, nem somou no historico do cliente. Mas
     * {@code aguardandoPagamento} ja esta limpo e {@code pagoEm} preenchido, entao
     * {@code !pagamentoPendente()} dizia "contabilizada" -- e o segundo cancelamento tirava do
     * cliente um cashback que ele nunca ganhou, mais uma compra que nunca entrou. O saldo tirado
     * vinha de OUTRO pedido (pente fino de 29/10/2026).</p>
     */
    @Test
    void cancelarDEnovoOpedidoREABERTOnaoTIRAcashbackQUEnuncaFOIdado() {
        PedidoRepository repo = mock(PedidoRepository.class);
        LogStatusRepository logs = mock(LogStatusRepository.class);
        when(repo.avancarSeAindaEsta(any(), any(), any(), any(), any())).thenReturn(1);
        Pedido p = pedido(StatusPedido.RECEBIDO);
        p.aguardandoPagamento = false;                       // o webhook limpou
        p.pagoEm = OffsetDateTime.now().minusMinutes(5);     // o dinheiro entrou
        when(repo.findByIdAndLojaId(1L, 1L)).thenReturn(Optional.of(p));
        when(repo.save(any(Pedido.class))).thenAnswer(i -> i.getArgument(0));
        when(logs.findByLojaIdAndPedidoIdOrderByDataHoraAsc(18L, 1L))
                .thenReturn(List.of(log("RECEBIDO", "CANCELADO")));

        servicoDePedido(repo, logs).alterarStatus(1L, StatusPedido.CANCELADO, "nao vou conseguir atender");

        verify(devolucao).devolver(p, StatusPedido.RECEBIDO, false);
    }

    @Test
    void oPEDIDOpagoQUEnuncaFOIcanceladoCONTINUAcontabilizado() {
        // A outra ponta: sem historico de cancelamento, a venda ACONTECEU e gerou cashback. Dizer
        // "false" aqui deixaria no cliente um saldo que a venda cancelada nao devia ter deixado.
        PedidoRepository repo = mock(PedidoRepository.class);
        LogStatusRepository logs = mock(LogStatusRepository.class);
        when(repo.avancarSeAindaEsta(any(), any(), any(), any(), any())).thenReturn(1);
        Pedido p = pedido(StatusPedido.RECEBIDO);
        p.pagoEm = OffsetDateTime.now().minusMinutes(5);
        when(repo.findByIdAndLojaId(1L, 1L)).thenReturn(Optional.of(p));
        when(repo.save(any(Pedido.class))).thenAnswer(i -> i.getArgument(0));
        when(logs.findByLojaIdAndPedidoIdOrderByDataHoraAsc(18L, 1L))
                .thenReturn(List.of(log(null, "RECEBIDO"), log("RECEBIDO", "EM_PREPARO")));

        servicoDePedido(repo, logs).alterarStatus(1L, StatusPedido.CANCELADO, "faltou ingrediente");

        verify(devolucao).devolver(p, StatusPedido.RECEBIDO, true);
    }

    private static br.com.bora.entity.LogStatus log(String de, String para) {
        br.com.bora.entity.LogStatus l = new br.com.bora.entity.LogStatus();
        l.setLojaId(18L);
        l.setPedidoId(1L);
        l.setStatusAnterior(de);
        l.setStatusNovo(para);
        return l;
    }

    private PedidoService servicoDePedido(PedidoRepository repo) {
        return servicoDePedido(repo, mock(LogStatusRepository.class));
    }

    private PedidoService servicoDePedido(PedidoRepository repo, LogStatusRepository logs) {
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(1L);
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, 1L, "ADMINISTRADOR_LOJA", "admin@bora.app"));
        PedidoService service = new PedidoService(repo, mock(PedidoItemRepository.class),
                mock(ProdutoRepository.class), mock(ClienteRepository.class), logs,
                mock(PlanoService.class), mock(IntegracaoService.class), mock(TaxaEntregaRepository.class),
                mock(InsumoService.class), mock(FidelidadeService.class), mock(ComplementoService.class),
                ctx, devolucao, GerenciadorDeTransacaoFalso.novo());
        ReflectionTestUtils.setField(service, "notifCliente", mock(NotificacaoClienteService.class));
        return service;
    }
}
