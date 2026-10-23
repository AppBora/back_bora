package br.com.bora.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O vigia pergunta pelo <b>processo</b>; o deploy pergunta pelo <b>sistema</b>.
 *
 * <h2>O defeito que isto fecha (item 9.7)</h2>
 * <p>{@code /actuator/health} <b>agrega</b> todos os indicadores, e um deles é o do banco. Quando o
 * RDS pisca, o health inteiro vira DOWN — e dois automatismos liam esse mesmo número para decidir
 * coisas diferentes:</p>
 * <ul>
 *   <li>o <b>vigia</b> reiniciava a API. Reiniciar não conserta banco: a API nova sobe, acha o banco
 *       fora do ar e cai de novo. Enquanto isso, as requisições em voo morrem a cada reinício.</li>
 *   <li>o <b>deploy</b> fazia rollback — de código <b>bom</b>, porque o que estava fora era o banco.
 *       E aí fica parecendo que a versão nova é que tinha defeito.</li>
 * </ul>
 *
 * <h2>A parte que era SUSPEITA na varredura, agora medida</h2>
 * <p>A varredura marcou "**SUSPEITA** sobre o health agregar mesmo". Este teste <b>mede</b>: ele
 * pergunta ao próprio registro de indicadores do Spring quais existem. Em produção o endpoint não
 * mostra detalhes (e não vai mostrar), então a única forma honesta de saber é esta — perguntar à
 * aplicação de verdade, subida de verdade.</p>
 *
 * <p>Como rodar: ver o javadoc do {@link PreviaDoAcertoBateComOPagamentoIT}.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:55432/bora",
        "spring.datasource.username=postgres",
        "spring.datasource.password=teste",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        // Só no teste: em produção o endpoint continua sem detalhes.
        "management.endpoint.health.show-details=always"
})
class OvigiaOlhaOprocessoNaoObancoIT {

    @Autowired private HealthEndpoint health;

    private Set<String> componentesDe(HealthComponent c) {
        assertInstanceOf(CompositeHealth.class, c,
                "esperava um health composto, com componentes nomeados; veio " + c.getClass());
        return ((CompositeHealth) c).getComponents().keySet();
    }

    // =============================================================== a premissa, medida

    @Test
    void oHEALTHcheioAGREGAoBANCO() {
        // A SUSPEITA da varredura, confirmada contra a aplicacao de verdade. Se um dia o Spring parar
        // de registrar o indicador do banco, este teste falha e conta que o item 9.7 perdeu a causa.
        Set<String> componentes = componentesDe(health.health());

        assertTrue(componentes.contains("db"),
                "o health cheio tem que incluir o banco -- e por isso que ele vira DOWN quando o RDS "
                        + "pisca. Achei: " + componentes);
    }

    // =============================================================== o grupo do vigia

    @Test
    void oGRUPOvivaEXISTE() {
        assertNotNull(health.healthForPath("viva"),
                "sem o grupo, o vigia continua lendo o health cheio e reiniciando por causa do banco");
    }

    @Test
    void oGRUPOvivaNAOolhaObanco() {
        // O coracao do conserto: o vigia tem que conseguir perguntar "o processo atende?" sem a
        // resposta depender de terceiro nenhum.
        Set<String> componentes = componentesDe(health.healthForPath("viva"));

        assertFalse(componentes.contains("db"),
                "o grupo do vigia NAO pode depender do banco: reiniciar a API nao conserta banco. "
                        + "Achei: " + componentes);
        assertFalse(componentes.isEmpty(), "grupo vazio nao responde nada");
    }

    @Test
    void oGRUPOvivaRESPONDEqueESTAdePE() {
        assertEquals("UP", health.healthForPath("viva").getStatus().getCode(),
                "com a aplicacao no ar, o grupo do processo tem que dizer UP");
    }

    @Test
    void oGRUPOvivaNAOolhaNENHUMterceiro() {
        // Generaliza: hoje o unico indicador externo registrado e o banco, mas amanha pode entrar
        // outro (Redis, disco, fila). O grupo do vigia nao pode herdar nenhum deles sozinho.
        Set<String> doGrupo = componentesDe(health.healthForPath("viva"));

        assertEquals(Set.of("ping"), doGrupo,
                "o grupo do vigia e so o `ping`, de proposito. Qualquer indicador a mais aqui volta a "
                        + "fazer o vigia reiniciar por problema que reinicio nao resolve. Achei: " + doGrupo);
    }
}
