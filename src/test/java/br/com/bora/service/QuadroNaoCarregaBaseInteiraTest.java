package br.com.bora.service;

import br.com.bora.entity.Cliente;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.*;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O quadro de pedidos não pode carregar a base inteira de clientes.
 *
 * <p>Ele puxava TODOS os clientes da loja a cada atualização, só para achar o nome e o telefone de
 * alguns cards. O painel recarrega a cada 6 a 8 segundos, em várias telas abertas ao mesmo tempo:
 * numa loja com milhares de clientes isso é dezenas de milhares de registros por minuto para mostrar
 * uns 30 pedidos. E piora com o sucesso da loja, que é quando menos se pode travar.</p>
 */
class QuadroNaoCarregaBaseInteiraTest {

    private PedidoRepository repo;
    private ClienteRepository clientes;
    private PedidoService service;

    @BeforeEach
    void montar() {
        repo = mock(PedidoRepository.class);
        clientes = mock(ClienteRepository.class);
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(1L);
        service = new PedidoService(repo, mock(PedidoItemRepository.class), mock(ProdutoRepository.class),
                clientes, mock(LogStatusRepository.class), mock(PlanoService.class),
                mock(IntegracaoService.class), mock(TaxaEntregaRepository.class), mock(InsumoService.class),
                mock(FidelidadeService.class), mock(ComplementoService.class), ctx, mock(DevolucaoDeEstoqueService.class));
    }

    private Pedido pedido(Long id, Long clienteId) {
        Pedido p = new Pedido();
        p.id = id;
        p.lojaId = 1L;
        p.clienteId = clienteId;
        p.status = StatusPedido.RECEBIDO;
        p.criadoEm = OffsetDateTime.now();
        return p;
    }

    private void comPedidos(List<Pedido> pedidos) {
        when(repo.findByLojaIdAndCriadoEmGreaterThanEqualAndCriadoEmLessThanOrderByCriadoEmDesc(
                eq(1L), any(), any())).thenReturn(pedidos);
        when(repo.findByLojaIdAndStatusNotInOrderByCriadoEmDesc(eq(1L), any())).thenReturn(List.of());
    }

    @Test
    void buscaSoOsClientesQueAparecemNosCards() {
        comPedidos(List.of(pedido(1L, 70L), pedido(2L, 71L), pedido(3L, 70L)));
        Cliente c = new Cliente();
        c.id = 70L;
        c.nome = "Maria";
        when(clientes.findByLojaIdAndIdIn(eq(1L), any())).thenReturn(List.of(c));

        service.board(null, null);

        verify(clientes, never()).findByLojaIdOrderByNomeAsc(anyLong());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<Long>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(clientes).findByLojaIdAndIdIn(eq(1L), ids.capture());
        assertEquals(java.util.Set.of(70L, 71L), new java.util.HashSet<>(ids.getValue()),
                "so os clientes dos pedidos exibidos, sem repetir");
    }

    @Test
    void semPedidoComCliente_naoVaiAoBancoDeClientes() {
        comPedidos(List.of(pedido(1L, null), pedido(2L, null)));

        service.board(null, null);

        verify(clientes, never()).findByLojaIdAndIdIn(anyLong(), any());
        verify(clientes, never()).findByLojaIdOrderByNomeAsc(anyLong());
    }

    @Test
    void quadroVazio_naoConsultaCliente() {
        comPedidos(List.of());

        assertTrue(service.board(null, null).isEmpty());
        verify(clientes, never()).findByLojaIdAndIdIn(anyLong(), any());
    }
}
