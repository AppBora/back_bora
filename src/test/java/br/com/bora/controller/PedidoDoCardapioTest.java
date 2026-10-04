package br.com.bora.controller;

import br.com.bora.entity.*;
import br.com.bora.repository.*;
import br.com.bora.service.*;
import br.com.bora.security.RegraDeAcesso;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O pedido do cardápio público — o caminho que não tinha teste nenhum.
 *
 * <p>É a rota mais exposta do sistema: aberta na internet, sem login, e nela moram cupom, frete por
 * bairro, cashback, estoque e PIX. Tudo que mexe em dinheiro do lojista passa por aqui, e até agora
 * cada conserto dependia de alguém reparar no efeito colateral. Estes testes trancam as regras que o
 * cliente final consegue tentar dobrar.</p>
 */
class PedidoDoCardapioTest {

    private LojaRepository lojas;
    private ProdutoRepository produtos;
    private PedidoRepository pedidos;
    private PedidoItemRepository itens;
    private FidelidadeService fidelidade;
    private TaxaEntregaRepository taxas;
    private InsumoService insumos;
    private CupomRepository cupons;
    private PublicController controller;
    private Produto acai;

    @BeforeEach
    void montar() {
        lojas = mock(LojaRepository.class);
        produtos = mock(ProdutoRepository.class);
        pedidos = mock(PedidoRepository.class);
        itens = mock(PedidoItemRepository.class);
        fidelidade = mock(FidelidadeService.class);
        taxas = mock(TaxaEntregaRepository.class);
        insumos = mock(InsumoService.class);
        cupons = mock(CupomRepository.class);
        ComplementoService complementos = mock(ComplementoService.class);
        when(complementos.aplicar(anyLong(), any(), any()))
                .thenReturn(new ComplementoService.Escolha(BigDecimal.ZERO, List.of()));
        OperacaoService operacao = mock(OperacaoService.class);
        when(operacao.abertaAgora(anyLong())).thenReturn(true);

        controller = new PublicController(lojas, produtos, pedidos, itens,
                mock(IntegracaoCanalRepository.class), mock(PixService.class),
                mock(ComplementoGrupoRepository.class), mock(ComplementoItemRepository.class),
                complementos, cupons, fidelidade, operacao, taxas, insumos,
                new RegraDeAcesso(false), mock(ConfiguracaoLojaRepository.class), false);

        Loja l = new Loja();
        l.id = 1L;
        l.ativo = true;
        when(lojas.findById(1L)).thenReturn(Optional.of(l));

        acai = new Produto();
        acai.id = 10L;
        acai.lojaId = 1L;
        acai.nome = "Açaí 500ml";
        acai.preco = new BigDecimal("20.00");
        acai.ativo = true;
        when(produtos.findById(10L)).thenReturn(Optional.of(acai));
        when(pedidos.save(any(Pedido.class))).thenAnswer(i -> {
            Pedido p = i.getArgument(0);
            if (p.id == null) p.id = 99L;
            return p;
        });
        when(fidelidade.resgatePossivel(anyLong(), any(), any())).thenReturn(BigDecimal.ZERO);
    }

