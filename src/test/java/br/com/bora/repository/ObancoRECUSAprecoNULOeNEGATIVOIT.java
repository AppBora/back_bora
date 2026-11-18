package br.com.bora.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O BANCO recusa preço de produto nulo ou negativo — e aceita zero.
 *
 * <h2>Por que isto é um teste de integração, e não um unitário</h2>
 * <p>Porque a regra que ele mede é uma {@code check constraint} do Postgres
 * ({@code ck_produto_preco_valido}, V75), e constraint é uma coisa que só existe quando um banco de
 * verdade está do outro lado. A validação em Java ({@code PrecoDoCardapio}) já tem teste unitário e
 * de caminho. Esta aqui é a trava de baixo, a que vale para as portas que NÃO passam pelo
 * {@code ProdutoService}:</p>
 * <ul>
 *   <li>{@code CopiaDeCardapioService} — faz {@code n.preco = o.preco} sem conferir nada. É o
 *       caminho de abrir unidade nova (foi assim na 6ª loja da Zirá): um produto sem preço na loja
 *       de origem viajava para a loja nova em silêncio.</li>
 *   <li>SQL direto na instância.</li>
 * </ul>
 *
 * <h2>E por que ela é NOT VALID</h2>
 * <p>{@code NOT VALID} faz o Postgres aplicar a regra em todo {@code INSERT} e {@code UPDATE} a
 * partir de agora, mas pular a varredura das linhas que já existem. Eu medi as 6 lojas reais pelo
 * cardápio público (182 produtos ativos, nenhum sem preço), mas o cardápio público só mostra
 * {@code ativo = true} — produto desativado e as 17 lojas de teste eu não vi. Uma constraint validada
 * que encontrasse UMA dessas linhas faria a migração falhar, e com ela o deploy inteiro.</p>
 *
 * <p><b>O primeiro teste deste arquivo mede exatamente isso:</b> que {@code NOT VALID} realmente
 * barra o INSERT novo. Sem essa medição eu estaria confiando num detalhe de documentação do Postgres
 * para proteger o caminho do dinheiro.</p>
 *
 * <p><b>Como rodar:</b> precisa do Postgres de teste.</p>
 * <pre>
 * bash testes-de-integracao.sh
 * </pre>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:55432/bora",
        "spring.datasource.username=postgres",
        "spring.datasource.password=teste",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class ObancoRECUSAprecoNULOeNEGATIVOIT {

    private static final String MARCA = "zz-teste-preco";

    @Autowired private DataSource dados;

    @AfterEach
    void limpar() throws SQLException {
        try (Connection c = dados.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("delete from produto where nome like '" + MARCA + "%'");
        }
    }

    /**
     * Grava por JDBC puro, cada tentativa na sua PRÓPRIA conexão.
     *
     * <h3>Por que uma conexão por tentativa</h3>
     * <p>Porque uma constraint recusada <b>envenena a transação inteira</b> no Postgres: tudo o que
     * vier depois falha com "current transaction is aborted", inclusive a limpeza e a tentativa
     * seguinte. Compartilhando conexão, o relatório culparia o teste errado — foi assim que eu já
     * perdi tempo neste projeto (ver V126/V154: a recusa desfaz a transação inteira).</p>
     *
     * <p>E é JDBC em vez de {@code EntityManager} porque o que está sendo medido é o <b>banco</b>:
     * passar por JPA acrescentaria uma camada que pode mascarar ou traduzir a recusa.</p>
     */
    private int inserir(String sufixo, String precoSql) throws SQLException {
        try (Connection c = dados.getConnection(); Statement st = c.createStatement()) {
            return st.executeUpdate("insert into produto(loja_id, nome, categoria, preco, ativo) "
                    + "values (1, '" + MARCA + sufixo + "', 'Teste', " + precoSql + ", true)");
        }
    }

    private int atualizarPreco(String sufixo, String precoSql) throws SQLException {
        try (Connection c = dados.getConnection(); Statement st = c.createStatement()) {
            return st.executeUpdate("update produto set preco = " + precoSql
                    + " where nome = '" + MARCA + sufixo + "'");
        }
    }

    @Test
    void aCONSTRAINTexisteEestaNAtabelaPRODUTO() throws SQLException {
        try (Connection c = dados.getConnection(); Statement st = c.createStatement();
             ResultSet r = st.executeQuery(
                     "select conname, convalidated, pg_get_constraintdef(oid) from pg_constraint "
                             + "where conrelid = 'produto'::regclass "
                             + "and conname = 'ck_produto_preco_valido'")) {

            assertTrue(r.next(), "a constraint da V75 nao esta na tabela produto");
            assertFalse(r.getBoolean(2),
                    "era esperada NOT VALID: validada, a migracao varreria a tabela e podia "
                            + "falhar o deploy por causa de um produto inativo de loja de teste");
            String definicao = r.getString(3);
            assertTrue(definicao.contains(">= 0") || definicao.contains(">= (0)"),
                    "a regra tem de ser >= 0, nunca > 0: zero e o Monte Seu Acai. Definicao: " + definicao);
            assertFalse(r.next(), "constraint duplicada");
        }
    }

    /**
     * A MEDIÇÃO QUE IMPORTA: {@code NOT VALID} barra o INSERT novo de preço nulo.
     *
     * <p>É o ponto todo da migração. Se {@code NOT VALID} só valesse para linhas futuras "em teoria"
     * e não barrasse de fato, a trava de baixo não existiria e eu teria escrito uma migração
     * decorativa.</p>
     */
    @Test
    void precoNULOehRECUSADOpeloBANCO() {
        Exception e = assertThrows(Exception.class, () -> inserir("-nulo", "null"));
        assertTrue(raiz(e).contains("ck_produto_preco_valido"),
                "recusou por outro motivo: " + raiz(e));
    }

    @Test
    void precoNEGATIVOehRECUSADOpeloBANCO() {
        Exception e = assertThrows(Exception.class, () -> inserir("-negativo", "-0.01"));
        assertTrue(raiz(e).contains("ck_produto_preco_valido"),
                "recusou por outro motivo: " + raiz(e));
    }

    /**
     * ZERO PASSA NO BANCO. É o "Monte Seu Açaí" das lojas 18, 19 e 20 e os brindes da loja 1.
     *
     * <p>Se alguém trocar a constraint por {@code preco > 0}, este teste reprova — e é o único aviso
     * antes de 5 produtos pararem de salvar em 4 lojas reais.</p>
     */
    @Test
    void precoZEROpassaNOBANCO() throws SQLException {
        assertEquals(1, inserir("-zero", "0.00"));
    }

    @Test
    void precoPOSITIVOpassaNOBANCO() throws SQLException {
        assertEquals(1, inserir("-positivo", "24.90"));
    }

    /** O UPDATE também é barrado, não só o INSERT: é por onde um preço válido viraria nulo. */
    @Test
    void oUPDATEparaNULOtambemEHrecusado() throws SQLException {
        assertEquals(1, inserir("-update", "24.90"));

        Exception e = assertThrows(Exception.class, () -> atualizarPreco("-update", "null"));
        assertTrue(raiz(e).contains("ck_produto_preco_valido"), raiz(e));

        assertEquals(1, atualizarPreco("-update", "0.00"),
                "e baixar para ZERO de proposito continua podendo");
    }

    private static String raiz(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) sb.append(c.getMessage()).append(" | ");
        return sb.toString();
    }
}
