package br.com.bora.repository;

import br.com.bora.config.SegredoGuardado;
import br.com.bora.entity.ConfigPlataforma;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import br.com.bora.service.CifragemEmRepousoService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * A credencial sai do banco cifrada — e é isso que decide o que vai no {@code pg_dump}.
 *
 * <h2>Por que contra Postgres de verdade</h2>
 * <p>O item 8.4 é sobre o que está <b>gravado</b>. Teste com mock prova que o serviço chamou um
 * update; ele não prova o que ficou na coluna, e o que vai para o backup é o que ficou na coluna.
 * Aqui a conferência é por SQL cru, olhando o valor como o {@code pg_dump} o veria.</p>
 *
 * <h2>Os dois comportamentos do cifrador, os dois medidos</h2>
 * <p>Sem a chave, grava e lê em texto puro — é o estado da produção em 22/10/2026, e é por isso que o
 * item 8.4 existe. Com a chave, grava {@code enc:v1:...} e lê de volta em claro. O serviço desta
 * classe é o que fecha a lacuna entre os dois: ligar a chave <b>não</b> cifra o que já estava lá.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:55432/bora",
        "spring.datasource.username=postgres",
        "spring.datasource.password=teste",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class CifragemEmRepousoIT {

    @Autowired private EntityManager em;
    @Autowired private ConfigPlataformaRepository configs;

    private CifragemEmRepousoService servico;

    private static final String CHAVE = "credencial.teste.it";

    @BeforeEach
    void montar() {
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.atual()).thenReturn(new BoraPrincipal(1L, null, "ADMINISTRADOR_BORA", "bora@bora.com"));
        servico = new CifragemEmRepousoService(em, ctx);
        SegredoGuardado.usarChave(null); // comeca desligado, como a producao
    }

    @AfterEach
    @Transactional
    void limpar() {
        SegredoGuardado.usarChave(null);
        em.createNativeQuery("delete from config_plataforma where chave = ?1")
                .setParameter(1, CHAVE).executeUpdate();
    }

    /** O valor como o pg_dump o veria: sem passar pelo conversor. */
    private String cru() {
        var lista = em.createNativeQuery("select valor from config_plataforma where chave = ?1")
                .setParameter(1, CHAVE).getResultList();
        return lista.isEmpty() ? null : (String) lista.get(0);
    }

    private void gravar(String valor) {
        ConfigPlataforma c = new ConfigPlataforma();
        c.setChave(CHAVE);
        c.setValor(valor);
        configs.save(c);
        em.flush();
        em.clear();
    }

    // =============================================================== o estado de hoje

    @Test
    @Transactional
    void SEMaCHAVE_aCREDENCIALfICAemTEXTOpuro() {
        // E o estado da producao em 22/10/2026 (BORA_SEGREDO_COLUNAS nao definida), e e o motivo do
        // item 8.4: este valor sai legivel em todo dump.
        gravar("segredo-do-ifood-123");

        assertEquals("segredo-do-ifood-123", cru(),
                "sem chave o cifrador grava em texto puro -- de proposito, para ligar a chave nao "
                        + "exigir migracao");
    }

    // =============================================================== com a chave

    @Test
    @Transactional
    void COMaCHAVE_oQUEeGRAVADOsaiCIFRADO() {
        SegredoGuardado.usarChave("chave-de-teste-do-it");

        gravar("segredo-do-ifood-123");

        assertTrue(cru().startsWith("enc:v1:"), "o que ficou na coluna foi: " + cru());
        assertFalse(cru().contains("segredo-do-ifood"), "o valor em claro nao pode aparecer");
    }

    @Test
    @Transactional
    void oQUEeCIFRADOvoltaEMclaroPELOjpa() {
        // Se a volta nao funcionasse, a integracao pararia de autenticar no iFood -- pior que o furo.
        SegredoGuardado.usarChave("chave-de-teste-do-it");
        gravar("segredo-do-ifood-123");

        assertEquals("segredo-do-ifood-123", configs.findById(CHAVE).orElseThrow().getValor());
    }

    // =============================================================== a lacuna que o servico fecha

    @Test
    @Transactional
    void ligarACHAVEnaoCIFRAoQUEjaESTAVAgravado() {
        // A premissa do item 8.4, provada: por isso o servico precisa existir. Se este teste parar de
        // falhar... quer dizer que o cifrador passou a cifrar na leitura, e o servico ficou obsoleto.
        gravar("segredo-antigo-em-claro");
        assertEquals("segredo-antigo-em-claro", cru());

        SegredoGuardado.usarChave("chave-de-teste-do-it");

        assertEquals("segredo-antigo-em-claro", cru(),
                "ligar a chave NAO mexe no que ja estava no banco -- e e por isso que o "
                        + "CifragemEmRepousoService existe");
    }

    @Test
    @Transactional
    void oSERVICOcifraOqueJAestavaGRAVADO() {
        gravar("segredo-antigo-em-claro");
        SegredoGuardado.usarChave("chave-de-teste-do-it");

        var r = servico.cifrarOqueJaEstaGravado();

        assertTrue(((Number) r.get("total")).intValue() >= 1, "tinha que cifrar ao menos um");
        assertTrue(cru().startsWith("enc:v1:"), "ficou: " + cru());
        assertEquals("segredo-antigo-em-claro", configs.findById(CHAVE).orElseThrow().getValor(),
                "e tem que continuar legivel para quem usa");
    }

    @Test
    @Transactional
    void rodarDEnovoNAOcifraDUASvezes() {
        gravar("segredo-antigo-em-claro");
        SegredoGuardado.usarChave("chave-de-teste-do-it");
        servico.cifrarOqueJaEstaGravado();
        String depoisDaPrimeira = cru();

        servico.cifrarOqueJaEstaGravado();

        assertEquals(depoisDaPrimeira, cru(), "o valor nao pode ser reescrito nem cifrado de novo");
        assertEquals("segredo-antigo-em-claro", configs.findById(CHAVE).orElseThrow().getValor());
    }

    @Test
    @Transactional
    void SEMaCHAVEoSERVICOrecusa() {
        // Regravar sem chave nao protege nada, e tocaria em credencial a toa.
        gravar("segredo-antigo-em-claro");

        var erro = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> servico.cifrarOqueJaEstaGravado());

        assertTrue(erro.getReason().contains("DESLIGADA"), "foi: " + erro.getReason());
        assertEquals("segredo-antigo-em-claro", cru(), "e nao pode ter mexido em nada");
    }

    // =============================================================== o relatorio

    @Test
    @Transactional
    void oRELATORIOcontaOqueFALTA_eNAOmostraVALOR() {
        gravar("segredo-antigo-em-claro");

        var r = servico.oQueFaltaCifrar();

        assertEquals(false, r.get("cifragemLigada"));
        assertTrue(((Number) r.get("total")).intValue() >= 1);
        assertFalse(r.toString().contains("segredo-antigo"),
                "o relatorio nao pode conter credencial: ele vai para a tela e para o log");
        assertTrue(String.valueOf(r.get("aviso")).contains("texto puro"),
                "com a chave desligada, o relatorio tem que dizer o tamanho do problema");
    }

    @Test
    @Transactional
    void oRELATORIOzeraDEPOISdeCIFRAR() {
        // Achado por mutacao: tirar o filtro `not like 'enc:v1:%'` da consulta nao muda o que e
        // GRAVADO (o cifrador recusa cifrar duas vezes), mas faz o RELATORIO contar como "em texto
        // puro" o que ja esta cifrado -- e e esse numero que diz se ainda falta algo. Relatorio que
        // nunca zera e relatorio que ninguem olha.
        gravar("segredo-antigo-em-claro");
        SegredoGuardado.usarChave("chave-de-teste-do-it");
        servico.cifrarOqueJaEstaGravado();

        @SuppressWarnings("unchecked")
        var porColuna = (java.util.Map<String, Object>) servico.oQueFaltaCifrar().get("emTextoPuro");

        assertEquals(0, ((Number) porColuna.get("config_plataforma.valor")).intValue(),
                "depois de cifrar, nao pode sobrar nada em texto puro nesta coluna");
    }
}
