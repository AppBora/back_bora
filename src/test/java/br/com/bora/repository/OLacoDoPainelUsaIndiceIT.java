package br.com.bora.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * As consultas do laço do painel usam índice, e não varrem a loja inteira.
 *
 * <h2>Por que isto precisa ser um teste contra banco de verdade</h2>
 * <p>Índice é promessa, não garantia: quem decide usar é o planejador, e ele decide pelo que a tabela
 * tem. Um índice entregue e ignorado é pior que nenhum — ocupa espaço, pesa em cada gravação e dá a
 * impressão de que o problema foi resolvido.</p>
 *
 * <p>E foi exatamente o que quase aconteceu aqui: um índice parcial
 * {@code where status not in ('ENTREGUE','CANCELADO')} servia a consulta do KDS em 0,23 ms com
 * valores literais, mas o planejador <b>não consegue provar</b> que {@code status not in ($1,$2)} —
 * como o Hibernate manda — implica aquele predicado quando o plano é genérico. Medido: 15,9 ms. Por
 * isso a consulta passou a usar lista positiva, servida pelo índice comum.</p>
 *
 * <p>Este teste carrega pedidos de verdade e lê o plano do Postgres. Sem carga, o planejador escolhe
 * varredura mesmo com índice — e com razão, porque varrer 32 linhas é mais rápido.</p>
 *
 * <p><b>Como rodar:</b> precisa de um Postgres. Com Docker:</p>
 * <pre>
 * docker run -d --name bora-teste-pg -e POSTGRES_PASSWORD=teste -e POSTGRES_DB=bora -p 55432:5432 postgres:16
 * mvn -o test -Dtest='*IT' -DfailIfNoTests=false
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
class OLacoDoPainelUsaIndiceIT {

    /** Uma loja só deste teste, para não misturar com o que as outras classes carregam. */
    private static final long LOJA = 9100L;
    private static final int QUANTOS = 6000;

    @Autowired private EntityManager em;

    @BeforeEach
    void carregar() {
        em.createNativeQuery("delete from pedido where loja_id = " + LOJA).executeUpdate();
        em.createNativeQuery("insert into loja (id, nome) values (" + LOJA + ", 'Loja do teste de indice') "
                + "on conflict do nothing").executeUpdate();
        // Distribuicao REALISTA: pedido nao fica aberto para sempre. Quase tudo ja encerrou e um
        // punhado esta ativo agora. Com a distribuicao irreal (1/6 em cada status) o ganho do indice
        // aparece MENOR do que e, porque contar mil linhas custa mesmo com indice.
        //
        // E espalhada por HORAS, nao por minutos, para cobrir uns 250 dias. Na primeira versao
        // deste teste a carga cobria 4 dias, "hoje" era 13% da tabela, e o planejador varria de
        // proposito -- com razao, porque ler 13% de uma tabela e mais barato por varredura que por
        // indice. O teste reprovava por um motivo que NAO era o defeito. Uma loja com meses de
        // historico tem "hoje" em menos de 1%.
        em.createNativeQuery("""
                insert into pedido (loja_id, codigo, status, valor_total, origem, criado_em, atualizado_em, entregue_em)
                select ?1, 'T'||g,
                       case when g <= ?2 - 30 then (array['ENTREGUE','CANCELADO'])[1 + (g % 2)]
                            when g <= ?2 - 20 then 'EM_PREPARO'
                            when g <= ?2 - 10 then 'SAIU_PARA_ENTREGA'
                            else 'RECEBIDO' end,
                       30, 'Balcao', now() - (g || ' hours')::interval, now(),
                       case when g <= ?2 - 30 and g % 2 = 1 then now() - (g || ' hours')::interval else null end
                  from generate_series(1, ?2) g""")
                .setParameter(1, LOJA).setParameter(2, QUANTOS).executeUpdate();
        em.createNativeQuery("analyze pedido").executeUpdate();
    }

    /** O plano do Postgres para a consulta, em uma string. */
    private String plano(String sql) {
        @SuppressWarnings("unchecked")
        List<Object> linhas = em.createNativeQuery("explain (analyze) " + sql).getResultList();
        return linhas.stream().map(String::valueOf).reduce("", (a, b) -> a + "\n" + b);
    }

    private void exigirIndice(String oQue, String sql) {
        String p = plano(sql);
        assertFalse(p.contains("Seq Scan"),
                oQue + " está varrendo a tabela inteira. O painel recarrega a cada 6 a 15 segundos, "
                        + "por loja e por aba aberta. Plano:\n" + p);
        assertTrue(p.contains("Index"), oQue + " não usou índice. Plano:\n" + p);
    }

    // ------------------------------------------------------------------ as três contagens

    @Test
    void aContagemDeEMPREPAROusaIndice() {
        exigirIndice("a contagem de 'em preparo' (Dashboard, 8 s)",
                "select count(*) from pedido where loja_id = " + LOJA + " and status = 'EM_PREPARO'");
    }

