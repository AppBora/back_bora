package br.com.bora.service;

import br.com.bora.entity.*;
import br.com.bora.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pedido cancelado devolve o que consumiu — e so o que ainda existe.
 *
 * <p>Nascer um pedido baixa estoque por um de dois caminhos: produto COM ficha tecnica consome os
 * insumos; produto SEM ficha baixa o estoque dele mesmo. Nenhum dos quatro caminhos de cancelamento
 * desfazia isso. Com o PIX expirando sozinho em 30 minutos, todo checkout abandonado comia estoque de
 * uma venda que nunca existiu — e o lojista descobria no inventario, sem saber de onde veio o furo.</p>
 *
 * <p>A devolucao nao e "volta tudo sempre", porque a realidade nao e: ingrediente que a cozinha ja
 * usou acabou mesmo, e refrigerante que ja saiu na mochila do motoboy nao esta na prateleira.</p>
 */
class EstoqueVoltaNoCancelamentoTest {

    private PedidoItemRepository itens;
    private ProdutoRepository produtos;
    private InsumoRepository insumosRepo;
    private ProdutoInsumoRepository fichas;
    private DevolucaoDeEstoqueService devolucao;

    private Produto refrigerante;   // sem ficha: controla o proprio estoque
    private Produto acai;           // com ficha: consome insumos
    private Insumo polpa;

    @BeforeEach
    void montar() {
        itens = mock(PedidoItemRepository.class);
        produtos = mock(ProdutoRepository.class);
        insumosRepo = mock(InsumoRepository.class);
        fichas = mock(ProdutoInsumoRepository.class);

        refrigerante = new Produto();
        refrigerante.id = 10L; refrigerante.lojaId = 1L; refrigerante.nome = "Guarana lata";
        refrigerante.estoque = 20;

        acai = new Produto();
        acai.id = 11L; acai.lojaId = 1L; acai.nome = "Acai 500ml";

        polpa = new Insumo();
        polpa.id = 99L; polpa.lojaId = 1L; polpa.nome = "Polpa de acai";
        polpa.estoque = new BigDecimal("5.000"); // 5 kg
        polpa.custo = new BigDecimal("20.00");

        ProdutoInsumo linhaDaFicha = new ProdutoInsumo();
        linhaDaFicha.insumoId = 99L; linhaDaFicha.produtoId = 11L; linhaDaFicha.lojaId = 1L;
        linhaDaFicha.quantidade = new BigDecimal("0.300"); // 300 g por acai

        when(produtos.findByIdAndLojaId(10L, 1L)).thenReturn(Optional.of(refrigerante));
        when(produtos.findByIdAndLojaId(11L, 1L)).thenReturn(Optional.of(acai));
        when(produtos.save(any(Produto.class))).thenAnswer(i -> i.getArgument(0));
        when(fichas.findByLojaIdAndProdutoId(1L, 11L)).thenReturn(List.of(linhaDaFicha));
        when(fichas.findByLojaIdAndProdutoId(1L, 10L)).thenReturn(List.of());
        when(insumosRepo.findByIdAndLojaId(99L, 1L)).thenReturn(Optional.of(polpa));
        when(insumosRepo.save(any(Insumo.class))).thenAnswer(i -> i.getArgument(0));

        InsumoService insumoService = new InsumoService(insumosRepo, fichas, produtos,
                mock(br.com.bora.security.AuthContext.class));
        devolucao = new DevolucaoDeEstoqueService(itens, produtos, insumoService);
    }

    private Pedido pedido() {
        Pedido p = new Pedido();
        p.id = 500L; p.lojaId = 1L;
        return p;
    }

    private PedidoItem item(Long produtoId, int qtd, Boolean porFicha) {
        PedidoItem it = new PedidoItem();
        it.setLojaId(1L); it.setPedidoId(500L); it.setProdutoId(produtoId);
        it.setQuantidade(qtd); it.setConsumiuFicha(porFicha);
        return it;
    }

    private void pedidoTem(PedidoItem... lista) {
        when(itens.findByLojaIdAndPedidoIdOrderById(1L, 500L)).thenReturn(List.of(lista));
    }

    @Test
    void pixAbandonado_devolveOEstoqueDoProduto() {
        pedidoTem(item(10L, 3, false));

        int mexidos = devolucao.devolver(pedido(), StatusPedido.RECEBIDO);

        assertEquals(1, mexidos);
        assertEquals(23, refrigerante.estoque, "3 latas de um PIX que ninguem pagou tem que voltar");
    }

