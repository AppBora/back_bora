package br.com.bora.repository;

import jakarta.persistence.EntityManager;

/**
 * Garante que as lojas de um teste de integracao EXISTEM na tabela {@code loja}.
 *
 * <h2>Por que isto passou a ser necessario (item 6 da secao 4, 20/11/2026)</h2>
 * <p>Ate a V82, 23 tabelas tinham {@code loja_id} e <b>nenhuma chave estrangeira</b> para
 * {@code loja}: o banco aceitava, calado, produto, pedido ou cliente apontando para uma loja que nao
 * existe. Os testes de integracao viviam desse buraco -- cada um escolhia um id alto
 * ({@code 9007}, {@code 9301}, ...), inseria os filhos e nunca criava a mae.</p>
 *
 * <p><b>Isso nao era detalhe de fixture.</b> Era o mesmo buraco de producao aparecendo nos testes: um
 * {@code loja_id} que nao existe nao devia ser gravavel em lugar nenhum. Com as FKs da V82, os
 * testes passaram a ter de dizer a verdade -- e e por isso que o conserto certo e <b>criar a loja</b>,
 * nao afrouxar a chave.</p>
 *
 * <h2>Por que com ID EXPLICITO, e nao {@code returning id}</h2>
 * <p>Os ids sao constantes espalhadas por cada classe de teste ({@code LOJA}, {@code OUTRA_LOJA}) e
 * aparecem em asercoes. Trocar por id sorteado obrigaria a reescrever os 14 arquivos inteiros; com
 * {@code insert ... on conflict do nothing} o diff e uma linha por classe, e cada teste continua
 * medindo o que media.</p>
 *
 * <p>Os ids usados sao todos &gt;= 9001, de proposito: a sequencia de {@code loja} nao e avancada por
 * um insert com id explicito, entao ficar longe dela evita que uma loja criada por outro teste caia
 * em cima de uma destas.</p>
 *
 * <p>Nada precisa ser limpado: {@code @DataJpaTest} e transacional e desfaz cada teste.</p>
 */
public final class LojaDeTeste {

    private LojaDeTeste() {}

    /**
     * A mesma coisa, para o teste que roda FORA de transacao.
     *
     * <p>Os ITs de concorrencia marcam cada teste com
     * {@code @Transactional(propagation = NOT_SUPPORTED)} -- duas threads disputando a mesma linha
     * nao podem estar dentro da mesma transacao, senao nao ha disputa nenhuma. Ali o
     * {@code EntityManager} recusa {@code executeUpdate} com {@code TransactionRequiredException}, e
     * o {@code JdbcTemplate} resolve porque cada comando dele e a propria transacao.</p>
     *
     * <p>Consequencia: aqui a loja fica GRAVADA (nao ha rollback). E inofensivo -- o banco e
     * descartavel e o {@code on conflict do nothing} torna a repeticao gratuita.</p>
     */
    public static void garantir(org.springframework.jdbc.core.JdbcTemplate jdbc, Long... ids) {
        for (Long id : ids) {
            if (id == null) continue;
            jdbc.update("insert into loja (id, nome, ativo) values (?, ?, true) "
                        + "on conflict (id) do nothing", id, "Loja de teste " + id);
        }
    }

    /** Cria as lojas que faltam, com nome e `ativo = true`. Repetir e inofensivo. */
    public static void garantir(EntityManager em, Long... ids) {
        for (Long id : ids) {
            if (id == null) continue;
            em.createNativeQuery(
                            "insert into loja (id, nome, ativo) values (?1, ?2, true) "
                            + "on conflict (id) do nothing")
                    .setParameter(1, id)
                    .setParameter(2, "Loja de teste " + id)
                    .executeUpdate();
        }
    }
}
