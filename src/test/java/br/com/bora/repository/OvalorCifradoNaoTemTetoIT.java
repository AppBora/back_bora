package br.com.bora.repository;

import br.com.bora.config.SegredoGuardado;
import br.com.bora.entity.ConfigPlataforma;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O valor da config da plataforma cabe cifrado, por mais longo que ele seja.
 *
 * <h2>O defeito (achado pelo QA em 26/10/2026, remedido em 27/10)</h2>
 * <p>{@code config_plataforma.valor} era {@code varchar(255)} e passa pelo cifrador
 * {@link SegredoGuardado}, que grava {@code enc:v1:} + base64(nonce 12B || cifra || tag 16B). O
 * cifrado <b>cresce</b>: para um texto puro de L caracteres ASCII, o tamanho e
 * {@code 7 + ceil((L + 28) / 3) * 4}. Em {@code varchar(255)} o maior texto puro que ainda cabe
 * cifrado tem <b>158 caracteres</b>; acima disso o Postgres recusa e a cifragem falha.</p>
 *
 * <h2>O que eu medi, e o alarme que eu tinha dado errado</h2>
 * <p>Na producao, em 27/10/2026, os cinco valores desta tabela tem 5, 13, 19, 36 e 99 caracteres. O
 * maior — o {@code client-secret} do iFood, 99 — cifra para 179 e <b>cabe</b>. Ligar o
 * {@code BORA_SEGREDO_COLUNAS} hoje <b>nao falharia</b>, e eu havia dito ao dono que isso bloqueava a
 * tarefa dele. Era alarme mais forte que o fato. A armadilha e real e nao esta armada: 99 de teto 158
 * e dois tercos do caminho.</p>
 *
 * <h2>Por que `text`</h2>
 * <p>E a regra que o sistema ja segue, e eu conferi as cinco outras colunas que passam pelo mesmo
 * cifrador — {@code integracao_canal.client_secret}, {@code webhook_token}, {@code access_token},
 * {@code refresh_token} e {@code loja.asaas_api_key} — todas {@code text}. Esta era a unica com
 * limite, e por isso a unica que podia falhar.</p>
 *
 * <p><b>Como rodar:</b> precisa do Postgres de teste.</p>
 * <pre>
 * docker run -d --name bora-teste-pg -e POSTGRES_PASSWORD=teste -e POSTGRES_DB=bora -p 55432:5432 postgres:16
 * mvn -o test -Dtest='OvalorCifradoNaoTemTetoIT' -DfailIfNoTests=false
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
class OvalorCifradoNaoTemTetoIT {

    private static final String CHAVE = "credencial.teste.teto";

    @Autowired private EntityManager em;
    @Autowired private ConfigPlataformaRepository configs;

    @BeforeEach
    void montar() {
        SegredoGuardado.usarChave(null);
    }

    /**
     * Limpa o que o teste gravou.
     *
     * <p>E {@code @Transactional}, e por isso TODO teste desta classe tambem e -- inclusive os que
     * so leem o esquema. Sem isso o {@code delete} daqui estoura com
     * {@code TransactionRequiredException} nos testes nao-transacionais, e o relatorio mostra erro
     * num conserto que esta certo. Foi o que aconteceu na primeira execucao.</p>
     */
    @AfterEach
    @Transactional
    void limpar() {
        SegredoGuardado.usarChave(null);
        em.createNativeQuery("delete from config_plataforma where chave like 'credencial.teste.teto%'")
                .executeUpdate();
    }

    private void gravar(String chave, String valor) {
        ConfigPlataforma c = new ConfigPlataforma();
        c.setChave(chave);
        c.setValor(valor);
        configs.save(c);
        em.flush();
        em.clear();
    }

    /** O valor como o pg_dump o veria: sem passar pelo conversor. */
    private String cru(String chave) {
        List<?> l = em.createNativeQuery("select valor from config_plataforma where chave = ?1")
                .setParameter(1, chave).getResultList();
        return l.isEmpty() ? null : (String) l.get(0);
    }

    /** O tamanho cifrado previsto pela conta, para o teste cobrar o numero e nao so "funcionou". */
    private static int tamanhoCifradoPrevisto(int tamanhoDoTextoPuro) {
        int bytes = 12 + tamanhoDoTextoPuro + 16;
        return 7 + (int) Math.ceil(bytes / 3.0) * 4;
    }

    // ---------------------------------------------------------------- a coluna
    @Test
    @Transactional
    void aCOLUNAnaoTEMlimiteDEtamanho() {
        Object limite = em.createNativeQuery("""
                select character_maximum_length from information_schema.columns
                 where table_name = 'config_plataforma' and column_name = 'valor'
                """).getSingleResult();

        assertNull(limite,
                "a coluna voltou a ter teto (" + limite + "): acima de 158 caracteres de texto puro "
                        + "a cifragem passa a falhar");
    }