    @Test
    void aContagemDeSAIUPARAENTREGAusaIndice() {
        exigirIndice("a contagem de 'saiu para entrega' (Dashboard, 8 s)",
                "select count(*) from pedido where loja_id = " + LOJA + " and status = 'SAIU_PARA_ENTREGA'");
    }

    @Test
    void aContagemDeENTREGUESnaUltimaHoraUsaIndice() {
        exigirIndice("a contagem de 'entregues na última hora' (Dashboard, 8 s)",
                "select count(*) from pedido where loja_id = " + LOJA
                        + " and entregue_em > now() - interval '1 hour'");
    }

    @Test
    void asDUAScontagensPorStatusNaoTocamNaTABELA() {
        // Index Only Scan: a resposta sai do índice, sem ler a tabela. É o melhor caso, e o que
        // justifica o índice por status existir.
        String p = plano("select count(*) from pedido where loja_id = " + LOJA
                + " and status = 'EM_PREPARO'");
        assertTrue(p.contains("Index Only Scan"),
                "a contagem por status deveria sair só do índice. Plano:\n" + p);
    }

    // ------------------------------------------------------------------ o quadro da cozinha

    @Test
    void aListaDeABERTOSusaIndice_naFormaPOSITIVA() {
        // É a consulta que o KDS roda a cada 6 segundos. A forma positiva é servida pelo índice comum.
        exigirIndice("a lista de pedidos abertos (KDS, 6 s)",
                "select * from pedido where loja_id = " + LOJA
                        + " and status in ('RECEBIDO','CONFIRMADO','EM_PREPARO','PRONTO','SAIU_PARA_ENTREGA')"
                        + " order by criado_em desc");
    }

    @Test
    void aFormaPOSITIVAfuncionaComPARAMETRO_eNAOsoComLiteral() {
        // O Hibernate manda `status in (?,?,?,?,?)`, nao os valores no SQL. Um indice que so serve
        // com literais e um indice que nunca e usado em producao -- foi o caso do indice parcial que
        // eu cheguei a medir antes de descartar.
        //
        // Precisa de JDBC cru: `prepare` nao passa pelo createNativeQuery do Hibernate (a primeira
        // versao deste teste quebrou com SQLGrammarException nisso).
        em.unwrap(org.hibernate.Session.class).doWork(conexao -> {
            try (var st = conexao.createStatement()) {
                st.execute("deallocate all");
                st.execute("prepare abertos(text,text,text,text,text) as "
                        + "select * from pedido where loja_id = " + LOJA
                        + " and status in ($1,$2,$3,$4,$5) order by criado_em desc");
                // Seis execucoes: o Postgres troca para plano generico depois da quinta, e e ai que
                // um indice fragil deixa de ser usado.
                for (int i = 1; i <= 6; i++) {
                    StringBuilder plano = new StringBuilder();
                    try (var rs = st.executeQuery("explain (analyze) execute abertos("
                            + "'RECEBIDO','CONFIRMADO','EM_PREPARO','PRONTO','SAIU_PARA_ENTREGA')")) {
                        while (rs.next()) plano.append(System.lineSeparator()).append(rs.getString(1));
                    }
                    assertFalse(plano.toString().contains("Seq Scan"),
                            "execucao " + i + " com parametro voltou a varrer a tabela. Plano:" + plano);
                    assertTrue(plano.toString().contains("Index"),
                            "execucao " + i + " nao usou indice. Plano:" + plano);
                }
            }
        });
    }

    @Test
    void oQUADRODODIAcontinuaUsandoOindiceQueJAexistia() {
        // idx_pedido_loja (loja_id, criado_em DESC). Não era o problema, e não pode ter sido quebrado.
        exigirIndice("os pedidos do dia (quadro do balcão)",
                "select * from pedido where loja_id = " + LOJA
                        + " and criado_em >= now()::date and criado_em < (now()::date + 1)"
                        + " order by criado_em desc");
    }

    // ------------------------------------------------------------------ a carga é de verdade

    @Test
    void oTESTEestaREALMENTEcomCargaEcomOsIndices() {
        // Sem carga o planejador varre de propósito, e os testes acima falhariam por um motivo que não
        // é o defeito. Sem os índices, falhariam por outro. Isto separa os dois.
        Object quantos = em.createNativeQuery("select count(*) from pedido where loja_id = " + LOJA)
                .getSingleResult();
        assertEquals(QUANTOS, ((Number) quantos).intValue(), "a carga do teste não entrou");

        @SuppressWarnings("unchecked")
        List<Object> indices = em.createNativeQuery("""
                select indexname from pg_indexes where tablename = 'pedido'
                 and indexname in ('idx_pedido_loja_status','idx_pedido_entregue_em')""")
                .getResultList();
        assertEquals(2, indices.size(), "os índices da V54 não estão no banco: " + indices);
    }
}
