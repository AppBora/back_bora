package br.com.bora.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class WebConfig {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WebConfig.class);

    /**
     * De onde o navegador pode chamar a API.
     *
     * <h2>Item 8.5 da varredura, e o que medir corrigiu</h2>
     * <p>A varredura aponta {@code *} com {@code allowCredentials: true}. <b>Na producao nao e isso
     * que roda:</b> conferido no servidor em 22/10/2026, {@code BORA_CORS_ORIGINS} esta definida com
     * tres origens (o dominio, o www e o nip.io), e um preflight de {@code evil.example.com} volta
     * <b>403 sem nenhum cabecalho Access-Control</b>. Medi antes de consertar.</p>
     *
     * <p><b>O defeito real e o padrao:</b> {@code getOrDefault(..., "*")}. Um deploy que esqueca a
     * variavel — servidor novo, arquivo de ambiente trocado — abre a API para <b>qualquer site</b>
     * com credenciais, em silencio, e nada na tela denuncia. Falhar aberto num item de seguranca e o
     * padrao errado.</p>
     *
     * <p>Agora o padrao e <b>localhost</b>, que serve o desenvolvimento e nao serve a ninguem na
     * internet, e a falta da variavel aparece no log em voz alta — mesma regra do App Secret do
     * WhatsApp (sem ele o robo fica calado) e da chave de cifragem.</p>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        String configurado = System.getenv("BORA_CORS_ORIGINS");
        String origins;
        if (configurado == null || configurado.isBlank()) {
            origins = "http://localhost:*,http://127.0.0.1:*";
            LOG.warn("BORA_CORS_ORIGINS nao definida: a API so aceita chamada de navegador vinda de "
                    + "localhost. Se o painel esta num dominio, DEFINA a variavel -- senao as telas "
                    + "vao falhar com erro de CORS. O padrao de antes era \"*\", que abria a API "
                    + "para qualquer site com credenciais. Ver varredura 8.5.");
        } else {
            origins = configurado;
        }
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(List.of(origins.split(",")));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    @Bean
    public OpenAPI boraOpenAPI() {
        final String scheme = "bearerAuth";
        return new OpenAPI()
                .info(new Info().title("BoraHapp API").version("1.0.0")
                        .description("SaaS de delivery white-label, multi-tenant e em tempo real."))
                .addSecurityItem(new SecurityRequirement().addList(scheme))
                .components(new Components().addSecuritySchemes(scheme,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("JWT obtido em POST /auth/login")));
    }
}
