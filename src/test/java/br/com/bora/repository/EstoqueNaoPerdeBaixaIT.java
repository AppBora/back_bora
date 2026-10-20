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

    // =============================================================================================
    // A baixa QUE RECUSA: `baixarEstoqueSeTiver` e `consumirSeTiver`, usadas pelo cardapio publico.
    //
    // O defeito que estes travam (20/10/2026): nada conferia estoque antes de vender. Uma unica
    // chamada do cardapio podia levar um produto a -4950 (50 itens x 99), porque a baixa so tinha
    // `where estoque is not null`.
    //
    // Isto PRECISA de banco de verdade por dois motivos. O primeiro: o retorno 1 para a linha de
    // estoque NULO depende de o Postgres contar como alterada uma linha cujo valor nao mudou (nulo
    // menos qualquer coisa e nulo). O segundo: a garantia contra duas vendas simultaneas e do banco,
    // nao de Java. Mock devolve o que o teste mandar.
    // =============================================================================================

    @Test
    void temOsuficiente_baixaEdevolveUm() {
        Produto p = produtoCom(10);

        assertEquals(1, produtos.baixarEstoqueSeTiver(p.id, LOJA, 3));

        assertEquals(7, estoqueDe(p.id));
    }

    @Test
    void temEXATAMENTEoPedido_baixaEzera() {
        // O limite. Pedir tudo que tem e venda legitima; recusar aqui deixaria a ultima unidade
        // encalhada para sempre.
        Produto p = produtoCom(3);

        assertEquals(1, produtos.baixarEstoqueSeTiver(p.id, LOJA, 3));

        assertEquals(0, estoqueDe(p.id));
    }

    @Test
    void naoTemOsuficiente_RECUSAEnaoMexeNoSaldo() {
        Produto p = produtoCom(2);

        assertEquals(0, produtos.baixarEstoqueSeTiver(p.id, LOJA, 3),
                "pedir 3 com 2 em estoque tem que recusar");

        assertEquals(2, estoqueDe(p.id), "recusar nao pode mexer no saldo");
    }

    @Test
    void estoqueZERADOrecusa() {
        Produto p = produtoCom(0);

        assertEquals(0, produtos.baixarEstoqueSeTiver(p.id, LOJA, 1));

        assertEquals(0, estoqueDe(p.id));
    }

    @Test
    void estoqueJAnegativoRecusa() {
        // Pode existir saldo negativo de antes: o balcao e o marketplace nao recusam, de proposito.
        // O cardapio nao pode afundar mais.
        Produto p = produtoCom(10);
        produtos.baixarEstoque(p.id, LOJA, 14);     // a baixa SEM trava, como no balcao
        assertEquals(-4, estoqueDe(p.id));

        assertEquals(0, produtos.baixarEstoqueSeTiver(p.id, LOJA, 1));

        assertEquals(-4, estoqueDe(p.id));
    }

    @Test
    void produtoSemCONTROLEdeEstoqueDEIXAPASSAR() {
        // A pedra fundamental do desenho, e o caso de TODOS os 123 produtos da producao hoje (medido
        // em 20/10/2026): estoque nulo quer dizer "o lojista escolheu nao controlar". A venda tem que
        // passar, e o retorno tem que ser 1 -- senao o cardapio inteiro pararia de vender.
        //
        // Por isso o 0 deste metodo significa UMA coisa so: controla estoque e nao tem o suficiente.
        Produto p = produtoCom(null);

        assertEquals(1, produtos.baixarEstoqueSeTiver(p.id, LOJA, 5),
                "produto sem controle de estoque NAO pode bloquear venda");

        assertNull(produtos.findByIdAndLojaId(p.id, LOJA).orElseThrow().estoque,
                "e continua sem numero, em vez de nascer com saldo inventado");
    }

    @Test
    void naoBaixaProdutoDeOutraLoja() {
        Produto p = produtoCom(10);

        assertEquals(0, produtos.baixarEstoqueSeTiver(p.id, LOJA + 1, 1));

        assertEquals(10, estoqueDe(p.id));
    }

    @Test
    void duasVendasDaULTIMAunidadeNaoPassamAsDUAS() {
        // O que a trava existe para impedir. Nao e teste de simultaneidade de verdade (uma thread so),
        // mas prova o que importa: a decisao e do BANCO, que reavalia o saldo a cada chamada. Com a
        // baixa antiga as duas passavam e o estoque ia a -1.
        //
        // A simultaneidade real foi medida a mao em duas sessoes psql: estoque 1, duas vendas de 1 ao
        // mesmo tempo, final 0 -- nao -1.
        Produto p = produtoCom(1);

        assertEquals(1, produtos.baixarEstoqueSeTiver(p.id, LOJA, 1), "a primeira venda leva");
        assertEquals(0, produtos.baixarEstoqueSeTiver(p.id, LOJA, 1), "a segunda tem que recusar");

        assertEquals(0, estoqueDe(p.id), "nunca pode ficar negativo por este caminho");
    }

    @Test
    void oInsumoTambemRecusaQuandoFalta() {
        Insumo i = insumoCom("0.400");

        assertEquals(1, insumos.consumirSeTiver(i.id, LOJA, new BigDecimal("0.250")));
        assertEquals(0, insumos.consumirSeTiver(i.id, LOJA, new BigDecimal("0.250")),
                "sobraram 0,150 kg: a segunda venda tem que recusar");

        assertEquals(0, insumos.findByIdAndLojaId(i.id, LOJA).orElseThrow().estoque
                .compareTo(new BigDecimal("0.150")), "recusar nao pode mexer no saldo");
    }

    @Test
    void insumoSemCONTROLEdeEstoqueDEIXAPASSAR() {
        Insumo i = insumoCom(null);

        assertEquals(1, insumos.consumirSeTiver(i.id, LOJA, new BigDecimal("9.999")));

        assertNull(insumos.findByIdAndLojaId(i.id, LOJA).orElseThrow().estoque);
    }

    @Test
    void aDEVOLUCAOdoInsumoNUNCAeRecusadaPorFalta() {
        // Por que `consumirSeTiver` e `mexerNoEstoque` sao metodos SEPARADOS, e nao um com sinal:
        // devolver estoque nao pode ser barrado por saldo baixo. Um cancelamento tem que conseguir
        // devolver mesmo com o insumo em zero ou negativo -- foi a venda que nao aconteceu.
        Insumo i = insumoCom("0.100");
        insumos.mexerNoEstoque(i.id, LOJA, new BigDecimal("0.500").negate());   // vai a -0,400
        assertEquals(-1, insumos.findByIdAndLojaId(i.id, LOJA).orElseThrow().estoque.signum());

        assertEquals(1, insumos.mexerNoEstoque(i.id, LOJA, new BigDecimal("0.500")),
                "a devolucao tem que passar mesmo com o saldo negativo");

        assertEquals(0, insumos.findByIdAndLojaId(i.id, LOJA).orElseThrow().estoque
                .compareTo(new BigDecimal("0.100")));
    }
}
