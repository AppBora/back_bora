package br.com.bora.service;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.*;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * O pedido avanca pelo aviso do entregador do marketplace, sem devolver o status para la.
 *
 * <p>Quando a entrega e do marketplace, quem sabe onde o motoboy esta e ele. Mandar o status de volta
 * — que e o que o caminho normal do painel faz — seria contar a mesma novidade para quem acabou de
 * nos contar. Este caminho tambem nao exige usuario logado, porque quem chama e o webhook.</p>
 *
 * <p>O aviso pode chegar fora de ordem ou repetido, entao nao recua status nem reabre pedido
 * encerrado: reabrir um pedido entregue seria pior que ignorar o aviso.</p>
 */
class AvancoPeloEntregadorTest {

    private PedidoRepository repo;
    private LogStatusRepository logs;
    private IntegracaoService integracoes;
    private NotificacaoClienteService notifCliente;
    private PedidoService service;
    private Pedido pedido;

    @BeforeEach
    void montar() {
        repo = mock(PedidoRepository.class);
        logs = mock(LogStatusRepository.class);
        integracoes = mock(IntegracaoService.class);
        notifCliente = mock(NotificacaoClienteService.class);

        pedido = new Pedido();
        pedido.id = 10L;
        pedido.lojaId = 18L;
        pedido.canalExterno = "NOVE_NOVE";
        pedido.idExterno = "ped-99";
        pedido.status = StatusPedido.PRONTO;

        when(repo.findFirstByLojaIdAndCanalExternoAndIdExterno(18L, "NOVE_NOVE", "ped-99"))
                .thenReturn(Optional.of(pedido));
        when(repo.save(any(Pedido.class))).thenAnswer(i -> i.getArgument(0));

        service = new PedidoService(repo, mock(PedidoItemRepository.class), mock(ProdutoRepository.class),
                mock(ClienteRepository.class), logs, mock(PlanoService.class), integracoes,
                mock(TaxaEntregaRepository.class), mock(InsumoService.class), mock(FidelidadeService.class),
                mock(ComplementoService.class), mock(AuthContext.class), mock(DevolucaoDeEstoqueService.class),
                GerenciadorDeTransacaoFalso.novo());
        ReflectionTestUtils.setField(service, "notifCliente", notifCliente);
    }

    @Test
    void saiuParaEntrega_mudaOPedidoENaoAvisaOMarketplaceDeVolta() {
        assertTrue(service.avancarPorMarketplace(18L, "NOVE_NOVE", "ped-99", StatusPedido.SAIU_PARA_ENTREGA));

        assertEquals(StatusPedido.SAIU_PARA_ENTREGA, pedido.status);
        verify(integracoes, never()).notificarStatus(any(), any());
        verify(notifCliente).notificarFase(pedido, StatusPedido.SAIU_PARA_ENTREGA);
    }

    @Test
    void entregue_marcaAHoraDaEntrega() {
        service.avancarPorMarketplace(18L, "NOVE_NOVE", "ped-99", StatusPedido.ENTREGUE);

        assertEquals(StatusPedido.ENTREGUE, pedido.status);
        assertNotNull(pedido.entregueEm, "sem a hora, o relatorio de entrega fica sem o fecho");
    }

    @Test
    void avisoRepetido_naoFazNada() {
        pedido.status = StatusPedido.ENTREGUE;

        assertFalse(service.avancarPorMarketplace(18L, "NOVE_NOVE", "ped-99", StatusPedido.ENTREGUE),
                "a 99 reenvia aviso; gravar de novo so sujaria o historico");
        verify(repo, never()).save(any(Pedido.class));
    }

    @Test
    void pedidoJaCancelado_naoReabre() {
        pedido.status = StatusPedido.CANCELADO;

        assertFalse(service.avancarPorMarketplace(18L, "NOVE_NOVE", "ped-99", StatusPedido.ENTREGUE),
                "aviso fora de ordem nao pode ressuscitar pedido cancelado");
        assertEquals(StatusPedido.CANCELADO, pedido.status);
    }

    @Test
    void pedidoQueNaoEhNosso_naoFazNada() {
        assertFalse(service.avancarPorMarketplace(18L, "NOVE_NOVE", "ped-de-outro", StatusPedido.ENTREGUE));
    }

    @Test
    void registraNoHistoricoSemUsuario() {
        service.avancarPorMarketplace(18L, "NOVE_NOVE", "ped-99", StatusPedido.SAIU_PARA_ENTREGA);

        var cap = org.mockito.ArgumentCaptor.forClass(br.com.bora.entity.LogStatus.class);
        verify(logs).save(cap.capture());
        assertEquals("PRONTO", cap.getValue().getStatusAnterior());
        assertEquals("SAIU_PARA_ENTREGA", cap.getValue().getStatusNovo());
        assertNull(cap.getValue().getUsuarioId(), "quem mexeu foi o marketplace, nao alguem do painel");
    }
}
