package br.com.bora.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Apagar produto com complemento funciona — e o banco realmente recusaria sem a limpeza.
 *
 * <h2>Por que contra Postgres de verdade</h2>
 * <p>O item 4.3 é uma chave estrangeira. Teste com mock prova que o serviço chama os quatro
 * {@code delete} na ordem certa; ele <b>não prova</b> que o 500 acabou, porque o mock nunca recusa
 * nada. Quem recusa é o banco, e os {@code *IT} são excluídos do {@code mvn test} e do build do
 * deploy.</p>
 *
 * <p>O primeiro teste desta classe é a <b>premissa</b>: ele apaga o produto do jeito antigo e exige
 * que o banco recuse. Sem esse teste, o resto poderia estar passando porque a FK não existe mais, e
 * eu acharia que provei algo. Já me aconteceu — ver a lição do freio contra força bruta, em que medi
 * metade do caminho e narrei o caminho inteiro.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:55432/bora",
        "spring.datasource.username=postgres",
        "spring.datasource.password=teste",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class ApagarProdutoComComplementoIT {

    @Autowired private EntityManager em;
    @Autowired private ComplementoGrupoRepository grupos;
    @Autowired private ComplementoItemRepository opcoes;
    @Autowired private ProdutoInsumoRepository fichas;

    private Long loja;
    private Long produto;

    @BeforeEach
    @Transactional
    void semear() {
        loja = (Long) em.createNativeQuery(
                        "insert into loja (nome, ativo) values ('Loja do 4.3', true) returning id")
                .getSingleResult();
        produto = (Long) em.createNativeQuery(
                        "insert into produto (loja_id, nome, preco, ativo) "
                                + "values (?1, 'Acai 500ml', 20.00, true) returning id")
                .setParameter(1, loja).getSingleResult();
        Long grupo = (Long) em.createNativeQuery(
                        "insert into complemento_grupo (loja_id, produto_id, nome, minimo, maximo) "
                                + "values (?1, ?2, 'Adicionais', 0, 5) returning id")
                .setParameter(1, loja).setParameter(2, produto).getSingleResult();
        em.createNativeQuery("insert into complemento_item (loja_id, grupo_id, nome, preco) "
                        + "values (?1, ?2, 'Granola', 3.00)")
                .setParameter(1, loja).setParameter(2, grupo).executeUpdate();
        Long insumo = (Long) em.createNativeQuery(
                        "insert into insumo (loja_id, nome, unidade, custo, estoque) "
                                + "values (?1, 'Polpa', 'kg', 20.00, 5.000) returning id")
                .setParameter(1, loja).getSingleResult();
        em.createNativeQuery("insert into produto_insumo (loja_id, produto_id, insumo_id, quantidade) "
                        + "values (?1, ?2, ?3, 0.5000)")
                .setParameter(1, loja).setParameter(2, produto).setParameter(3, insumo).executeUpdate();
        em.flush();
    }

    // =============================================================== a premissa

    @Test
    @Transactional
    void oBANCOrealmenteRECUSAoDELETEseco() {
        // Isto e o defeito do item 4.3, reproduzido. Se este teste parar de falhar, a FK mudou e os
        // outros testes desta classe passaram a nao provar nada.
        assertThrows(PersistenceException.class, () -> {
            em.createNativeQuery("delete from produto where id = ?1").setParameter(1, produto)
                    .executeUpdate();
            em.flush();
        }, "a FK de complemento_grupo para produto tem que recusar -- e esse era o erro 500");
    }

    @Test
    @Transactional
    void aFKexisteESEMon_delete() {
        // A premissa escrita de outra forma: a FK existe e NAO tem ON DELETE. Se alguem puser CASCADE
        // na migracao, este teste avisa -- e a limpeza no servico passa a ser redundante, nao errada.
        String tipo = (String) em.createNativeQuery(
                        "select confdeltype::text from pg_constraint "
                                + "where conrelid = 'complemento_grupo'::regclass and contype = 'f' "
                                + "and confrelid = 'produto'::regclass")
                .getSingleResult();

        assertEquals("a", tipo, "'a' = NO ACTION. Mudou para 'c' (CASCADE)? Atualize o ProdutoService.");
    }

    // =============================================================== a ordem que funciona

    @Test
    @Transactional
    void naORDEMdoSERVICO_oDELETEpassa() {
        // A mesma sequencia do ProdutoService.excluir, contra o banco de verdade.
        List<Long> ids = grupos.findByLojaIdAndProdutoIdOrderById(loja, produto)
                .stream().map(g -> g.id).toList();
        assertFalse(ids.isEmpty(), "o cenario tem que ter grupo, senao nao prova nada");

        opcoes.deleteByLojaIdAndGrupoIdIn(loja, ids);
        grupos.deleteByLojaIdAndProdutoId(loja, produto);
        fichas.deleteByLojaIdAndProdutoId(loja, produto);
        em.flush();

        assertDoesNotThrow(() -> {
            em.createNativeQuery("delete from produto where id = ?1").setParameter(1, produto)
                    .executeUpdate();
            em.flush();
        });
    }

    @Test
    @Transactional
    void depoisDEapagar_naoSOBRAlinhaORFA() {
        List<Long> ids = grupos.findByLojaIdAndProdutoIdOrderById(loja, produto)
                .stream().map(g -> g.id).toList();
        opcoes.deleteByLojaIdAndGrupoIdIn(loja, ids);
        grupos.deleteByLojaIdAndProdutoId(loja, produto);
        fichas.deleteByLojaIdAndProdutoId(loja, produto);
        em.createNativeQuery("delete from produto where id = ?1").setParameter(1, produto)
                .executeUpdate();
        em.flush();
        em.clear();

        // A ficha e o caso que a varredura nao citou: produto_insumo NAO tem FK, entao o delete seco
        // passava e deixava a linha apontando para o nada.
        assertTrue(fichas.findByLojaIdAndProdutoId(loja, produto).isEmpty(),
                "sobrou linha de ficha tecnica apontando para produto que nao existe");
        assertTrue(grupos.findByLojaIdAndProdutoIdOrderById(loja, produto).isEmpty());
        assertEquals(0L, ((Number) em.createNativeQuery(
                        "select count(*) from complemento_item where loja_id = ?1")
                .setParameter(1, loja).getSingleResult()).longValue());
    }

    // =============================================================== o historico nao e tocado

    @Test
    @Transactional
    void oHISTORICOdeVENDAnaoIMPEDEnemSEperde() {
        // Medido: pedido_item NAO tem FK para produto, e o relatorio por produto agrupa pela
        // `descricao` gravada no item. Por isso apagar produto vendido nao precisa ser recusado -- e o
        // relatorio antigo continua mostrando o nome que o produto tinha no dia da venda.
        Long pedido = (Long) em.createNativeQuery(
                        "insert into pedido (loja_id, status, valor_total, criado_em) "
                                + "values (?1, 'ENTREGUE', 20.00, now()) returning id")
                .setParameter(1, loja).getSingleResult();
        em.createNativeQuery("insert into pedido_item (loja_id, pedido_id, produto_id, descricao, "
                        + "quantidade, preco_unitario, subtotal) "
                        + "values (?1, ?2, ?3, 'Acai 500ml (Granola)', 1, 23.00, 23.00)")
                .setParameter(1, loja).setParameter(2, pedido).setParameter(3, produto).executeUpdate();
        em.flush();

        List<Long> ids = grupos.findByLojaIdAndProdutoIdOrderById(loja, produto)
                .stream().map(g -> g.id).toList();
        opcoes.deleteByLojaIdAndGrupoIdIn(loja, ids);
        grupos.deleteByLojaIdAndProdutoId(loja, produto);
        fichas.deleteByLojaIdAndProdutoId(loja, produto);

        assertDoesNotThrow(() -> {
            em.createNativeQuery("delete from produto where id = ?1").setParameter(1, produto)
                    .executeUpdate();
            em.flush();
        }, "pedido_item nao tem FK para produto: a venda nao pode impedir o apagar");

        String descricao = (String) em.createNativeQuery(
                        "select descricao from pedido_item where pedido_id = ?1")
                .setParameter(1, pedido).getSingleResult();
        assertEquals("Acai 500ml (Granola)", descricao,
                "o nome do item de venda tem que sobreviver ao produto, senao o relatorio perde a venda");
    }

    @Test
    @Transactional
    void oCENARIOfoiREALMENTEsemeado() {
        // Sem isto, um erro no insert deixaria todos os testes acima passando sobre uma base vazia.
        assertEquals(1, grupos.findByLojaIdAndProdutoIdOrderById(loja, produto).size());
        assertEquals(1, fichas.findByLojaIdAndProdutoId(loja, produto).size());
        assertEquals(0, new BigDecimal("1").compareTo(
                new BigDecimal(((Number) em.createNativeQuery(
                                "select count(*) from complemento_item where loja_id = ?1")
                        .setParameter(1, loja).getSingleResult()).toString())));
    }
}
