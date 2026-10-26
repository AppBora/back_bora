package br.com.bora.service;

import br.com.bora.dto.InboundOrder;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Endereco de entrega do marketplace nunca carrega a palavra "null".
 *
 * <h2>O defeito (achado pelo QA em 26/10/2026)</h2>
 * <p>O {@code join(rua, numero)} era {@code firstNonBlank(a, "") + ...}. O {@code firstNonBlank}
 * devolve <b>null</b> quando nada esta preenchido — e {@code ""} E blank, entao o segundo argumento
 * nunca salvava nada. Em Java, {@code null + ", 999"} escreve o texto literal
 * <b>{@code "null, 999"}</b>.</p>
 *
 * <p>Por que importa mais que parece: {@code endereco_entrega} vai para a tela de Entregas e para a
 * <b>comanda impressa</b>. Ou seja, chega na mao de quem esta na rua. Nenhum campo do payload do
 * marketplace e obrigatorio no normalizador — texto de fora nao tem forma garantida.</p>
 *
 * <p>O teste cobre os dois canais, porque os dois passam pelo mesmo {@code join} com nomes de campo
 * diferentes: iFood usa {@code streetName}/{@code streetNumber}, 99Food usa
 * {@code street}/{@code number}.</p>
 */
class OenderecoDoMarketplaceNaoEscreveNullTest {

    private final MarketplaceNormalizer normalizer = new MarketplaceNormalizer();

    // ---------------------------------------------------------------- iFood
    private InboundOrder doIfood(Map<String, Object> endereco) {
        Map<String, Object> delivery = new HashMap<>();
        delivery.put("deliveryAddress", endereco);
        Map<String, Object> raw = new HashMap<>();
        raw.put("id", "if-0001");
        raw.put("orderType", "DELIVERY");
        raw.put("delivery", delivery);
        raw.put("customer", Map.of("name", "Cliente iFood"));
        raw.put("items", List.of());
        return normalizer.normalizar("IFOOD", raw);
    }

    @Test
    void PREMISSA_comRUAcompletaOenderecoSAIcerto() {
        // Sem esta premissa, os testes abaixo passariam com um `join` que devolve sempre vazio.
        Map<String, Object> end = new HashMap<>();
        end.put("streetName", "Rua das Flores");
        end.put("streetNumber", "120");

        String e = doIfood(end).endereco();

        assertNotNull(e);
        assertTrue(e.startsWith("Rua das Flores, 120"), "o caminho bom quebrou: " + e);
    }

    @Test
    void semORUA_oNUMEROsaiSOZINHO_eNAOprecedidoDEnull() {
        Map<String, Object> end = new HashMap<>();
        end.put("streetNumber", "999");

        String e = doIfood(end).endereco();

        assertFalse(contemNull(e),
                "a palavra \"null\" foi para o endereco de entrega, e esse campo vai para a comanda "
                        + "impressa na mao do entregador: " + e);
        assertTrue(e != null && e.contains("999"), "o numero, que e a unica pista, foi perdido: " + e);
    }

    @Test
    void semENDERECOnenhum_oCAMPOnaoVIRAaPALAVRAnull() {
        String e = doIfood(new HashMap<>()).endereco();

        assertFalse(contemNull(e), "endereco virou a palavra \"null\": " + e);
        assertTrue(e == null || e.isBlank(),
                "sem endereco, o campo tem de ficar vazio — e isso que \"nao sei\" parece na tela: " + e);
    }

    @Test
    void ruaEMbrancoCONTAcomoAUSENTE() {
        // Marketplace manda string vazia tanto quanto manda campo ausente.
        Map<String, Object> end = new HashMap<>();
        end.put("streetName", "   ");
        end.put("streetNumber", "77");

        String e = doIfood(end).endereco();

        assertFalse(contemNull(e), e);
        assertFalse(e.trim().startsWith(","), "sobrou a virgula de separacao sem a rua: " + e);
    }

    // ---------------------------------------------------------------- 99Food (Open Delivery)
    private InboundOrder da99(Map<String, Object> endereco) {
        Map<String, Object> delivery = new HashMap<>();
        delivery.put("deliveryAddress", endereco);
        Map<String, Object> raw = new HashMap<>();
        raw.put("id", "od-0001");
        raw.put("orderType", "DELIVERY");
        raw.put("delivery", delivery);
        raw.put("customer", Map.of("name", "Cliente 99"));
        raw.put("items", List.of());
        raw.put("total", Map.of());
        return normalizer.normalizar("NOVE_NOVE", raw);
    }

    @Test
    void na99Food_semRUA_tambemNAOescreveNull() {
        // Os dois canais passam pelo mesmo join, com nomes de campo diferentes. Consertar um e
        // esquecer o outro era o risco real.
        Map<String, Object> end = new HashMap<>();
        end.put("number", "120");

        String e = da99(end).endereco();

        assertFalse(contemNull(e), "a 99Food gravou \"null\" no endereco: " + e);
    }

    @Test
    void na99Food_comRUAcompletaCONTINUAcerto() {
        Map<String, Object> end = new HashMap<>();
        end.put("street", "Avenida Brasil");
        end.put("number", "500");

        String e = da99(end).endereco();

        assertTrue(e != null && e.startsWith("Avenida Brasil, 500"), "" + e);
    }

    // ---------------------------------------------------------------- retirada
    @Test
    void naRETIRADAoENDERECOcontinuaNULO_deProposito() {
        // Retirada nao tem endereco, e nulo aqui e a resposta certa: a tela e a comanda sabem
        // tratar nulo, e inventar texto faria o cartao do KDS parecer entrega.
        Map<String, Object> raw = new HashMap<>();
        raw.put("id", "if-retirada");
        raw.put("orderType", "TAKEOUT");
        raw.put("customer", Map.of("name", "Quem retira"));
        raw.put("items", List.of());

        assertNull(normalizer.normalizar("IFOOD", raw).endereco());
    }

    private static boolean contemNull(String s) {
        return s != null && s.toLowerCase().contains("null");
    }
}
