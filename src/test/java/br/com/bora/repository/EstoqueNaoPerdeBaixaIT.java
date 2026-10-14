package br.com.bora.repository;

import br.com.bora.entity.Insumo;
import br.com.bora.entity.Produto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Duas vendas do mesmo item nao perdem uma baixa de estoque.
 *
 * <p><b>O defeito que isto fecha:</b> a baixa era {@code prod.estoque = prod.estoque - qtd} seguido de
 * {@code save}, em tres lugares (balcao, cardapio e a ficha tecnica do insumo). Duas vendas ao mesmo
 * tempo — o balcao e o cardapio, ou dois pedidos do cardapio no pico do jantar — leem o MESMO estoque
 * antes de qualquer uma gravar, e as duas gravam o mesmo numero. Uma das baixas desaparece e o lojista
 * vende o que nao tem.</p>
 *
 * <p>Isto so pode ser provado contra um banco de verdade: o defeito esta em QUEM faz a conta, Java ou
 * o Postgres. Mock de repositorio devolve o que o teste mandar.</p>
 *
 * <p>Como rodar: ver o javadoc do {@link PreviaDoAcertoBateComOPagamentoIT}.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:55432/bora",
        "spring.datasource.username=postgres",
        "spring.datasource.password=teste",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class EstoqueNaoPerdeBaixaIT {

    private static final Long LOJA = 9003L;

    @Autowired private ProdutoRepository produtos;
    @Autowired private InsumoRepository insumos;

    @BeforeEach
    void limpar() {
        produtos.deleteAll(produtos.findByLojaIdOrderByNomeAsc(LOJA));
        insumos.deleteAll(insumos.findByLojaIdOrderByNomeAsc(LOJA));
    }

    private Produto produtoCom(Integer estoque) {
        Produto p = new Produto();
        p.lojaId = LOJA;
        p.nome = "Copo 500ml";
        p.preco = new BigDecimal("19.90");
        p.estoque = estoque;
        p.ativo = true;
        return produtos.save(p);
    }

    private Insumo insumoCom(String estoque) {
        Insumo i = new Insumo();
        i.lojaId = LOJA;
        i.nome = "Polpa de acai";
        i.unidade = "kg";
        i.custo = new BigDecimal("12.00");
        i.estoque = estoque == null ? null : new BigDecimal(estoque);
        return insumos.save(i);
    }

    private int estoqueDe(Long id) {
        return produtos.findByIdAndLojaId(id, LOJA).orElseThrow().estoque;
    }

    @Test
    void dezBaixasDeUmaUnidadeDeixamOEstoqueDezAbaixo() {
        Produto p = produtoCom(30);

        for (int i = 0; i < 10; i++) assertEquals(1, produtos.baixarEstoque(p.id, LOJA, 1));

        // A conta e do banco: nenhuma leitura em Java entre uma baixa e a seguinte.
        assertEquals(20, estoqueDe(p.id));
    }

    @Test
    void duasBaixasComAEntidadeLIDAANTESNaoSeAtropelam() {
        // Reproduz o cenario real: o balcao e o cardapio leem o produto, cada um com estoque 30, e
        // so depois cada um baixa. Com `prod.estoque = prod.estoque - qtd` os dois gravavam 28 e o
        // estoque terminava em 28 em vez de 26.
        Produto p = produtoCom(30);
        Produto lidoPeloBalcao = produtos.findByIdAndLojaId(p.id, LOJA).orElseThrow();
        Produto lidoPeloCardapio = produtos.findByIdAndLojaId(p.id, LOJA).orElseThrow();
        assertEquals(30, lidoPeloBalcao.estoque);
        assertEquals(30, lidoPeloCardapio.estoque);

        produtos.baixarEstoque(lidoPeloBalcao.id, LOJA, 2);
        produtos.baixarEstoque(lidoPeloCardapio.id, LOJA, 2);

        assertEquals(26, estoqueDe(p.id), "as duas baixas tem que contar");
    }

    @Test
    void produtoQueNaoControlaEstoqueContinuaSemNumero() {
        Produto p = produtoCom(null);

        assertEquals(0, produtos.baixarEstoque(p.id, LOJA, 5), "nada a baixar");
        assertNull(produtos.findByIdAndLojaId(p.id, LOJA).orElseThrow().estoque,
                "quem nao controla estoque nao pode nascer com saldo inventado");
    }

    @Test
    void naoBaixaOEstoqueDeProdutoDeOutraLoja() {
        Produto p = produtoCom(30);

        assertEquals(0, produtos.baixarEstoque(p.id, LOJA + 1, 5));
        assertEquals(30, estoqueDe(p.id));
    }

    @Test
    void aDevolucaoDoCancelamentoTambemEhNoBanco() {
        Produto p = produtoCom(10);
        produtos.baixarEstoque(p.id, LOJA, 3);

        assertEquals(1, produtos.devolverEstoque(p.id, LOJA, 3));

        assertEquals(10, estoqueDe(p.id));
    }

    @Test
    void doisCancelamentosDoMesmoPedidoNaoDevolvemEmDobroPorLeituraVelha() {
        Produto p = produtoCom(10);
        Produto lidoAgora = produtos.findByIdAndLojaId(p.id, LOJA).orElseThrow();
        produtos.devolverEstoque(lidoAgora.id, LOJA, 2);

        // O segundo ainda devolve (a idempotencia do cancelamento e do PedidoService, nao daqui), mas o
        // estoque reflete as DUAS devolucoes em vez de sobrescrever com a leitura velha.
        produtos.devolverEstoque(lidoAgora.id, LOJA, 2);

        assertEquals(14, estoqueDe(p.id));
    }

    @Test
    void oInsumoDaFichaTecnicaTambemBaixaNoBanco() {
        Insumo i = insumoCom("5.000");

        // 3 vendas de 1 copo, cada copo consome 0,250 kg
        for (int n = 0; n < 3; n++) {
            assertEquals(1, insumos.mexerNoEstoque(i.id, LOJA, new BigDecimal("0.250").negate()));
        }

        BigDecimal sobrou = insumos.findByIdAndLojaId(i.id, LOJA).orElseThrow().estoque;
        assertEquals(0, sobrou.compareTo(new BigDecimal("4.250")), "sobrou " + sobrou);
    }

    @Test
    void oInsumoVoltaNoCancelamento() {
        Insumo i = insumoCom("5.000");
        insumos.mexerNoEstoque(i.id, LOJA, new BigDecimal("0.500").negate());

        insumos.mexerNoEstoque(i.id, LOJA, new BigDecimal("0.500"));

        assertEquals(0, insumos.findByIdAndLojaId(i.id, LOJA).orElseThrow().estoque
                .compareTo(new BigDecimal("5.000")));
    }

    @Test
    void insumoSemControleDeEstoqueNaoNasceComSaldo() {
        Insumo i = insumoCom(null);

        assertEquals(0, insumos.mexerNoEstoque(i.id, LOJA, new BigDecimal("1.000").negate()));
        assertNull(insumos.findByIdAndLojaId(i.id, LOJA).orElseThrow().estoque);
    }
}