    @Test
    void pixAbandonado_devolveOsInsumosDaFicha() {
        pedidoTem(item(11L, 2, true));

        devolucao.devolver(pedido(), StatusPedido.RECEBIDO);

        // 2 acais x 300 g = 600 g de polpa voltam para os 5 kg
        assertEquals(0, new BigDecimal("5.600").compareTo(polpa.estoque),
                "a polpa de um pedido que nunca foi para a cozinha tem que voltar, deu " + polpa.estoque);
    }

    @Test
    void cozinhaJaFez_naoDevolveIngrediente() {
        pedidoTem(item(11L, 2, true));

        int mexidos = devolucao.devolver(pedido(), StatusPedido.EM_PREPARO);

        assertEquals(0, mexidos);
        assertEquals(0, new BigDecimal("5.000").compareTo(polpa.estoque),
                "a polpa foi batida e o acai foi para o lixo; devolver inflaria o estoque");
    }

    @Test
    void cozinhaJaFez_masORefrigeranteContinuaNaGeladeira() {
        pedidoTem(item(10L, 2, false));

        devolucao.devolver(pedido(), StatusPedido.PRONTO);

        assertEquals(22, refrigerante.estoque,
                "lata nao e preparada, e tirada da prateleira: cancelou antes de sair, volta");
    }

    @Test
    void pedidoQueJaSaiuParaEntrega_naoDevolveNada() {
        pedidoTem(item(10L, 2, false), item(11L, 1, true));

        int mexidos = devolucao.devolver(pedido(), StatusPedido.SAIU_PARA_ENTREGA);

        assertEquals(0, mexidos);
        assertEquals(20, refrigerante.estoque, "a lata esta na mochila do motoboy, nao na geladeira");
        assertEquals(0, new BigDecimal("5.000").compareTo(polpa.estoque));
    }

    @Test
    void aMarcaDoItemManda_naoAFichaDeHoje() {
        // O item baixou o ESTOQUE DO PRODUTO (nao tinha ficha na epoca). Depois o lojista cadastrou
        // uma ficha para o mesmo produto. Devolver pela ficha de hoje criaria polpa do nada e deixaria
        // o estoque do produto furado para sempre.
        when(fichas.findByLojaIdAndProdutoId(1L, 10L)).thenReturn(List.of(fichaNova()));
        pedidoTem(item(10L, 4, false));

        devolucao.devolver(pedido(), StatusPedido.RECEBIDO);

        assertEquals(24, refrigerante.estoque, "tinha que devolver as 4 latas que realmente baixaram");
        assertEquals(0, new BigDecimal("5.000").compareTo(polpa.estoque),
                "nao pode nascer insumo de uma ficha que nao existia quando o pedido foi feito");
    }

    @Test
    void itemAntigoSemMarca_naoDevolveNada() {
        // Item criado antes da V45: consumiuFicha e null. A primeira versao caia na ficha de hoje, e
        // isso INVENTAVA estoque: o cardapio publico so passou a baixar estoque em 01/10/2026, entao
        // item criado ali antes disso tem produtoId preenchido e nunca tirou nada. Devolver somaria
        // unidade que jamais saiu, calado. Entre errar somando e nao mexer, nao mexer e melhor --
        // estoque inventado o lojista nunca descobre de onde veio.
        pedidoTem(item(11L, 1, null));

        int mexidos = devolucao.devolver(pedido(), StatusPedido.CONFIRMADO);

        assertEquals(0, mexidos);
        assertEquals(0, new BigDecimal("5.000").compareTo(polpa.estoque),
                "item sem marca nao pode criar insumo do nada, deu " + polpa.estoque);
    }

    @Test
    void itemAntigoSemMarca_tambemNaoMexeNoEstoqueDoProduto() {
        pedidoTem(item(10L, 3, null));

        devolucao.devolver(pedido(), StatusPedido.RECEBIDO);

        assertEquals(20, refrigerante.estoque,
                "sem saber como baixou, nao devolve: 20 continua 20");
    }

    @Test
    void produtoQueNaoControlaEstoque_naoGanhaSaldoDoNada() {
        pedidoTem(item(11L, 5, false)); // acai nao tem campo estoque preenchido

        int mexidos = devolucao.devolver(pedido(), StatusPedido.RECEBIDO);

        assertEquals(0, mexidos);
        assertNull(acai.estoque, "quem nao controla estoque continua sem numero nenhum");
    }

    private ProdutoInsumo fichaNova() {
        ProdutoInsumo pi = new ProdutoInsumo();
        pi.insumoId = 99L; pi.produtoId = 10L; pi.lojaId = 1L;
        pi.quantidade = new BigDecimal("0.100");
        return pi;
    }
}
