package br.com.bora.service;

import br.com.bora.dto.InboundOrder;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.*;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * As três regras do caminho do pedido de marketplace que já quebraram em produção e que nenhum teste
 * automático cobria até 29/09/2026:
 *
 * <ol>
 *   <li>pedido reenviado pelo marketplace não pode virar dois pedidos (o iFood reenvia o mesmo pedido
 *       no evento de confirmação — em 19/09 isso gerou "confirm" em dobro);</li>
 *   <li>cancelamento é pedido ao marketplace ANTES de gravar aqui: se ele recusar, o pedido continua
 *       ativo nos dois lados (em 19/09 o Bora cancelava sozinho e o pedido seguia vivo no iFood);</li>
 *   <li>quando o marketplace aceita, o cancelamento é gravado com o motivo e vai para o histórico.</li>
 * </ol>
 */
class PedidoServiceMarketplaceTest {

    private PedidoRepository repo;
    private LogStatusRepository logs;
    private IntegracaoService integracoes;
    private NotificacaoClienteService notifCliente;
    private AuthContext ctx;
    private PedidoService service;

    @BeforeEach
    void montar() {
        repo = mock(PedidoRepository.class);
        logs = mock(LogStatusRepository.class);
        integracoes = mock(IntegracaoService.class);
        notifCliente = mock(NotificacaoClienteService.class);
        ctx = mock(AuthContext.class);

        service = new PedidoService(repo, mock(PedidoItemRepository.class), mock(ProdutoRepository.class),
                mock(ClienteRepository.class), logs, mock(PlanoService.class), integracoes,
                mock(TaxaEntregaRepository.class), mock(InsumoService.class), mock(FidelidadeService.class),
                mock(ComplementoService.class), ctx);
        // O aviso ao cliente entra por injeção de campo (Módulo IA), não pelo construtor.
        ReflectionTestUtils.setField(service, "notifCliente", notifCliente);

        when(ctx.lojaId()).thenReturn(1L);
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, 1L, "ADMINISTRADOR_LOJA", "admin@bora.app"));
        when(repo.save(any(Pedido.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Pedido pedidoDoIfood() {
        Pedido p = new Pedido();
        p.id = 99L;
        p.lojaId = 1L;
        p.status = StatusPedido.EM_PREPARO;
        p.canalExterno = "IFOOD";
        p.idExterno = "82e2531d-aaaa-bbbb-cccc-000000000001";
        return p;
    }

    @Test
    void pedidoReenviadoPeloMarketplaceNaoViraDoisPedidos() {
        Pedido jaExiste = pedidoDoIfood();
        when(repo.findFirstByLojaIdAndCanalExternoAndIdExterno(1L, "IFOOD", jaExiste.idExterno))
                .thenReturn(Optional.of(jaExiste));

        InboundOrder in = new InboundOrder(jaExiste.idExterno, "Cliente", "62999990000", "Rua X, 1",
                "Centro", "Pago online", "Pedido iFood #1022", new BigDecimal("27"), List.of(),
                new BigDecimal("5"), "1022");

        Pedido devolvido = service.criarExterno(1L, "IFOOD", "iFood", in);

        assertSame(jaExiste, devolvido, "tem que devolver o pedido que já existe");
        verify(repo, never()).save(any(Pedido.class));
    }

    @Test
    void marketplaceRecusandoOCancelamentoNaoCancelaNoBora() {
        Pedido p = pedidoDoIfood();
        when(repo.findByIdAndLojaId(99L, 1L)).thenReturn(Optional.of(p));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                "O iFood não aceita cancelar este pedido nesta etapa."))
                .when(integracoes).cancelarNoMarketplace(any(Pedido.class), anyString());

        ResponseStatusException erro = assertThrows(ResponseStatusException.class,
                () -> service.alterarStatus(99L, StatusPedido.CANCELADO, "item indisponivel"));

        assertEquals(HttpStatus.CONFLICT, erro.getStatusCode());
        assertEquals(StatusPedido.EM_PREPARO, p.status, "o pedido não pode mudar de status aqui");
        verify(repo, never()).save(any(Pedido.class));
        verify(logs, never()).save(any());
        verifyNoInteractions(notifCliente);
    }

    @Test
    void marketplaceAceitandoOCancelamentoGravaMotivoEHistorico() {
        Pedido p = pedidoDoIfood();
        when(repo.findByIdAndLojaId(99L, 1L)).thenReturn(Optional.of(p));

        Pedido salvo = service.alterarStatus(99L, StatusPedido.CANCELADO, "item indisponivel");

        verify(integracoes).cancelarNoMarketplace(p, "item indisponivel");
        assertEquals(StatusPedido.CANCELADO, salvo.status);
        assertEquals("item indisponivel", salvo.motivoCancelamento);
        assertNotNull(salvo.canceladoEm);
        verify(repo).save(p);
        verify(logs).save(any());
    }

    @Test
    void cancelamentoSemMotivoEhRecusadoAntesDeFalarComOMarketplace() {
        Pedido p = pedidoDoIfood();
        when(repo.findByIdAndLojaId(99L, 1L)).thenReturn(Optional.of(p));

        assertThrows(ResponseStatusException.class,
                () -> service.alterarStatus(99L, StatusPedido.CANCELADO, "   "));

        verifyNoInteractions(integracoes);
        verify(repo, never()).save(any(Pedido.class));
    }
}