    private Map<String, Object> corpo(Object... extras) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itens", List.of(Map.of("produtoId", 10, "quantidade", 2)));
        m.put("clienteNome", "Maria");
        m.put("telefone", "15999990001");
        m.put("endereco", "Rua A, 1");
        for (int i = 0; i < extras.length; i += 2) m.put((String) extras[i], extras[i + 1]);
        return m;
    }

    private Pedido salvo() {
        var c = org.mockito.ArgumentCaptor.forClass(Pedido.class);
        verify(pedidos, atLeastOnce()).save(c.capture());
        return c.getValue();
    }

    @Test
    void oTotalEhCalculadoNoServidor_naoAceitaPrecoDoCliente() {
        controller.pedirOnline(1L, corpo("valorTotal", "0.01", "preco", "0.01"));

        assertEquals(0, new BigDecimal("40.00").compareTo(salvo().valorTotal),
                "2 x R$ 20 = R$ 40; preco mandado pelo cliente nao pode valer");
    }

    @Test
    void produtoDeOutraLoja_naoEntraNoPedido() {
        acai.lojaId = 2L; // produto de OUTRA loja

        // O pedido e gravado antes dos itens, entao quem garante que nada sobra e a transacao do
        // metodo. Aqui o que importa e que o pedido NAO se completa com produto de outra loja.
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.pedirOnline(1L, corpo()));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(itens, never()).save(any(PedidoItem.class));
    }

    @Test
    void lojaDesativada_naoRecebePedido() {
        Loja fechada = new Loja();
        fechada.id = 1L;
        fechada.ativo = false;
        when(lojas.findById(1L)).thenReturn(Optional.of(fechada));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.pedirOnline(1L, corpo()));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void semItens_naoCriaPedidoVazio() {
        Map<String, Object> vazio = corpo();
        vazio.put("itens", List.of());

        assertThrows(ResponseStatusException.class, () -> controller.pedirOnline(1L, vazio));
        verify(pedidos, never()).save(any(Pedido.class));
    }

    @Test
    void semNome_naoPassa() {
        Map<String, Object> m = corpo();
        m.remove("clienteNome");

        assertThrows(ResponseStatusException.class, () -> controller.pedirOnline(1L, m));
    }

    @Test
    void freteDoBairroEntraNoTotal_eNaoVemDoCliente() {
        TaxaEntrega centro = new TaxaEntrega();
        centro.lojaId = 1L;
        centro.bairro = "Centro";
        centro.taxa = new BigDecimal("7.50");
        centro.ativo = true;
        when(taxas.findByLojaIdAndAtivoTrueOrderByBairroAsc(1L)).thenReturn(List.of(centro));
        when(taxas.findFirstByLojaIdAndBairroIgnoreCaseAndAtivoTrue(1L, "Centro")).thenReturn(Optional.of(centro));

        controller.pedirOnline(1L, corpo("bairro", "Centro", "taxaEntrega", "0.00"));

        Pedido p = salvo();
        assertEquals(0, new BigDecimal("7.50").compareTo(p.taxaEntrega), "o frete e o da tabela da loja");
        assertEquals(0, new BigDecimal("47.50").compareTo(p.valorTotal));
    }

    @Test
    void retirada_naoCobraFrete() {
        TaxaEntrega centro = new TaxaEntrega();
        centro.lojaId = 1L;
        centro.bairro = "Centro";
        centro.taxa = new BigDecimal("7.50");
        centro.ativo = true;
        when(taxas.findByLojaIdAndAtivoTrueOrderByBairroAsc(1L)).thenReturn(List.of(centro));

        controller.pedirOnline(1L, corpo("tipoEntrega", "RETIRADA", "bairro", "Centro"));

        assertEquals(0, BigDecimal.ZERO.compareTo(salvo().taxaEntrega));
    }

    @Test
    void cupomInventado_naoDaDesconto() {
        assertThrows(ResponseStatusException.class,
                () -> controller.pedirOnline(1L, corpo("cupom", "BORA90")),
                "cupom que nao existe nao pode virar desconto");
    }

    @Test
    void aFichaTecnicaEhConsumidaNaVenda() {
        controller.pedirOnline(1L, corpo());

        verify(insumos, atLeastOnce()).consumirFicha(eq(1L), eq(10L), anyInt());
    }

    @Test
    void naEntrega_oCashbackEhRegistradoNaHora() {
        when(fidelidade.identificarPeloTelefone(anyLong(), any(), any(), any())).thenReturn(42L);

        controller.pedirOnline(1L, corpo());

        verify(fidelidade).registrar(eq(1L), eq(42L), any(), any());
    }
}
