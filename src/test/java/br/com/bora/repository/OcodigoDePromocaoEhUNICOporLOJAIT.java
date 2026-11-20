package br.com.bora.repository;

import br.com.bora.entity.Promocao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Um codigo de promocao por loja — garantido pelo BANCO, nao por comentario.
 *
 * <h2>O defeito que isto fecha</h2>
 * <p>{@code PromocaoService.abrirAutomatica} criava a promocao com o codigo fixo
 * {@code 'BORA15L' || lojaId} e um comentario na propria linha dizendo <i>"codigo unico por loja"</i>.
 * <b>Nada garantia isso.</b> O scheduler desligava a promocao 3 h depois, mas a linha ficava; no dia
 * seguinte as guardas liberavam e nascia outra com o mesmo codigo.</p>
 *
 * <p>Com duas linhas, o {@code DescontoService} — que buscava com um metodo {@code Optional} — recebia
 * {@code IncorrectResultSizeDataAccessException}: <b>HTTP 500 no cupom do cardapio publico</b>.</p>
 *
 * <p><b>Medido no dump de producao de 19/11/2026:</b> a loja 18 (Zira, cliente pagante) tinha
 * <b>4 linhas</b> com {@code BORA15L18}, todas com {@code ativa = false} e {@code usos = 0}. O cupom
 * dela estava quebrado em producao.</p>
 *
 * <h2>O que cada teste aqui mede</h2>
 * <p>A parte que vive no banco: o indice unico existe, recusa a segunda linha, e o renome da V77
 * separa o que ja estava duplicado. O lado do servico — que duplicata nunca mais vire 500 — esta em
 * {@code ODescontoNaoEstouraComCodigoDuplicadoTest}, porque com o indice no lugar nao da mais para
 * criar duplicata por aqui.</p>
 *
 * <p><b>Como rodar:</b> precisa do Postgres de teste.</p>
 * <pre>
 * bash testes-de-integracao.sh --so-uma OcodigoDePromocaoEhUNICOporLOJAIT
 * </pre>
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
class OcodigoDePromocaoEhUNICOporLOJAIT {

    private static final Long LOJA = 9077L;
    private static final Long OUTRA_LOJA = 9078L;

    @Autowired private PromocaoRepository promocoes;
    @Autowired private JdbcTemplate jdbc;

    /**
     * Limpa ANTES de cada teste, e nao depois — e a razao e a mesma que a V77 documenta.
     *
     * <p>No PostgreSQL, <b>a instrucao que falha envenena a transacao inteira</b>: depois de um
     * {@code assertThrows(DataIntegrityViolationException)}, qualquer comando seguinte na mesma
     * transacao responde {@code 25P02 - current transaction is aborted}. Com {@code @AfterEach}, o
     * {@code delete} daqui rodava JUSTAMENTE nessa transacao envenenada e os dois testes de
     * violacao de indice terminavam em erro — com a assercao deles passando. Medido em 19/11/2026.</p>
     *
     * <p>E limpar depois nao e necessario: {@code @DataJpaTest} e transacional e desfaz cada teste
     * ao fim. O {@code @BeforeEach} fica como cinto de seguranca, para o caso de alguem rodar isto
     * com a transacao desligada.</p>
     */
    @BeforeEach
    void limpar() {
        jdbc.update("delete from promocao where loja_id in (?, ?)", LOJA, OUTRA_LOJA);
        jdbc.update("drop table if exists promocao_copia_do_teste");
    }

    /** Uma promocao gravada por SQL cru, para nao depender do servico nem do JPA. */
    private void gravarPorSql(Long loja, String codigo) {
        jdbc.update("""
                insert into promocao (loja_id, tipo, descricao, codigo, percentual_desconto,
                                      inicio, fim, limite_usos, usos, ativa, origem, criado_em)
                values (?, 'RELAMPAGO', 'teste', ?, 15.00, now(), now() + interval '3 hours',
                        20, 0, false, 'AUTO', now())
                """, loja, codigo);
    }

    // ----------------------------------------------------------------- a premissa

