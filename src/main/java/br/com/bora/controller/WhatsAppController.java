package br.com.bora.controller;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Robô de WhatsApp v1 (API oficial Meta Cloud) — fluxo simples por palavras-chave.
 * Config por loja na integração WHATSAPP: clientId = Phone Number ID, clientSecret = token
 * de acesso permanente, webhookToken = verify token (colar no painel da Meta).
 * Estratégia: o robô atende e direciona ao cardápio digital (onde o pedido e o PIX acontecem).
 */
@Slf4j
@RestController
@RequestMapping("/public/whatsapp-webhook")
public class WhatsAppController {

    private final IntegracaoCanalRepository integracoes;
    private final LojaRepository lojas;

    public WhatsAppController(IntegracaoCanalRepository integracoes, LojaRepository lojas) {
        this.integracoes = integracoes;
        this.lojas = lojas;
    }

    /** Verificação do webhook (Meta chama com hub.challenge ao configurar). */
    @GetMapping("/{lojaId}")
    public String verificar(@PathVariable Long lojaId,
                            @RequestParam(name = "hub.mode", required = false) String mode,
                            @RequestParam(name = "hub.verify_token", required = false) String token,
                            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        IntegracaoCanal i = integracoes.findByLojaIdAndCanal(lojaId, "WHATSAPP")
                .filter(x -> "subscribe".equals(mode) && token != null && token.equals(x.webhookToken))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Verify token inválido"));
        return challenge;
    }

    /** Mensagens recebidas: responde o fluxo. Sempre devolve 200 para a Meta não repetir. */
    @PostMapping("/{lojaId}")
    @SuppressWarnings("unchecked")
    public Map<String, String> receber(@PathVariable Long lojaId, @RequestBody Map<String, Object> body) {
        log.info("WhatsApp loja {} payload: {}", lojaId, body); // raio-X temporário de diagnóstico
        try {
            IntegracaoCanal i = integracoes.findByLojaIdAndCanal(lojaId, "WHATSAPP")
                    .filter(x -> Boolean.TRUE.equals(x.ativo) && x.clientSecret != null && x.clientId != null)
                    .orElse(null);
            if (i == null) return Map.of("status", "ignored");

            List<Map<String, Object>> entry = (List<Map<String, Object>>) body.get("entry");
            if (entry == null || entry.isEmpty()) return Map.of("status", "ok");
            List<Map<String, Object>> changes = (List<Map<String, Object>>) entry.get(0).get("changes");
            if (changes == null || changes.isEmpty()) return Map.of("status", "ok");
            Map<String, Object> value = (Map<String, Object>) changes.get(0).get("value");
            List<Map<String, Object>> messages = value == null ? null : (List<Map<String, Object>>) value.get("messages");
            if (messages == null || messages.isEmpty()) return Map.of("status", "ok"); // status de entrega etc.

            Map<String, Object> msg = messages.get(0);
            String de = String.valueOf(msg.get("from"));
            Map<String, Object> text = (Map<String, Object>) msg.get("text");
            String corpo = text == null ? "" : String.valueOf(text.getOrDefault("body", "")).trim().toLowerCase();

            String nomeLoja = lojas.findById(lojaId).map(l -> l.nome).orElse("nossa loja");
            String linkCardapio = "https://borahapp.com.br/cardapio.html?loja=" + lojaId;
            String resposta;
            if (corpo.equals("1") || corpo.contains("cardapio") || corpo.contains("cardápio")
                    || corpo.contains("pedido") || corpo.contains("pedir")) {
                resposta = "🍽️ Aqui está o cardápio de *" + nomeLoja + "*!\n\nMonte seu pedido e pague por PIX ou na entrega:\n" + linkCardapio;
            } else if (corpo.equals("2") || corpo.contains("horario") || corpo.contains("horário")
                    || corpo.contains("aberto") || corpo.contains("funciona")) {
                resposta = "🕒 Consulte nossos horários e faça o pedido direto no cardápio:\n" + linkCardapio
                        + "\n\nSe estivermos abertos, seu pedido entra na cozinha na hora!";
            } else if (corpo.equals("3") || corpo.contains("atendente") || corpo.contains("humano") || corpo.contains("falar")) {
                resposta = "👤 Certo! Um atendente humano vai te responder por aqui em instantes.";
            } else {
                resposta = "Olá! 👋 Sou o assistente de *" + nomeLoja + "*.\n\nDigite o número da opção:\n"
                        + "*1* 🍽️ Ver cardápio e fazer pedido\n"
                        + "*2* 🕒 Horário de funcionamento\n"
                        + "*3* 👤 Falar com um atendente\n\nOu peça agora: " + linkCardapio;
            }
            enviar(i, de, resposta);
        } catch (Exception e) {
            log.warn("WhatsApp loja {}: erro ao processar mensagem: {}", lojaId, e.getMessage());
        }
        return Map.of("status", "ok");
    }

    private void enviar(IntegracaoCanal i, String para, String texto) {
        try {
            RestClient.create().post()
                    .uri("https://graph.facebook.com/v20.0/" + i.clientId + "/messages")
                    .header("Authorization", "Bearer " + i.clientSecret)
                    .header("Content-Type", "application/json")
                    .body(Map.of("messaging_product", "whatsapp", "to", para,
                            "type", "text", "text", Map.of("body", texto)))
                    .retrieve().body(Map.class);
        } catch (Exception e) {
            log.warn("WhatsApp loja {}: falha ao enviar resposta: {}", i.lojaId, e.getMessage());
        }
    }
}
