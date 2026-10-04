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
import static org.mockito.Mockito.*;

/**
 * Os quatro caminhos de cancelamento chamam a devolucao de estoque.
 *
 * <p>A regra em si vive no {@link DevolucaoDeEstoqueService} e e testada em
 * {@code EstoqueVoltaNoCancelamentoTest}. O risco que ESTE teste cobre e outro: esquecer de ligar a
 * devolucao em um dos caminhos. Foi exatamente o que aconteceu — o cardapio publico passou a baixar
 * estoque e nenhum dos cancelamentos desfazia, em nenhum lugar.</p>
 *
 * <p>Cada caminho tem que passar o status que o pedido tinha ANTES de virar CANCELADO: e ele que diz
 * se o ingrediente ainda existe. Passar o status ja cancelado devolveria tudo sempre.</p>
 */
class TodoCancelamentoDevolveEstoqueTest {

    private final DevolucaoDeEstoqueService devolucao = mock(DevolucaoDeEstoqueService.class);

    private Pedido pedido(StatusPedido status) {
        Pedido p = new Pedido();
        p.id = 1L; p.lojaId = 18L; p.status = status;
        p.criadoEm = OffsetDateTime.now().minusHours(2);
        return p;
    }

    @Test
    void pixExpirado_devolveComOStatusAnterior() {
        PedidoRepository repo = mock(PedidoRepository.class);
        Pedido p = pedido(StatusPedido.RECEBIDO);
        p.aguardandoPagamento = true;
        when(repo.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));

        new CobradorDePixService(repo, mock(FidelidadeService.class), mock(LojaRepository.class),
                mock(IntegracaoCanalRepository.class), mock(PixService.class), devolucao, 30)
                .expirarAbandonados();

        verify(devolucao).devolver(p, StatusPedido.RECEBIDO);
    }

    @Test
    void pixJaPago_naoDevolveNada() {
        PedidoRepository repo = mock(PedidoRepository.class);
        Pedido p = pedido(StatusPedido.RECEBIDO);
        p.aguardandoPagamento = true;
        p.pagoEm = OffsetDateTime.now().minusMinutes(1); // pagou entre a consulta e agora
        when(repo.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));

        new CobradorDePixService(repo, mock(FidelidadeService.class), mock(LojaRepository.class),
                mock(IntegracaoCanalRepository.class), mock(PixService.class), devolucao, 30)
                .expirarAbandonados();

        verify(devolucao, never()).devolver(any(), any());
    }

    @Test
    void cancelamentoPeloMarketplace_devolveComOStatusAnterior() {
        PedidoRepository repo = mock(PedidoRepository.class);
        Pedido p = pedido(StatusPedido.CONFIRMADO);
        p.canalExterno = "IFOOD";
        p.idExterno = "ped-ext-1";
        when(repo.findFirstByLojaIdAndCanalExternoAndIdExterno(18L, "IFOOD", "ped-ext-1"))
                .thenReturn(Optional.of(p));
        when(repo.save(any(Pedido.class))).thenAnswer(i -> i.getArgument(0));

        servicoDePedido(repo).cancelarPorMarketplace(18L, "IFOOD", "ped-ext-1", "cliente desistiu");

        verify(devolucao).devolver(p, StatusPedido.CONFIRMADO);
    }

    @Test
    void cancelamentoNoPainel_devolveComOStatusAnterior() {
        PedidoRepository repo = mock(PedidoRepository.class);
        Pedido p = pedido(StatusPedido.EM_PREPARO);
        when(repo.findByIdAndLojaId(1L, 1L)).thenReturn(Optional.of(p));
        when(repo.save(any(Pedido.class))).thenAnswer(i -> i.getArgument(0));

        servicoDePedido(repo).alterarStatus(1L, StatusPedido.CANCELADO, "faltou ingrediente");

        // EM_PREPARO: a devolucao e chamada, e ela decide que o insumo nao volta.
        verify(devolucao).devolver(p, StatusPedido.EM_PREPARO);
    }

    @Test
    void mudancaDeStatusQueNaoEhCancelamento_naoDevolveNada() {
        PedidoRepository repo = mock(PedidoRepository.class);
        Pedido p = pedido(StatusPedido.RECEBIDO);
        when(repo.findByIdAndLojaId(1L, 1L)).thenReturn(Optional.of(p));
        when(repo.save(any(Pedido.class))).thenAnswer(i -> i.getArgument(0));

        servicoDePedido(repo).alterarStatus(1L, StatusPedido.EM_PREPARO, null);

        verify(devolucao, never()).devolver(any(), any());
    }

    private PedidoService servicoDePedido(PedidoRepository repo) {
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(1L);
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, 1L, "ADMINISTRADOR_LOJA", "admin@bora.app"));
        PedidoService service = new PedidoService(repo, mock(PedidoItemRepository.class),
                mock(ProdutoRepository.class), mock(ClienteRepository.class), mock(LogStatusRepository.class),
                mock(PlanoService.class), mock(IntegracaoService.class), mock(TaxaEntregaRepository.class),
                mock(InsumoService.class), mock(FidelidadeService.class), mock(ComplementoService.class),
                ctx, devolucao);
        ReflectionTestUtils.setField(service, "notifCliente", mock(NotificacaoClienteService.class));
        return service;
    }
}
