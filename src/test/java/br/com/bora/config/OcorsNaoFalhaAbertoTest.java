package br.com.bora.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sem {@code BORA_CORS_ORIGINS}, a API não abre para a internet — ela fecha.
 *
 * <h2>O item 8.5, e o que medir corrigiu</h2>
 * <p>A varredura aponta {@code *} com {@code allowCredentials: true}, o que seria a API chamável por
 * qualquer site. <b>Não é o que roda na produção:</b> conferido no servidor em 22/10/2026,
 * {@code BORA_CORS_ORIGINS} está definida com três origens (o domínio, o www e o nip.io), e um
 * preflight de {@code evil.example.com} contra o servidor de verdade volta <b>403, sem nenhum
 * cabeçalho Access-Control</b>. O furo descrito não existe lá.</p>
 *
 * <h2>O defeito real é o padrão</h2>
 * <p>{@code getOrDefault(..., "*")}: um deploy que esqueça a variável — servidor novo, arquivo de
 * ambiente trocado — abre a API para qualquer site, <b>em silêncio</b>, e nada na tela denuncia.
 * Falhar aberto num item de segurança é o padrão errado, e é o que estes testes travam. É a mesma
 * regra do App Secret do WhatsApp (sem ele o robô fica calado) e da chave de cifragem.</p>
 *
 * <p>Estes testes rodam num JVM <b>sem</b> a variável definida, então o que eles medem é exatamente o
 * caminho do padrão.</p>
 */
class OcorsNaoFalhaAbertoTest {

    /** A regra que valeria para uma requisição deste endereço. */
    private CorsConfiguration regra(String caminho) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", caminho);
        return new WebConfig().corsConfigurationSource().getCorsConfiguration(req);
    }

    private List<String> origensPermitidas() {
        CorsConfiguration cfg = regra("/api/vendas/termometro");
        assertNotNull(cfg, "tem que haver regra de CORS registrada para /**");
        List<String> padroes = cfg.getAllowedOriginPatterns();
        assertNotNull(padroes, "a configuracao usa allowedOriginPatterns, nao allowedOrigins");
        return padroes;
    }

    @Test
    void semAvariavel_oCURINGAnaoEoPADRAO() {
        // O coracao do item: `*` com credenciais nao pode ser o que acontece quando ninguem configurou.
        assertFalse(origensPermitidas().contains("*"),
                "sem BORA_CORS_ORIGINS a API estava aberta para QUALQUER site, com credenciais. "
                        + "Foi: " + origensPermitidas());
    }

    @Test
    void semAvariavel_soLOCALHOSTpassa() {
        // O padrao tem que servir o desenvolvimento e nao servir ninguem na internet.
        List<String> ok = origensPermitidas();

        assertFalse(ok.isEmpty(), "padrao vazio quebraria o desenvolvimento sem avisar");
        for (String o : ok) {
            assertTrue(o.contains("localhost") || o.contains("127.0.0.1"),
                    "o padrao so pode permitir maquina local; achei: " + o);
        }
    }

    @Test
    void siteDEforaNAOcasaComOpadrao() {
        CorsConfiguration cfg = regra("/api/vendas/termometro");

        assertNull(cfg.checkOrigin("https://evil.example.com"),
                "um site qualquer nao pode casar com o padrao");
        assertNull(cfg.checkOrigin("https://localhost.evil.example.com"),
                "nem um dominio que CONTEM 'localhost' no nome");
    }

    @Test
    void oDESENVOLVIMENTOemLOCALHOSTcontinuaFUNCIONANDO() {
        // O outro lado: fechar nao pode custar o dia a dia de quem desenvolve.
        CorsConfiguration cfg = regra("/api/produtos");

        assertNotNull(cfg.checkOrigin("http://localhost:5500"), "o painel local tem que passar");
        assertNotNull(cfg.checkOrigin("http://127.0.0.1:8080"));
    }

    @Test
    void asCREDENCIAIScontinuamPERMITIDAS_eEporISSOqueOcurigaDOI() {
        // Fica medido o porque: `*` sozinho seria menos grave. Com allowCredentials, o navegador
        // manda tambem o que identifica a sessao -- e o curinga vale para todo site do mundo.
        assertEquals(Boolean.TRUE, regra("/api/produtos").getAllowCredentials());
    }

    @Test
    void aREGRAvaleParaTODAaAPI() {
        assertNotNull(regra("/api/qualquer/coisa"));
        assertNotNull(regra("/public/loja/1/cardapio"));
        assertNotNull(regra("/auth/login"));
    }
}
