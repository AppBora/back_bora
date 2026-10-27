package br.com.bora.repository;

import br.com.bora.entity.ConfiguracaoLoja;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uma configuração por loja, e é o BANCO que garante.
 *
 * <h2>O defeito (achado pelo QA em 26/10/2026)</h2>
 * <p>{@code configuracao_loja} tinha PRIMARY KEY só em {@code id} e um índice <b>não-único</b> em
 * {@code loja_id}. Nada impedia duas linhas para a mesma loja.</p>
 *
 * <p>O que a segunda linha derruba: {@link ConfiguracaoLojaRepository#findByLojaId} devolve
 * {@code Optional}. Com duas linhas o Spring Data levanta {@code IncorrectResultSizeDataAccessException}
 * <b>antes</b> do {@code .orElse(null)} — e um dos chamadores é o {@code PublicController} do
 * cardápio. O cardápio público da loja sai do ar com 500, e com ele a única porta por onde o cliente
 * final faz pedido.</p>
 *
 * <p><b>Não estava acontecendo:</b> medido na produção em 27/10/2026, somente leitura, 5 linhas para
 * 5 lojas distintas, zero duplicata. E a corrida que normalmente cria esse tipo de linha <b>não é
 * alcançável hoje</b>: os dois únicos chamadores do {@code semear} criam a loja na linha de cima. A
 * trava escreve a regra no único lugar que ninguém pula — um chamador novo, um pedido repetido, um
 * INSERT feito à mão.</p>
 *
 * <h2>Por que isto é um IT, e o que isso custa</h2>
 * <p>Restrição de unicidade só se prova contra um Postgres de verdade: nem o {@code ddl-auto:
 * validate} do Hibernate nem teste com dublê enxergam constraint. <b>Os ITs não rodam no deploy</b>
 * (ficam fora do {@code mvn package}), então este arquivo é prova de que o conserto funciona, e não
 * vigia permanente. É a mesma limitação registrada na v120.</p>
 *
 * <p><b>Como rodar:</b></p>
 * <pre>
 * docker run -d --name bora-teste-pg -e POSTGRES_PASSWORD=teste -e POSTGRES_DB=bora -p 55432:5432 postgres:16
 * mvn -o test -Dtest='UmaConfiguracaoPorLojaIT' -DfailIfNoTests=false
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
class UmaConfiguracaoPorLojaIT {

    private static final Long LOJA = 90001L;

    @Autowired private EntityManager em;
    @Autowired private ConfiguracaoLojaRepository repo;

    private ConfiguracaoLoja config(Long lojaId, String nome) {
        ConfiguracaoLoja c = new ConfiguracaoLoja();
        c.lojaId = lojaId;
        c.nomeExibicao = nome;
        c.corPrimaria = "#5B1A8B";
        return c;
    }

    // ---------------------------------------------------------------- a trava existe
    @Test
    void existeUNIQUEemLOJAid() {
        @SuppressWarnings("unchecked")
        List<Object[]> travas = em.createNativeQuery("""
                select conname, pg_get_constraintdef(oid)
                  from pg_constraint
                 where conrelid = 'configuracao_loja'::regclass and contype = 'u'
                """).getResultList();

        assertEquals(1, travas.size(),
                "esperava exatamente uma restricao de unicidade, e vieram " + travas.size());
        assertTrue(String.valueOf(travas.get(0)[1]).contains("loja_id"),
                "a unicidade nao e por loja_id: " + travas.get(0)[1]);
    }

    // ---------------------------------------------------------------- e funciona
    @Test
    void oBANCOrecusaAsegundaCONFIGURACAOdaMESMAloja() {
        // O teste que importa: nao e "existe a constraint", e "a linha nao entra".
        repo.saveAndFlush(config(LOJA, "Zira Centro"));

        assertThrows(DataIntegrityViolationException.class,
                () -> repo.saveAndFlush(config(LOJA, "Zira Centro (copia)")),
                "o banco aceitou a segunda configuracao: o cardapio publico desta loja cai com 500");
    }

    @Test
    void comUMAlinhaOcardapioCONTINUAachandoAconfiguracao() {
        // O caminho que o defeito derrubava -- e o que nao pode ter sido quebrado pela trava.
        repo.saveAndFlush(config(LOJA, "Zira Centro"));
        em.clear();

        assertTrue(repo.findByLojaId(LOJA).isPresent());
        assertEquals("Zira Centro", repo.findByLojaId(LOJA).get().nomeExibicao);
    }

    @Test
    void outraLOJAcontinuaPODENDOterAsuaCONFIGURACAO() {
        // Unicidade por loja, nao unicidade global: o erro oposto deixaria a plataforma com uma
        // configuracao so para todos os clientes.
        repo.saveAndFlush(config(LOJA, "Zira Centro"));

        assertDoesNotThrow(() -> repo.saveAndFlush(config(LOJA + 1, "Zira Zona Norte")));
        assertTrue(repo.findByLojaId(LOJA + 1).isPresent());
    }

    // ---------------------------------------------------------------- o indice duplicado saiu
    @Test
    void oINDICEnaoUNICOemLOJAidSAIU() {
        // O UNIQUE cria o seu proprio indice btree em (loja_id): o antigo virou duplicado de
        // definicao. Pela regra que ficou da V56, indice sai quando nenhuma forma de consulta do
        // codigo pode usar ele -- e aqui nao e o contador de uso, e um indice identico ao que nasceu.
        @SuppressWarnings("unchecked")
        List<String> indices = em.createNativeQuery("""
                select indexname from pg_indexes
                 where tablename = 'configuracao_loja' order by indexname
                """).getResultList();

        assertFalse(indices.contains("idx_configuracao_loja"),
                "o indice duplicado voltou: " + indices);
        assertTrue(indices.stream().anyMatch(i -> i.contains("uk_configuracao_loja_loja_id")),
                "o indice da unicidade nao esta la: " + indices);
    }

    @Test
    void aBUSCAporLOJAidAINDAusaINDICE() {
        // Tirar o indice antigo nao pode ter deixado a busca do cardapio em varredura sequencial.
        // Numa tabela de 5 linhas o Postgres escolhe sequencial de qualquer jeito -- por isso o
        // `enable_seqscan = off`: a pergunta aqui e "EXISTE indice utilizavel?", nao "ele seria
        // escolhido hoje?". Foi a licao da V56: contador em zero diz que a tabela e pequena.
        em.createNativeQuery("set enable_seqscan = off").executeUpdate();
        String plano = String.valueOf(em.createNativeQuery(
                "explain select * from configuracao_loja where loja_id = 1").getResultList().get(0));

        assertTrue(plano.contains("Index") && plano.contains("uk_configuracao_loja_loja_id"),
                "a busca por loja_id ficou sem indice utilizavel. Plano: " + plano);
    }
}