    @Test
    @Transactional
    void TODAcolunaCIFRADAdoSISTEMAehSEMlimite() {
        // A regra, cobrada nas seis de uma vez: quem passa pelo SegredoGuardado nao pode ter teto.
        // Uma coluna nova cifrada que nasca varchar reprova aqui.
        @SuppressWarnings("unchecked")
        List<Object[]> comTeto = em.createNativeQuery("""
                select table_name, column_name, character_maximum_length
                  from information_schema.columns
                 where character_maximum_length is not null
                   and ((table_name = 'config_plataforma' and column_name = 'valor')
                     or (table_name = 'integracao_canal'
                         and column_name in ('client_secret','webhook_token','access_token','refresh_token'))
                     or (table_name = 'loja' and column_name = 'asaas_api_key'))
                """).getResultList();

        assertTrue(comTeto.isEmpty(), () -> "coluna cifrada com teto: " + comTeto.stream()
                .map(r -> r[0] + "." + r[1] + "(" + r[2] + ")").toList());
    }

    // ---------------------------------------------------------------- o caso que falhava
    @Test
    @Transactional
    void valorDE200caracteresCABEcifrado() {
        // 200 caracteres cifram para 291: estourava varchar(255) e o INSERT era recusado.
        SegredoGuardado.usarChave("chave-de-teste-do-it");
        String segredo = "s".repeat(200);

        assertDoesNotThrow(() -> gravar(CHAVE, segredo));

        String gravado = cru(CHAVE);
        assertTrue(gravado.startsWith("enc:v1:"), "nao foi cifrado: " + gravado);
        assertEquals(tamanhoCifradoPrevisto(200), gravado.length(),
                "o tamanho cifrado saiu diferente da conta documentada na migracao");
        assertTrue(gravado.length() > 255, "este teste precisa de um valor que NAO cabia antes");
    }

    @Test
    @Transactional
    void eELEvoltaINTEIROnaLEITURA() {
        // Cifrar e caber nao basta: tem de voltar igual, senao a credencial do iFood volta truncada e
        // a plataforma para de autorizar pedido de todas as lojas.
        SegredoGuardado.usarChave("chave-de-teste-do-it");
        String segredo = "x".repeat(199) + "F";

        gravar(CHAVE, segredo);

        assertEquals(segredo, configs.findById(CHAVE).orElseThrow().getValor());
    }

    @Test
    @Transactional
    void oTETOde158FICAprovado_eEHporQUEaCOLUNAmudou() {
        // O numero da conta, medido nas duas pontas: 158 cifra para exatamente 255 e cabia; 159 passa
        // de 255 e nao cabia. E o que tornava o varchar(255) uma armadilha, nao um limite folgado.
        assertEquals(255, tamanhoCifradoPrevisto(158));
        assertEquals(259, tamanhoCifradoPrevisto(159));

        SegredoGuardado.usarChave("chave-de-teste-do-it");
        gravar(CHAVE + ".159", "y".repeat(159));
        assertEquals(259, cru(CHAVE + ".159").length(),
                "a conta da migracao nao bate com o que o cifrador produz");
    }

    // ---------------------------------------------------------------- o que nao pode mudar
    @Test
    @Transactional
    void semCHAVEcontinuaGRAVANDOemTEXTOpuro() {
        // E o estado da producao: ligar a chave nao pode ser pre-requisito para gravar.
        gravar(CHAVE, "segredo-em-claro");
        assertEquals("segredo-em-claro", cru(CHAVE));
    }

    @Test
    @Transactional
    void oVALORcurtoCONTINUAcurtoNObanco() {
        // Tirar o teto nao pode ter mudado o formato: `text` e varchar guardam igual, e o cifrado de
        // um valor curto continua curto.
        SegredoGuardado.usarChave("chave-de-teste-do-it");
        gravar(CHAVE, "abc");
        assertEquals(tamanhoCifradoPrevisto(3), cru(CHAVE).length());
    }

    @Test
    @Transactional
    void aCHAVEdaTABELAcontinuaLIMITADA() {
        // Só o VALOR perdeu o teto. A chave é nome de configuração, escrito por nós, e 60 basta —
        // tirar o limite dela seria afrouxar sem motivo.
        Object limite = em.createNativeQuery("""
                select character_maximum_length from information_schema.columns
                 where table_name = 'config_plataforma' and column_name = 'chave'
                """).getSingleResult();
        assertEquals(60, ((Number) limite).intValue());
    }
}
