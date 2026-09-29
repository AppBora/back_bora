package br.com.bora.service;

import br.com.bora.dto.ItemPedidoRequest;
import br.com.bora.dto.NovoPedidoRequest;
import br.com.bora.entity.Cliente;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.Produto;
import br.com.bora.entity.TaxaEntrega;
import br.com.bora.repository.*;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A conta do pedido lançado no balcão: item + complemento pago, vezes a quantidade, mais a taxa do
 * bairro, menos o cashback resgatado. É a lógica de dinheiro mais usada do sistema e, até 29/09/2026,
 * não tinha teste nenhum — o QA apontou como o buraco número 1.
 *
 * <p>O que estes testes trancam: o total é sempre calculado NO SERVIDOR (nunca aceito do corpo da
 * requisição), o complemento pago entra no preço, a taxa do bairro é somada, o cashback desconta e
 * produto de outra loja não entra no pedido.</p>
 */
class PedidoServiceContaDoBalcaoTest {

    private PedidoRepository repo;
    private PedidoItemRepository itemRepo;
    private ProdutoRepository produtos;
    private ClienteRepository clientes;
    private TaxaEntregaRepository taxas;
    private ComplementoService complementos;
    private FidelidadeService fidelidade;
    private InsumoService insumos;
    private AuthContext ctx;
    private PedidoService service;