    /**
     * A PREMISSA: o indice existe, e e UNICO.
     *
     * <p>Sem esta medicao, os testes abaixo poderiam passar por qualquer outro motivo — uma
     * restricao diferente, um erro de digitacao no nome. Aqui eu exijo o objeto certo no schema.</p>
     */
    @Test
    void aPREMISSAoINDICEunicoEXISTEnoSCHEMA() {
        Integer quantos = jdbc.queryForObject(
                "select count(*) from pg_indexes where tablename = 'promocao' "
                        + "and indexname = 'uq_promocao_loja_codigo'", Integer.class);
        assertEquals(1, quantos, "a V77 nao criou o indice uq_promocao_loja_codigo");

        String definicao = jdbc.queryForObject(
                "select indexdef from pg_indexes where indexname = 'uq_promocao_loja_codigo'",
                String.class);
        assertTrue(definicao.contains("UNIQUE"), "o indice existe mas NAO e unico: " + definicao);
        assertTrue(definicao.contains("upper"),
                "o indice tem de ser sobre upper(codigo): a busca do checkout ignora caixa, e sem o "
                        + "upper 'bora15l18' e 'BORA15L18' seriam duas linhas para o banco e a MESMA "
                        + "para a busca. Definicao: " + definicao);
    }

    // ----------------------------------------------------------------- o que importa

