package br.com.bora.repository;

import br.com.bora.dto.ProdutoResumo;
import br.com.bora.entity.Produto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A lista de produtos do painel não carrega a foto em base64.
 *
 * <p><b>O peso que isto tirou:</b> a foto vive como data URL dentro de {@code produto.imagem_url}. Na
 * Zirá são <b>453 kB</b> em 31 de 33 produtos (medido em produção, 14/10/2026; 1,36 MB nas cinco
 * lojas). O {@code GET /api/produtos} devolvia a entidade inteira, então Produtos, Frente de Caixa,
 * Estoque, Contagem e Novo Pedido liam e trafegavam esse meio megabyte a cada abertura.</p>
 *
 * <p>Isto só pode ser provado contra um banco: o que mudou é a <b>consulta</b>, e mock devolveria o que
 * o teste mandasse. O teste compara o tamanho do que a projeção traz com o tamanho da foto.</p>
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
class ListaDeProdutosNaoCarregaFotoIT {

    private static final Long LOJA = 9201L;
    /** Uma foto de verdade tem umas dezenas de kB em base64; 20 mil caracteres é um tamanho honesto. */
    private static final String FOTO = "data:image/jpeg;base64," + "A".repeat(20_000);

    @Autowired private ProdutoRepository produtos;

    @BeforeEach
    void limpar() {
        produtos.deleteAll(produtos.findByLojaIdOrderByNomeAsc(LOJA));
    }

    private Produto novo(String nome, String imagem) {
        Produto p = new Produto();
        p.lojaId = LOJA;
        p.nome = nome;
        p.categoria = "Açaí";
        p.preco = new BigDecimal("19.90");
        p.custo = new BigDecimal("6.00");
        p.estoque = 10;
        p.estoqueMinimo = 2;
        p.ativo = true;
        p.imagemUrl = imagem;
        return produtos.save(p);
    }

    private List<ProdutoResumo> lista() {
        return produtos.resumoDaLoja(LOJA).stream()
                .map(r -> ProdutoResumo.de((Long) r[0], (String) r[1], (String) r[2],
                        (BigDecimal) r[3], (BigDecimal) r[4], (Integer) r[5], (Integer) r[6],
                        (Boolean) r[7], LOJA, Boolean.TRUE.equals(r[8]), (String) r[9]))
                .toList();
    }

    @Test
    void aProjecaoNaoTrazOBase64_eOPesoProvaIsso() {
        novo("Copo 500ml", FOTO);

        List<ProdutoResumo> r = lista();

        assertEquals(1, r.size());
        int pesoDaLinha = String.valueOf(r.get(0)).length();
        assertTrue(pesoDaLinha < 500,
                "a linha devia ser pequena e veio com " + pesoDaLinha + " caracteres; a foto tem " + FOTO.length());
        assertFalse(String.valueOf(r.get(0)).contains("base64"), "a foto veio junto");
    }

    @Test
    void quemTemFotoRecebeOEnderecoDela_eQuemNaoTemRecebeNulo() {
        novo("Com foto", FOTO);
        novo("Sem foto", null);

        var porNome = lista().stream().collect(
                java.util.stream.Collectors.toMap(ProdutoResumo::nome, p -> p));

        assertTrue(porNome.get("Com foto").imagemUrl().startsWith("/public/loja/" + LOJA + "/produto/"),
                "a tela usa isto como src da img; o navegador cacheia");
        assertTrue(porNome.get("Com foto").imagemUrl().endsWith("/imagem"));
        assertNull(porNome.get("Sem foto").imagemUrl());
    }

    @Test
    void stringVaziaNaColunaContaComoSemFoto() {
        novo("Vazia", "");

        assertNull(lista().get(0).imagemUrl());
    }

    @Test
    void osCamposQueAsTelasUSAMContinuamVindo() {
        novo("Copo 500ml", FOTO);

        ProdutoResumo p = lista().get(0);

        assertEquals("Copo 500ml", p.nome());
        assertEquals("Açaí", p.categoria());
        assertEquals(0, p.preco().compareTo(new BigDecimal("19.90")));
        assertEquals(0, p.custo().compareTo(new BigDecimal("6.00")), "o PDV e o CMV dependem do custo");
        assertEquals(10, p.estoque());
        assertEquals(2, p.estoqueMinimo());
        assertEquals(Boolean.TRUE, p.ativo());
        assertNotNull(p.id());
    }

    @Test
    void aFotoCONTINUANOBANCO_aListagemSoNaoATraz() {
        Produto salvo = novo("Copo 500ml", FOTO);

        // Esta e a parte que importa nao quebrar: a listagem nao pode ter virado um apagador de foto.
        assertEquals(FOTO, produtos.findByIdAndLojaId(salvo.id, LOJA).orElseThrow().imagemUrl);
        lista();
        assertEquals(FOTO, produtos.findByIdAndLojaId(salvo.id, LOJA).orElseThrow().imagemUrl,
                "listar nao pode mexer na foto gravada");
    }

    @Test
    void aOrdemEhPorNome_comoAsTelasEsperam() {
        novo("Zabaione", null);
        novo("Açaí", null);
        novo("Morango", null);

        assertEquals(List.of("Açaí", "Morango", "Zabaione"),
                lista().stream().map(ProdutoResumo::nome).toList());
    }

    @Test
    void produtoDeOutraLojaNaoApareceNaLista() {
        novo("Da loja", null);
        Produto outra = new Produto();
        outra.lojaId = LOJA + 1; outra.nome = "De outra loja"; outra.preco = BigDecimal.ONE;
        produtos.save(outra);

        assertEquals(List.of("Da loja"), lista().stream().map(ProdutoResumo::nome).toList());
        produtos.delete(outra);
    }
}
