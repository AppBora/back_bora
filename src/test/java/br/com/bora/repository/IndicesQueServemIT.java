package br.com.bora.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Os índices do bloco 4 da varredura: o que saiu, o que entrou, e as duas consultas reescritas.
 *
 * <h2>Por que com carga sintética, e não na produção</h2>
 * <p>Os números da varredura para estes itens <b>não são da produção</b> — ela tem 5 lojas, 32 pedidos
 * e 46 itens de pedido, e os índices de {@code pedido_item} têm 16 kB, não 24 MB. "381 ms para achar
 * 338 linhas" e "Seq Scan, 408 ms" vieram de uma carga que o agente montou, e eu não tenho como
 * conferir a dele. Então monto a minha aqui, dentro do teste, e meço com {@code EXPLAIN ANALYZE} no
 * Postgres de verdade.</p>
 *
 * <p>Nessa escala pequena, o Postgres <b>escolhe varredura sequencial e está certo</b> — por isso a
 * carga precisa ser grande o bastante para o índice ganhar. Foi o erro que eu cometi na primeira
 * versão do {@code OLacoDoPainelUsaIndiceIT}: a carga cabia num dia, "hoje" era 13% da tabela, e o
 * planejador preferia varrer. O teste falhava pelo motivo errado.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:55432/bora",
        "spring.datasource.username=postgres",
        "spring.datasource.password=teste",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class IndicesQueServemIT {

    @Autowired private EntityManager em;
    @Autowired private PedidoRepository pedidos;

    /** Grande o bastante para o índice ganhar da varredura sequencial. */
    private static final int QUANTOS = 4000;

    private Long loja;

    @BeforeEach
    @Transactional
    void semear() {
        loja = (Long) em.createNativeQuery(
                        "insert into loja (nome, ativo) values ('Loja do bloco 4', true) returning id")
                .getSingleResult();
        // Espalhado por horas (~166 dias) para a janela de datas ser seletiva de verdade.
        em.createNativeQuery("""
                        insert into pedido (loja_id, status, valor_total, taxa_entrega, forma_pagamento,
                                            entregador, acerto_id, criado_em, atualizado_em, entregue_em,
                                            aguardando_pagamento)
                        select ?1,
                               case when g % 7 = 0 then 'CANCELADO' else 'ENTREGUE' end,
                               30, 5, case when g % 3 = 0 then 'DINHEIRO' else 'PIX' end,
                               case when g % 2 = 0 then 'Joao ' else 'MARIA' end,
                               case when g % 5 = 0 then 1 else null end,
                               now() - (g || ' hours')::interval, now(),
                               now() - (g || ' hours')::interval,
                               case when g % 11 = 0 then true else false end
                          from generate_series(1, ?2) g""")
                .setParameter(1, loja).setParameter(2, QUANTOS).executeUpdate();
        em.createNativeQuery("analyze pedido").executeUpdate();
        em.createNativeQuery("analyze pedido_item").executeUpdate();
    }

    private String plano(String sql) {
        @SuppressWarnings("unchecked")
        List<Object> linhas = em.createNativeQuery("explain (analyze) " + sql).getResultList();
        return linhas.stream().map(String::valueOf).reduce("", (a, b) -> a + "\n" + b);
    }

    private boolean existeIndice(String nome) {
        return ((Number) em.createNativeQuery(
                        "select count(*) from pg_class where relkind = 'i' and relname = ?1")
                .setParameter(1, nome).getSingleResult()).intValue() > 0;
    }

    // =============================================================== 4.6 o que saiu e o que ficou

    @Test
    @Transactional
    void oINDICEduplicadoDEpedido_itemSAIU_eOqueFICOUehOmesmo() {
        // `idx_pedido_item_pedido` (V7) e `idx_pedido_item_loja` (V15) tinham a definicao IDENTICA.
        assertFalse(existeIndice("idx_pedido_item_loja"), "o duplicado tinha que sair");
        assertTrue(existeIndice("idx_pedido_item_pedido"), "e o que ficou tem que continuar la");

        String def = (String) em.createNativeQuery(
                        "select pg_get_indexdef(oid) from pg_class where relname = 'idx_pedido_item_pedido'")
                .getSingleResult();
        assertTrue(def.contains("(loja_id, pedido_id)"),
                "o que ficou tem que cobrir as duas colunas, senao as consultas por pedido perdem "
                        + "o indice. Ficou: " + def);
    }

    @Test
    @Transactional
    void osDOISindicesQueNENHUMAconsultaALCANCAVAsairam() {
        assertFalse(existeIndice("idx_loja_nao_excluida"),
                "indice pela chave primaria com filtro parcial; nenhum SQL filtra excluida_em");
        assertFalse(existeIndice("idx_pedido_bairro_entrega"),
                "o agrupamento por bairro acontece no navegador; nenhum SQL usa a coluna num filtro");
    }

    @Test
    @Transactional
    void oINDICEdaMENSALIDADEganhouADATA() {
        // A varredura chamou de inutil. Nao era: era INCOMPLETO -- a consulta filtra loja E data.
        assertFalse(existeIndice("ix_pagamento_assinatura_loja"));
        assertTrue(existeIndice("ix_pagamento_assinatura_loja_pago_em"));

        String def = (String) em.createNativeQuery(
                        "select pg_get_indexdef(oid) from pg_class "
                                + "where relname = 'ix_pagamento_assinatura_loja_pago_em'")
                .getSingleResult();
        assertTrue(def.contains("(loja_id, pago_em)"), "ficou: " + def);
    }

    @Test
    @Transactional
    void osINDICESsemUSOmasCOMconsultaFICARAM() {
        // A regra que eu apliquei: sai o indice que NENHUMA forma de consulta pode usar, nao o que
        // esta com contador zero numa base de 32 pedidos. Se alguem apagar estes por causa do
        // contador, este teste avisa.
        for (String nome : List.of("idx_pedido_aguardando", "idx_pedido_entregue_em",
                "idx_pedido_loja_status", "idx_pedido_loja_cliente", "idx_pedido_loja")) {
            assertTrue(existeIndice(nome), nome + " casa com uma consulta real do codigo e tem que ficar");
        }
    }

    // =============================================================== 4.7(a) o indice do acerto

    @Test
    @Transactional
    void oACERTOusaINDICE_eNAOvarreAlojaINTEIRA() {
        // O indice antigo era (loja_id, entregador, acerto_id) e desperdicava a coluna do meio: a
        // consulta compara lower(trim(entregador)), e funcao sobre a coluna impede o uso naquela
        // posicao. O novo indexa a EXPRESSAO.
        assertFalse(existeIndice("idx_pedido_acerto"), "o que desperdicava a coluna do meio saiu");
        assertTrue(existeIndice("idx_pedido_acerto_entregador"));

        String p = plano("select * from pedido where loja_id = " + loja
                + " and status = 'ENTREGUE' and acerto_id is null"
                + " and lower(trim(entregador)) = 'joao'"
                + " and entregue_em >= now() - interval '30 days' and entregue_em < now()");

        assertFalse(p.contains("Seq Scan"), "voltou a varrer a tabela. Plano:" + p);
        assertTrue(p.contains("idx_pedido_acerto_entregador"),
                "o plano tem que usar o indice da expressao. Plano:" + p);
    }

    @Test
    @Transactional
    void oINDICEnovoEHaEXPRESSAOqueAconsultaESCREVE() {
        // Se a consulta mudar a forma (tirar o trim, por exemplo) o indice para de servir e nada
        // avisa -- o plano volta a varrer em silencio. Este teste prende as duas pontas.
        String def = (String) em.createNativeQuery(
                        "select pg_get_indexdef(oid) from pg_class "
                                + "where relname = 'idx_pedido_acerto_entregador'")
                .getSingleResult();

        // O Postgres 16 imprime `lower(TRIM(BOTH FROM entregador))`; versoes mais antigas imprimem
        // `lower(btrim(entregador))`. As duas sao a MESMA expressao -- aceitar so uma faria o teste
        // quebrar ao trocar a versao do banco, por um motivo que nao tem nada a ver com o indice.
        String semEspaco = def.replace(" ", "").toLowerCase();
        assertTrue(semEspaco.contains("lower(btrim(entregador))")
                        || semEspaco.contains("lower(trim(bothfromentregador))"),
                "o indice tem que indexar lower(trim(entregador)), que e o que a consulta compara. "
                        + "Ficou: " + def);
        assertTrue(def.contains("acerto_id IS NULL"), "e ser parcial na condicao das duas consultas");
    }

    @Test
    @Transactional
    void aPREVIAdoACERTOcontinuaDANDOoMESMOresultado() {
        // Trocar indice nao pode mudar numero. "Joao " e "MARIA" viram dois grupos, e o trim junta as
        // variantes -- a regra que o javadoc do previaAcerto protege.
        OffsetDateTime inicio = OffsetDateTime.now().minusDays(400);
        OffsetDateTime fim = OffsetDateTime.now().plusDays(1);

        List<Object[]> previa = pedidos.previaAcerto(loja, inicio, fim);

        assertEquals(2, previa.size(), "dois entregadores, nao quatro variantes de texto");
        long total = previa.stream().mapToLong(r -> ((Number) r[1]).longValue()).sum();
        long esperado = ((Number) em.createNativeQuery(
                        "select count(*) from pedido where loja_id = ?1 and status = 'ENTREGUE' "
                                + "and acerto_id is null and entregador is not null")
                .setParameter(1, loja).getSingleResult()).longValue();
        assertEquals(esperado, total, "a soma dos grupos tem que ser o total de entregas nao acertadas");
    }

    // =============================================================== 4.7(b) o ultimo pedido por loja

    @Test
    @Transactional
    void oULTIMOpedidoPORlojaNAOvarreMAISaTABELAinteira() {
        String p = plano("select l.id, (select max(p.criado_em) from pedido p where p.loja_id = l.id) "
                + "from loja l");

        assertFalse(p.contains("Seq Scan on pedido"),
                "a subconsulta por loja tem que virar busca no indice, nao varredura. Plano:" + p);
    }

    @Test
    @Transactional
    void oULTIMOpedidoPORlojaDAoMESMOvalorDEantes() {
        // A forma antiga, para comparar: group by da tabela inteira.
        @SuppressWarnings("unchecked")
        List<Object[]> antiga = em.createNativeQuery(
                        "select loja_id, max(criado_em) from pedido where loja_id = ?1 group by loja_id")
                .setParameter(1, loja).getResultList();
        assertEquals(1, antiga.size(), "o cenario tem que ter pedido, senao nao prova nada");

        var nova = pedidos.ultimoPedidoPorLoja().stream()
                .filter(r -> loja.equals(r[0])).findFirst().orElseThrow();

        assertNotNull(nova[1], "a loja tem pedido: o maximo nao pode vir nulo");
        // A consulta nativa devolve Instant e a do repositorio devolve OffsetDateTime: comparar por
        // instante, nao por tipo.
        assertEquals(0, java.time.Duration.between(
                        ((java.time.OffsetDateTime) nova[1]).toInstant(),
                        (java.time.Instant) antiga.get(0)[1]).toMillis(),
                "a forma nova tem que dar o mesmo instante da antiga");
    }

    @Test
    @Transactional
    void lojaSEMpedidoAPARECEcomNULO_eNAOdesaparece() {
        // Diferenca deliberada em relacao ao group by antigo, que simplesmente nao devolvia essas
        // lojas. O chamador trata nulo como "sem pedido", igual a ausencia -- mas assim a tela recebe
        // uma linha por loja.
        Long vazia = (Long) em.createNativeQuery(
                        "insert into loja (nome, ativo) values ('Loja sem venda', true) returning id")
                .getSingleResult();
        em.flush();

        var linha = pedidos.ultimoPedidoPorLoja().stream()
                .filter(r -> vazia.equals(r[0])).findFirst();

        assertTrue(linha.isPresent(), "loja sem pedido tem que aparecer na lista");
        assertNull(linha.get()[1]);
    }

    // =============================================================== 4.7(c) o balancete

    @Test
    @Transactional
    void oBALANCETEemUMAconsultaDAosMESMOSnumerosDAStres() {
        // O que importa aqui NAO e a performance: e nao mudar numero. Se a consulta agrupada
        // divergisse das tres, o painel do dono passaria a mostrar outro faturamento -- e numero que
        // muda sozinho e pior que consulta lenta.
        OffsetDateTime inicio = OffsetDateTime.now().minusDays(400);
        OffsetDateTime fim = OffsetDateTime.now().plusDays(1);

        BigDecimal receitaAntiga = pedidos.somaReceita(loja, inicio, fim);
        long validosAntigo = pedidos.contaPedidosValidos(loja, inicio, fim);
        long canceladosAntigo = pedidos.contaPedidosCancelados(loja, inicio, fim);

        Object[] r = pedidos.balancetePorLoja(List.of(loja), inicio, fim).get(0);

        assertEquals(0, receitaAntiga.compareTo((BigDecimal) r[1]), "a receita tem que ser identica");
        assertEquals(validosAntigo, ((Number) r[2]).longValue(), "os pedidos validos tambem");
        assertEquals(canceladosAntigo, ((Number) r[3]).longValue(), "e os cancelados");
        assertTrue(validosAntigo > 0 && canceladosAntigo > 0,
                "o cenario precisa ter dos dois, senao a comparacao nao prova nada");
    }

    @Test
    @Transactional
    void oBALANCETEexcluiPIXpendente_igualAreceita() {
        // "PIX que ninguem pagou nao e venda" e a regra que ja estava nas tres consultas. A carga tem
        // pedidos com aguardando_pagamento = true de proposito.
        OffsetDateTime inicio = OffsetDateTime.now().minusDays(400);
        OffsetDateTime fim = OffsetDateTime.now().plusDays(1);

        long pendentes = ((Number) em.createNativeQuery(
                        "select count(*) from pedido where loja_id = ?1 and aguardando_pagamento = true "
                                + "and status <> 'CANCELADO'")
                .setParameter(1, loja).getSingleResult()).longValue();
        assertTrue(pendentes > 0, "o cenario precisa ter PIX pendente");

        long naoCancelados = ((Number) em.createNativeQuery(
                        "select count(*) from pedido where loja_id = ?1 and status <> 'CANCELADO'")
                .setParameter(1, loja).getSingleResult()).longValue();
        Object[] r = pedidos.balancetePorLoja(List.of(loja), inicio, fim).get(0);

        assertEquals(naoCancelados - pendentes, ((Number) r[2]).longValue(),
                "o pendente nao pode entrar na contagem de validos");
    }

    @Test
    @Transactional
    void oBALANCETEdeLOJAsemPEDIDOnaJANELAnaoVOLTA() {
        // O chamador trata ausencia como zero, igual a antes. Se isto mudar, o RedeService precisa
        // mudar junto.
        OffsetDateTime antes = OffsetDateTime.now().minusYears(5);

        assertTrue(pedidos.balancetePorLoja(List.of(loja), antes, antes.plusDays(1)).isEmpty());
    }

    @Test
    @Transactional
    void oCENARIOfoiREALMENTEsemeado() {
        // Sem isto, um erro no insert deixaria todos os testes acima passando sobre base vazia -- a
        // pior forma de falhar.
        long quantos = ((Number) em.createNativeQuery(
                        "select count(*) from pedido where loja_id = ?1")
                .setParameter(1, loja).getSingleResult()).longValue();

        assertEquals(QUANTOS, quantos);
    }
}