    /** O banco recusa a segunda linha com o mesmo codigo na mesma loja. */
    @Test
    void oBANCOrecusaSEGUNDAlinhaComOmesmoCODIGO() {
        gravarPorSql(LOJA, "BORA15L9077");

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> gravarPorSql(LOJA, "BORA15L9077"),
                "o banco ACEITOU duas promocoes com o mesmo codigo na mesma loja — era exatamente "
                        + "isso que fazia o cupom da loja 18 responder 500");
    }

    /** E recusa tambem quando a caixa e diferente, porque a busca do checkout ignora caixa. */
    @Test
    void oBANCOrecusaTAMBEMcomCAIXAdiferente() {
        gravarPorSql(LOJA, "BORA15L9077");

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> gravarPorSql(LOJA, "bora15l9077"),
                "minusculo passou: para o banco sao duas linhas, para a busca IgnoreCase e a MESMA "
                        + "promocao — e o 500 volta pela porta dos fundos");
    }

    // ----------------------------------------------------------------- o que NAO pode quebrar

    /** O CONTROLE: o mesmo codigo em OUTRA loja continua valendo. */
    @Test
    void oMESMOcodigoEmOUTRAlojaCONTINUAvalendo() {
        gravarPorSql(LOJA, "PROMO10");
        assertDoesNotThrow(() -> gravarPorSql(OUTRA_LOJA, "PROMO10"),
                "o indice e por LOJA; duas lojas podem ter o mesmo codigo");
    }

    /**
     * O CONTROLE 2: codigo NULO nao bloqueia o indice.
     *
     * <p>Existe em producao uma promocao com codigo nulo (id 2, loja 1, origem MANUAL). Se o indice
     * a bloqueasse, a migracao derrubaria o deploy.</p>
     */
    @Test
    void codigoNULOnaoBLOQUEIAoINDICE() {
        jdbc.update("""
                insert into promocao (loja_id, tipo, descricao, codigo, percentual_desconto,
                                      inicio, limite_usos, usos, ativa, origem, criado_em)
                values (?, 'RELAMPAGO', 'sem codigo', null, 15.00, now(), 20, 0, false, 'MANUAL', now())
                """, LOJA);

        assertDoesNotThrow(() -> jdbc.update("""
                insert into promocao (loja_id, tipo, descricao, codigo, percentual_desconto,
                                      inicio, limite_usos, usos, ativa, origem, criado_em)
                values (?, 'RELAMPAGO', 'sem codigo tambem', null, 15.00, now(), 20, 0, false, 'MANUAL', now())
                """, LOJA), "duas promocoes de codigo nulo colidiram — o predicado do indice falhou");
    }

    // ----------------------------------------------------------------- o renome da migracao

    /**
     * O RENOME da V77 separa o que JA estava duplicado — testado com o SQL do arquivo, nao com uma
     * copia dele.
     *
     * <p>Na base de teste a V77 roda sobre tabela vazia, entao o renome nunca e exercitado. Aqui eu
     * monto uma copia da tabela com as 4 linhas da loja 18 (o caso real), extraio o {@code UPDATE} do
     * proprio arquivo de migracao no classpath e rodo nela. Se alguem mexer no SQL da migracao, este
     * teste muda de resultado — e e isso que se quer.</p>
     */
    @Test
    void oRENOMEdaMigracaoSEPARAasQUATROlinhasDaLOJA18() throws Exception {
        String sql = new String(getClass().getResourceAsStream(
                "/db/migration/V77__codigo_de_promocao_unico_por_loja.sql").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

        int inicio = sql.indexOf("WITH ordenadas");
        int fim = sql.indexOf(';', inicio);
        assertTrue(inicio > 0 && fim > inicio,
                "nao achei o UPDATE de renome na V77 — a migracao mudou de forma e este teste "
                        + "precisa ser revisto");
        String renome = sql.substring(inicio, fim);

        // A copia tem o mesmo formato da tabela, SEM o indice unico -- e por isso aceita a duplicata
        // que o caso real tinha.
        jdbc.execute("create table promocao_copia_do_teste as select * from promocao where false");
        for (int i = 0; i < 4; i++) {
            jdbc.update("insert into promocao_copia_do_teste "
                    + "(id, loja_id, tipo, codigo, inicio, ativa, usos, origem) "
                    + "values (?, 18, 'RELAMPAGO', 'BORA15L18', ?, false, 0, 'AUTO')",
                    900 + i, OffsetDateTime.now().minusDays(4 - i));
        }

        // A PREMISSA deste teste: as quatro linhas existem com o MESMO codigo antes do renome.
        assertEquals(4, (int) jdbc.queryForObject(
                "select count(*) from promocao_copia_do_teste where upper(codigo) = 'BORA15L18'",
                Integer.class), "a massa do teste nao reproduziu o caso da loja 18");

        jdbc.execute(renome.replace("promocao", "promocao_copia_do_teste"));

        assertEquals(1, (int) jdbc.queryForObject(
                "select count(*) from promocao_copia_do_teste where upper(codigo) = 'BORA15L18'",
                Integer.class),
                "depois do renome tinha de sobrar UMA linha com o codigo original");
        assertEquals(4, (int) jdbc.queryForObject(
                "select count(distinct upper(codigo)) from promocao_copia_do_teste", Integer.class),
                "as quatro linhas tinham de ficar com codigos distintos — nenhuma foi apagada");

        // E a que ficou com o codigo original e a MAIS RECENTE, que e a que o DescontoService
        // escolheria: manter ela e manter o comportamento de hoje.
        assertEquals(903, (int) jdbc.queryForObject(
                "select id from promocao_copia_do_teste where upper(codigo) = 'BORA15L18'",
                Integer.class), "o codigo original ficou na linha errada");
    }

    // ----------------------------------------------------------------- o servico

    /** Chamar duas vezes nao cria duas linhas: a segunda reaproveita a primeira. */
    @Test
    void duasABERTURASdeixamUMAlinha() {
        Promocao primeira = new Promocao();
        primeira.setLojaId(LOJA);
        primeira.setTipo("RELAMPAGO");
        primeira.setCodigo("BORA15L" + LOJA);
        primeira.setPercentualDesconto(new BigDecimal("15.00"));
        primeira.setInicio(OffsetDateTime.now().minusDays(1));
        primeira.setFim(OffsetDateTime.now().minusDays(1).plusHours(3));
        primeira.setAtiva(false);
        primeira.setOrigem("AUTO");
        promocoes.save(primeira);

        var achadas = promocoes.findByLojaIdAndCodigoIgnoreCaseOrderByInicioDesc(
                LOJA, "BORA15L" + LOJA);
        assertEquals(1, achadas.size());

        // o que o servico faz: reaproveita a MESMA linha
        Promocao reaproveitada = achadas.get(0);
        reaproveitada.setInicio(OffsetDateTime.now());
        reaproveitada.setFim(OffsetDateTime.now().plusHours(3));
        reaproveitada.setUsos(0);
        reaproveitada.setAtiva(true);
        promocoes.save(reaproveitada);

        assertEquals(1, promocoes.findByLojaIdAndCodigoIgnoreCaseOrderByInicioDesc(
                        LOJA, "BORA15L" + LOJA).size(),
                "reaproveitar tinha de deixar UMA linha, nao duas");
        assertEquals(primeira.getId(), reaproveitada.getId(), "era para ser a mesma linha");
    }
}
