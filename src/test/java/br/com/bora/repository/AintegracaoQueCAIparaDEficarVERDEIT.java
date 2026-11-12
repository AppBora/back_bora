package br.com.bora.repository;

import br.com.bora.entity.IntegracaoCanal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integracao que cai para de ficar VERDE — contra um Postgres de verdade.
 *
 * <h2>O defeito que isto fecha</h2>
 * <p>O {@code marcarPollingFalhou} gravava "a hora da tentativa e a mensagem, sem mexer em mais
 * nada". O caminho inverso existia -- o {@code marcarPollingOk} promove ERRO para CONECTADO --, mas
 * a queda nao rebaixava: uma integracao CONECTADO que comeca a levar 403 em TODA consulta continuava
 * <b>verde</b> na tela de Integracoes, com o motivo escondido no {@code ultimo_erro}, que ninguem
 * le. Medido na producao em 04/11/2026: a loja 18 com {@code status = CONECTADO} e
 * {@code ultimo_erro = 403 ... forbidden}. E o mesmo "morre em silencio" que custou dois meses no
 * 99Food.</p>
 *
 * <h2>Por que isto e IT e nao teste unitario</h2>
 * <p>Porque o defeito e o conserto moram na consulta JPQL, inclusive num {@code case when} que le
 * {@code falhas_seguidas} ANTES do proprio incremento (num UPDATE, todas as expressoes do SET veem a
 * linha como ela esta). Duble de repositorio devolve o que o teste mandar e provaria exatamente
 * nada. So um banco de verdade diz se a consulta faz o que o javadoc dela promete.</p>
 *
 * <h2>Como rodar: precisa de um Postgres</h2>
 * <pre>
 * docker run -d --name bora-teste-pg -e POSTGRES_PASSWORD=teste -e POSTGRES_DB=bora -p 55432:5432 postgres:16
 * mvn test -Dtest='*IT'
 * </pre>
 * <p>Sem banco no ar o teste FALHA, em vez de passar calado: teste que se pula sozinho nao protege
 * nada.</p>
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
class AintegracaoQueCAIparaDEficarVERDEIT {

    private static final Long LOJA = 9301L;

    @Autowired
    private IntegracaoCanalRepository repo;

    @Autowired
    private jakarta.persistence.EntityManager em;

    @BeforeEach
    void limpar() {
        repo.deleteAll(repo.findByLojaIdOrderByCanalAsc(LOJA));
    }

    private IntegracaoCanal canal(String status) {
        return canal(status, "NOVE_NOVE");
    }

    /**
     * O canal entra como parametro por causa do indice {@code uq_integracao_loja_canal}: uma loja tem
     * UMA integracao por canal. O teste que varre varios status precisa de canais diferentes --
     * descobri isso com o teste estourando no banco, que e o unico lugar onde essa regra existe.
     */
    private IntegracaoCanal canal(String status, String nomeDoCanal) {
        IntegracaoCanal i = new IntegracaoCanal();
        i.lojaId = LOJA;
        i.canal = nomeDoCanal;
        i.status = status;
        i.ativo = true;
        i.merchantId = "shop-123";
        return repo.saveAndFlush(i);
    }

    /** Rele a linha do BANCO. Sem o clear, o que volta e a copia que o JPA tem em memoria. */
    private IntegracaoCanal doBanco(Long id) {
        em.flush();
        em.clear();
        return repo.findById(id).orElseThrow();
    }

    private void falhar(IntegracaoCanal i, String erro) {
        repo.marcarPollingFalhou(i.id, i.lojaId, OffsetDateTime.now(), erro);
    }

    // ------------------------------------------------------------------ o defeito

    @Test
    void tresFALHASseguidasREBAIXAMconectadoPARAerro() {
        IntegracaoCanal i = canal("CONECTADO");

        falhar(i, "403 : User is forbidden to access this resource");
        assertEquals("CONECTADO", doBanco(i.id).status,
                "um tropeco isolado NAO pode rebaixar: ERRO custa 10 minutos de espera ate a "
                        + "proxima tentativa, e isso atrasaria pedido real");
        falhar(i, "403 : User is forbidden to access this resource");
        assertEquals("CONECTADO", doBanco(i.id).status, "nem o segundo");

        falhar(i, "403 : User is forbidden to access this resource");

        IntegracaoCanal caiu = doBanco(i.id);
        assertEquals("ERRO", caiu.status, "no limite, a tela TEM de ficar vermelha -- era aqui que a "
                + "loja seguia verde levando 403 em toda consulta");
        assertEquals(3, caiu.falhasSeguidas);
        assertNotNull(caiu.ultimoErro);
        assertTrue(caiu.ultimoErro.contains("forbidden"), caiu.ultimoErro);
    }

