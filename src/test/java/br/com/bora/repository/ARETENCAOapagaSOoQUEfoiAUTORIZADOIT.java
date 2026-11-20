package br.com.bora.repository;

import br.com.bora.entity.ConfigPlataforma;
import br.com.bora.service.RetencaoDaFila;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ITEM 8 DA SECAO 4: a retencao da fila, contra um PostgreSQL de verdade. (20/11/2026)
 *
 * <h2>O que SO um banco prova</h2>
 * <p>O teste de unidade garante as decisoes (sem chave nao apaga, valor invalido nao e zero, as duas
 * chaves sao independentes) com {@code JdbcTemplate} dublado -- ou seja, <b>sem nunca executar o
 * SQL</b>. Tres coisas ficam de fora dele, e todas as tres apagam dado de producao se estiverem
 * erradas:</p>
 * <ol>
 *   <li><b>O SQL roda?</b> Ele usa {@code make_interval(days => ?)} com parametro de JDBC e
 *       {@code ctid in (select ... limit n)}. Se qualquer um dos dois nao for aceito, a rotina da
 *       madrugada so descobre isso em producao.</li>
 *   <li><b>Ele apaga o que devia, e so isso?</b> A idade e a condicao de publicacao sao comparadas
 *       pelo banco. Um sinal trocado apagaria o evento NOVO em vez do velho.</li>
 *   <li><b>O teto por execucao vale de verdade?</b> O {@code limit} esta dentro de uma subconsulta;
 *       se ele nao estiver limitando, um DELETE pega a tabela inteira de uma vez, com lock, no mesmo
 *       banco onde as lojas estao vendendo.</li>
 * </ol>
 *
 * <p><b>Como rodar:</b> {@code bash testes-de-integracao.sh --so-uma ARETENCAOapagaSOoQUEfoiAUTORIZADOIT}</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:55432/bora",
        "spring.datasource.username=postgres",
        "spring.datasource.password=teste",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class ARETENCAOapagaSOoQUEfoiAUTORIZADOIT {

    /** Loja propria, so deste teste: a contagem nao pode depender do que outro IT deixou. */
    private static final long LOJA = 9091L;

    @Autowired private RetencaoDaFila retencao;
    @Autowired private ConfigPlataformaRepository configs;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void semear() {
        apagarChaves();
        jdbc.update("delete from evento_outbox where loja_id = ?", LOJA);

        // Quatro eventos, nas quatro combinacoes de (idade x publicado). So UM deles pode morrer em
        // cada limpeza, e e isso que os testes abaixo conferem.
        evento("velho-pendente",   100, false);
        evento("velho-publicado",  100, true);
        evento("novo-pendente",      1, false);
        evento("novo-publicado",     1, true);
    }

    @AfterEach
    void limpar() {
        apagarChaves();
        jdbc.update("delete from evento_outbox where loja_id = ?", LOJA);
    }

    private void apagarChaves() {
        configs.findById(RetencaoDaFila.CHAVE_DIAS).ifPresent(configs::delete);
        configs.findById(RetencaoDaFila.CHAVE_DIAS_PENDENTES).ifPresent(configs::delete);
    }

    private void evento(String tipo, int diasDeIdade, boolean publicado) {
        jdbc.update("""
                insert into evento_outbox
                  (tipo, versao_schema, loja_id, agregado_tipo, agregado_id, payload,
                   ocorrido_em, data_operacao, criado_em, publicado_em)
                values (?, '1.0.0', ?, 'pedido', 1, '{}'::jsonb,
                        now(), current_date,
                        now() - make_interval(days => ?),
                        case when ? then now() - make_interval(days => ?) else null end)
                """, tipo, LOJA, diasDeIdade, publicado, diasDeIdade);
    }

    private void chave(String nome, String valor) {
        ConfigPlataforma c = configs.findById(nome).orElseGet(() -> {
            ConfigPlataforma nova = new ConfigPlataforma();
            nova.setChave(nome);
            return nova;
        });
        c.setValor(valor);
        c.setAtualizadoEm(java.time.OffsetDateTime.now());
        configs.save(c);
    }

    private boolean existe(String tipo) {
        Long n = jdbc.queryForObject(
                "select count(*) from evento_outbox where loja_id = ? and tipo = ?", Long.class, LOJA, tipo);
        return n != null && n > 0;
    }

    // ===================================================================== a premissa

    // SEM ISTO, todo teste de "nao apagou o que nao devia" passaria com um DELETE que nao apaga
    // nada -- e a retencao seria um botao morto, com a suite verde.
    @Test
    void premissa_oSQLrodaEapagaOqueFOIautorizado() {
        chave(RetencaoDaFila.CHAVE_DIAS, "30");

        Map<String, Object> r = retencao.limpar();

        assertEquals(1L, r.get("publicadosApagados"),
                "o DELETE nao apagou o evento publicado de 100 dias");
        assertFalse(existe("velho-publicado"));
    }

    // ===================================================================== o que NAO pode morrer

    @Test
    void aCHAVEdePUBLICADOSnaoTOCAnoQUEninguemLEU() {
        // Hoje NADA publica a fila: tudo o que esta la e "nao publicado". Se esta limpeza pegasse
        // pendente, ligar a chave mais inofensiva das duas apagaria a fila INTEIRA.
        chave(RetencaoDaFila.CHAVE_DIAS, "30");

        retencao.limpar();

        assertTrue(existe("velho-pendente"),
                "a limpeza de PUBLICADOS apagou um evento que ninguem leu");
        assertTrue(existe("novo-pendente"));
        assertTrue(existe("novo-publicado"), "apagou um evento publicado de 1 dia: a idade inverteu");
    }

    @Test
    void aCHAVEdePENDENTESnaoTOCAnoQUEehRECENTE() {
        chave(RetencaoDaFila.CHAVE_DIAS_PENDENTES, "30");

        Map<String, Object> r = retencao.limpar();

        assertEquals(1L, r.get("pendentesApagados"));
        assertFalse(existe("velho-pendente"));
        assertTrue(existe("novo-pendente"), "apagou o pendente de 1 dia");
        assertTrue(existe("velho-publicado"), "a chave de PENDENTES apagou um publicado");
    }

    @Test
    void semCHAVEnenhumaObancoNAOperdeUMAlinha() {
        Map<String, Object> r = retencao.limpar();

        assertEquals(0L, r.get("publicadosApagados"));
        assertEquals(0L, r.get("pendentesApagados"));
        for (String tipo : new String[]{"velho-pendente", "velho-publicado", "novo-pendente", "novo-publicado"}) {
            assertTrue(existe(tipo), "sem retencao definida, " + tipo + " foi apagado");
        }
    }

    @Test
    void asDUASchavesJUNTASapagamOSdoisVELHOSeSOeles() {
        chave(RetencaoDaFila.CHAVE_DIAS, "30");
        chave(RetencaoDaFila.CHAVE_DIAS_PENDENTES, "30");

        Map<String, Object> r = retencao.limpar();

        assertEquals(1L, r.get("publicadosApagados"));
        assertEquals(1L, r.get("pendentesApagados"));
        assertFalse(existe("velho-pendente"));
        assertFalse(existe("velho-publicado"));
        assertTrue(existe("novo-pendente"));
        assertTrue(existe("novo-publicado"));
    }

    @Test
    void aFRONTEIRAdaIDADEehOdiaQUEpassou() {
        // Teto de 200 dias com eventos de 100: nada vence. Fronteira na direcao que importa -- se
        // a comparacao estivesse invertida, ESTE teste e o que pega.
        chave(RetencaoDaFila.CHAVE_DIAS, "200");
        chave(RetencaoDaFila.CHAVE_DIAS_PENDENTES, "200");

        Map<String, Object> r = retencao.limpar();

        assertEquals(0L, r.get("publicadosApagados"));
        assertEquals(0L, r.get("pendentesApagados"));
        assertTrue(existe("velho-publicado"));
        assertTrue(existe("velho-pendente"));
    }

    // ===================================================================== o teto por execucao

    @Test
    void oTETOporEXECUCAOvaleDEverdade() {
        // O `limit` esta dentro de uma subconsulta por `ctid`. Se ele nao estiver limitando, um
        // DELETE pega a tabela inteira de uma vez -- lock e WAL por minutos, no mesmo banco onde as
        // lojas estao vendendo. Com LOTE+5 linhas velhas, uma execucao tem de parar no LOTE.
        for (int i = 0; i < RetencaoDaFila.LOTE + 5; i++) evento("enxurrada", 100, false);
        chave(RetencaoDaFila.CHAVE_DIAS_PENDENTES, "30");

        Map<String, Object> primeira = retencao.limpar();

        assertEquals((long) RetencaoDaFila.LOTE, primeira.get("pendentesApagados"),
                "a primeira execucao nao parou no teto");
        // O que sobrou sai na execucao seguinte: 5 da enxurrada + o velho-pendente do cenario base.
        assertEquals(6L, retencao.limpar().get("pendentesApagados"));
        assertFalse(existe("enxurrada"));
    }

    // ===================================================================== a tela

    @Test
    void oESTADOcontaAfilaEaIDADEdoMAISantigo() {
        Map<String, Object> m = retencao.estado();

        // Numeros da tabela inteira (outros ITs tambem gravam), por isso a asercao e por limite
        // inferior: o que importa e que a contagem FUNCIONA e nao vem nula.
        assertNotNull(m.get("naFila"), "a tela ficaria sem saber o tamanho da fila");
        assertTrue(((Number) m.get("naFila")).longValue() >= 4);
        assertNotNull(m.get("pendentes"));
        assertTrue(((Number) m.get("maisAntigoDias")).longValue() >= 99,
                "o evento de 100 dias nao foi visto como o mais antigo");
        assertNull(m.get("retencaoDias"), "sem chave, a tela tem de mostrar 'nunca apaga'");
    }

    @Test
    void oINDICEdaRETENCAOexiste() {
        String def = jdbc.queryForObject(
                "select indexdef from pg_indexes where indexname = 'ix_outbox_criado_em'", String.class);

        assertNotNull(def, "sem o indice, a limpeza varre a fila inteira toda madrugada");
        assertTrue(def.contains("criado_em"), def);
    }
}