    @BeforeEach
    void montar() {
        repo = mock(PedidoRepository.class);
        itemRepo = mock(PedidoItemRepository.class);
        produtos = mock(ProdutoRepository.class);
        clientes = mock(ClienteRepository.class);
        taxas = mock(TaxaEntregaRepository.class);
        complementos = mock(ComplementoService.class);
        fidelidade = mock(FidelidadeService.class);
        insumos = mock(InsumoService.class);
        ctx = mock(AuthContext.class);

        service = new PedidoService(repo, itemRepo, produtos, clientes, mock(LogStatusRepository.class),
                mock(PlanoService.class), mock(IntegracaoService.class), taxas, insumos, fidelidade,
                complementos, ctx);
        ReflectionTestUtils.setField(service, "notifCliente", mock(NotificacaoClienteService.class));

        when(ctx.lojaId()).thenReturn(1L);
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, 1L, "ADMINISTRADOR_LOJA", "admin@bora.app"));
        when(repo.save(any(Pedido.class))).thenAnswer(inv -> {
            Pedido p = inv.getArgument(0);
            p.id = 500L;
            return p;
        });
        // sem complemento escolhido, sem acréscimo; sem ficha técnica, sem custo calculado
        when(complementos.aplicar(anyLong(), any(Produto.class), any()))
                .thenReturn(new ComplementoService.Escolha(BigDecimal.ZERO, List.of()));
        when(insumos.consumirFicha(anyLong(), anyLong(), anyInt())).thenReturn(null);
        when(fidelidade.resgatePossivel(anyLong(), any(), any(BigDecimal.class))).thenReturn(BigDecimal.ZERO);
    }

    private Produto produto(long id, String nome, String preco) {
        Produto p = new Produto();
        p.id = id;
        p.lojaId = 1L;
        p.nome = nome;
        p.preco = new BigDecimal(preco);
        when(produtos.findByIdAndLojaId(id, 1L)).thenReturn(Optional.of(p));
        return p;
    }

    private Cliente clienteNoBairro(long id, String bairro) {
        Cliente c = new Cliente();
        c.id = id;
        c.lojaId = 1L;
        c.nome = "Cliente";
        c.bairro = bairro;
        when(clientes.findByIdAndLojaId(id, 1L)).thenReturn(Optional.of(c));
        return c;
    }

    private Pedido salvo() {
        ArgumentCaptor<Pedido> captor = ArgumentCaptor.forClass(Pedido.class);
        verify(repo).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void somaItemComplementoQuantidadeETaxaDoBairro() {
        produto(10L, "Pizza Calabresa", "47.00");
        clienteNoBairro(3L, "Setor Bueno");
        TaxaEntrega t = new TaxaEntrega();
        t.taxa = new BigDecimal("7.00");
        when(taxas.findFirstByLojaIdAndBairroIgnoreCaseAndAtivoTrue(1L, "Setor Bueno")).thenReturn(Optional.of(t));
        // borda recheada: +12 no preço unitário
        when(complementos.aplicar(eq(1L), any(Produto.class), any()))
                .thenReturn(new ComplementoService.Escolha(new BigDecimal("12.00"), List.of("Borda Catupiry")));

        service.criar(new NovoPedidoRequest(3L, "101", "Dinheiro", "Delivery", null, false,
                List.of(new ItemPedidoRequest(10L, 2, List.of(1L))), null));

        // (47 + 12) x 2 = 118, mais 7 de taxa
        assertEquals(0, new BigDecimal("125.00").compareTo(salvo().valorTotal), "total do pedido");
        assertEquals(0, new BigDecimal("7.00").compareTo(salvo().taxaEntrega), "taxa do bairro");

        ArgumentCaptor<List<PedidoItem>> itens = ArgumentCaptor.forClass(List.class);
        verify(itemRepo).saveAll(itens.capture());
        PedidoItem it = itens.getValue().get(0);
        assertEquals(0, new BigDecimal("59.00").compareTo(it.getPrecoUnitario()), "unitario com o complemento");
        assertEquals(0, new BigDecimal("118.00").compareTo(it.getSubtotal()));
        assertTrue(it.getDescricao().contains("Borda Catupiry"), it.getDescricao());
        assertEquals(500L, it.getPedidoId(), "o item tem que ficar amarrado ao pedido salvo");
    }

    @Test
    void cashbackResgatadoDescontaDoTotal() {
        produto(10L, "Acai 500ml", "30.00");
        clienteNoBairro(3L, "Centro");
        when(taxas.findFirstByLojaIdAndBairroIgnoreCaseAndAtivoTrue(anyLong(), anyString())).thenReturn(Optional.empty());
        when(fidelidade.resgatePossivel(eq(1L), eq(3L), any(BigDecimal.class))).thenReturn(new BigDecimal("6.57"));

        service.criar(new NovoPedidoRequest(3L, "102", "PIX", "Delivery", null, true,
                List.of(new ItemPedidoRequest(10L, 1, null)), null));

        assertEquals(0, new BigDecimal("23.43").compareTo(salvo().valorTotal), "30 - 6,57 de cashback");
    }

    @Test
    void balcaoNaoCobraTaxaDeEntrega() {
        produto(10L, "Acai 500ml", "30.00");
        clienteNoBairro(3L, "Setor Bueno");
        TaxaEntrega t = new TaxaEntrega();
        t.taxa = new BigDecimal("7.00");
        when(taxas.findFirstByLojaIdAndBairroIgnoreCaseAndAtivoTrue(1L, "Setor Bueno")).thenReturn(Optional.of(t));

        service.criar(new NovoPedidoRequest(3L, "103", "Dinheiro", "Balcao", null, false,
                List.of(new ItemPedidoRequest(10L, 1, null)), null));

        assertEquals(0, BigDecimal.ZERO.compareTo(salvo().taxaEntrega), "no balcao nao ha frete");
        assertEquals(0, new BigDecimal("30.00").compareTo(salvo().valorTotal));
    }

    @Test
    void freteCombinadoPeloBalconistaVenceATabela() {
        produto(10L, "Acai 500ml", "30.00");
        clienteNoBairro(3L, "Setor Bueno");
        TaxaEntrega t = new TaxaEntrega();
        t.taxa = new BigDecimal("7.00");
        when(taxas.findFirstByLojaIdAndBairroIgnoreCaseAndAtivoTrue(1L, "Setor Bueno")).thenReturn(Optional.of(t));

        service.criar(new NovoPedidoRequest(3L, "104", "PIX", "Delivery", null, false,
                List.of(new ItemPedidoRequest(10L, 1, null)), BigDecimal.ZERO)); // cortesia

        assertEquals(0, BigDecimal.ZERO.compareTo(salvo().taxaEntrega), "frete de cortesia informado na mao");
        assertEquals(0, new BigDecimal("30.00").compareTo(salvo().valorTotal));
    }

    @Test
    void produtoDeOutraLojaNaoEntraNoPedido() {
        when(produtos.findByIdAndLojaId(77L, 1L)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.criar(
                new NovoPedidoRequest(null, "105", "PIX", "Delivery", null, false,
                        List.of(new ItemPedidoRequest(77L, 1, null)), null)));

        verify(repo, never()).save(any(Pedido.class));
    }

    @Test
    void pedidoSemItemEhRecusado() {
        assertThrows(ResponseStatusException.class, () -> service.criar(
                new NovoPedidoRequest(null, "106", "PIX", "Delivery", null, false, List.of(), null)));
        verify(repo, never()).save(any(Pedido.class));
    }
}