    @Test
    void umSUCESSOnoMEIOzeraACONTAeNAOrebaixa() {
        IntegracaoCanal i = canal("CONECTADO");

        falhar(i, "timeout");
        falhar(i, "timeout");
        repo.marcarPollingOk(i.id, i.lojaId, OffsetDateTime.now());

        IntegracaoCanal depois = doBanco(i.id);
        assertEquals(0, depois.falhasSeguidas, "SEGUIDAS: o sucesso zera a serie");
        assertEquals("CONECTADO", depois.status);
        assertNull(depois.ultimoErro, "polling bom tem de limpar o erro, senao a tela mostra "
                + "problema de uma integracao que esta boa");

        // E a prova de que zerou de verdade: mais duas falhas ainda nao rebaixam.
        falhar(i, "timeout");
        falhar(i, "timeout");
        assertEquals("CONECTADO", doBanco(i.id).status,
                "se o sucesso nao tivesse zerado, estas duas completariam o limite");
    }

    // ------------------------------------------------------------------ o que NAO pode mudar

    @Test
    void oQUEnaoESTAconectadoNAOeREBAIXADO() {
        // AGUARDANDO_AUTORIZACAO quer dizer "falta o lojista digitar o codigo no portal". Trocar isso
        // por ERRO apagaria a informacao de que ha um passo a dar, e a tela diria a coisa errada.
        // PRONTO e DESCONECTADO tambem nao sao falha de polling.
        String[] status = {"AGUARDANDO_AUTORIZACAO", "PRONTO", "DESCONECTADO"};
        String[] canais = {"IFOOD", "RAPPI", "UBER_EATS"};   // uma integracao por canal, por loja
        for (int n = 0; n < status.length; n++) {
            IntegracaoCanal i = canal(status[n], canais[n]);
            falhar(i, "403");
            falhar(i, "403");
            falhar(i, "403");
            falhar(i, "403");
            assertEquals(status[n], doBanco(i.id).status,
                    "status " + status[n] + " nao pode virar ERRO");
            repo.delete(i);
        }
    }

    @Test
    void oCAMINHOdeVOLTAcontinuaFUNCIONANDO() {
        // Quem caiu em ERRO tem de se levantar sozinho quando o problema passa. Em 06/10/2026 uma
        // credencial foi trocada por engano, a loja caiu em ERRO, a credencial foi restaurada e ela
        // continuou parada -- so voltou quando alguem clicou em Conectar.
        IntegracaoCanal i = canal("ERRO");
        i.falhasSeguidas = 7;
        repo.saveAndFlush(i);

        repo.marcarPollingOk(i.id, i.lojaId, OffsetDateTime.now());

        IntegracaoCanal voltou = doBanco(i.id);
        assertEquals("CONECTADO", voltou.status);
        assertEquals(0, voltou.falhasSeguidas);
    }

    @Test
    void naoESCREVEnaINTEGRACAOdeOUTRAloja() {
        // O `and i.lojaId = :lojaId` da consulta nao e decoracao: com um id trocado por engano, o
        // UPDATE tem de escrever ZERO linhas, nao mexer na integracao de outro inquilino.
        IntegracaoCanal i = canal("CONECTADO");

        int linhas = repo.marcarPollingFalhou(i.id, 9999L, OffsetDateTime.now(), "403");

        assertEquals(0, linhas, "loja errada tem de dar UPDATE de zero linhas");
        IntegracaoCanal intacta = doBanco(i.id);
        assertEquals("CONECTADO", intacta.status);
        assertEquals(0, intacta.falhasSeguidas);
        assertNull(intacta.ultimoErro);
    }

    // ------------------------------------------------------------------ teste do teste

    @Test
    void oLIMITEdoCODIGOeOqueESTEtesteUSA() {
        // Se alguem mudar FALHAS_PARA_REBAIXAR, este teste tem de falar disso em voz alta em vez de
        // continuar verde medindo um limite que o codigo nao usa mais.
        assertEquals(3, IntegracaoCanal.FALHAS_PARA_REBAIXAR,
                "o teste de cima conta 3 falhas na mao: ajuste os dois juntos");
    }
}
