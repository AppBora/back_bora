package br.com.bora.controller;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
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

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final IntegracaoCanalRepository integracoes;
    private final LojaRepository lojas;
    private final br.com.bora.service.WhatsAppSender envio;
    private final String appSecret;

    public WhatsAppController(IntegracaoCanalRepository integracoes, LojaRepository lojas,
                              br.com.bora.service.WhatsAppSender envio,
                              @org.springframework.beans.factory.annotation.Value("${bora.whatsapp.app-secret:}") String appSecret) {
        this.integracoes = integracoes;
        this.lojas = lojas;
        this.envio = envio;
        this.appSecret = appSecret == null ? "" : appSecret.trim();
    }

    /**
     * Confere que o aviso veio mesmo da Meta: HMAC-SHA256 do corpo cru com o App Secret do
     * aplicativo. O App Secret é da plataforma, não da loja — é um aplicativo Meta só, com os
     * números das lojas dentro.
     */
    private boolean assinaturaDaMeta(String corpoCru, String cabecalho) {
        if (appSecret.isEmpty()) {
            log.warn("WhatsApp: bora.whatsapp.app-secret não configurado — o robô fica calado. "
                    + "Sem ele não dá para saber se o aviso veio da Meta.");
            return false;
        }
        if (cabecalho == null || !cabecalho.startsWith("sha256=")) return false;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    appSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(corpoCru.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256=");
            for (byte b : h) hex.append(String.format("%02x", b));
            return igual(hex.toString(), cabecalho);
        } catch (Exception e) {
            log.warn("WhatsApp: falha ao conferir a assinatura: {}", e.getMessage());
            return false;
        }
    }

    /** Comparação em tempo constante: com equals, o tamanho do prefixo certo vaza pelo relógio. */
    private static boolean igual(String a, String b) {
        if (a == null || b == null) return false;
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Verificação do webhook (Meta chama com hub.challenge ao configurar). */
    @GetMapping("/{lojaId}")
    public String verificar(@PathVariable Long lojaId,
                            @RequestParam(name = "hub.mode", required = false) String mode,
                            @RequestParam(name = "hub.verify_token", required = false) String token,
                            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        IntegracaoCanal i = integracoes.findByLojaIdAndCanal(lojaId, "WHATSAPP")
                .filter(x -> "subscribe".equals(mode) && igual(token, x.webhookToken))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Verify token inválido"));
        return challenge;
    }

    /**
     * Mensagens recebidas: responde o fluxo. Sempre devolve 200 para a Meta não repetir.
     *
     * <p><b>Só aceita aviso assinado pela Meta.</b> Sem isso, qualquer um fazia POST aqui com um
     * {@code from} à escolha e o robô respondia para esse número, com o token da loja: relay de
     * spam saindo do número comercial dela, com risco de banimento e custo. O id da loja está na
     * URL pública do cardápio, então adivinhar não era obstáculo.</p>
     *
     * <p>Sem App Secret configurado o robô fica <b>calado</b>, e não aberto. Nenhuma loja tem o robô
     * funcionando hoje, então fechar não tira nada de ninguém — e um robô que responde sem conferir
     * quem chamou é pior que robô nenhum.</p>
     */
    @PostMapping("/{lojaId}")
    @SuppressWarnings("unchecked")
    public Map<String, String> receber(@PathVariable Long lojaId,
                                       @RequestBody String corpoCru,
                                       @RequestHeader(name = "X-Hub-Signature-256", required = false) String assinatura) {
        // Não logamos o corpo: ele traz o telefone e o texto do cliente final da loja. Era um raio-X
        // temporário de diagnóstico que ficou, e guardava dado pessoal em log sem necessidade (LGPD).
        log.debug("WhatsApp loja {}: aviso recebido", lojaId);
        if (!assinaturaDaMeta(corpoCru, assinatura)) return Map.of("status", "ignored");
        try {
            Map<String, Object> body = JSON.readValue(corpoCru, Map.class);
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
            // Quem fala em atendente, ou reclama, vem ANTES do cardapio. Com "pedido" testado
            // primeiro, "meu pedido nao chegou" recebia o link do cardapio - justamente a mensagem
            // que mais precisa de gente.
            if (corpo.equals("3") || corpo.contains("atendente") || corpo.contains("humano")
                    || corpo.contains("falar") || corpo.contains("reclama") || corpo.contains("problema")
                    || corpo.contains("nao chegou") || corpo.contains("não chegou")
                    || corpo.contains("atrasad") || corpo.contains("errado")) {
                // Nao promete prazo: ninguem na loja e avisado por sistema nenhum. O que e verdade
                // e que a mensagem esta no WhatsApp da loja e alguem le.
                resposta = "👤 Pode falar! Sua mensagem fica aqui e alguém da loja responde assim que puder.";
            } else if (corpo.equals("1") || corpo.contains("cardapio") || corpo.contains("cardápio")
                    || corpo.contains("pedido") || corpo.contains("pedir")) {
                resposta = "🍽️ Aqui está o cardápio de *" + nomeLoja + "*!\n\nMonte seu pedido e pague por PIX ou na entrega:\n" + linkCardapio;
            } else if (corpo.equals("2") || corpo.contains("horario") || corpo.contains("horário")
                    || corpo.contains("aberto") || corpo.contains("funciona")) {
                resposta = "🕒 Consulte nossos horários e faça o pedido direto no cardápio:\n" + linkCardapio
                        + "\n\nSe estivermos abertos, seu pedido entra na cozinha na hora!";
            } else {
                resposta = "Olá! 👋 Sou o assistente de *" + nomeLoja + "*.\n\nDigite o número da opção:\n"
                        + "*1* 🍽️ Ver cardápio e fazer pedido\n"
                        + "*2* 🕒 Horário de funcionamento\n"
                        + "*3* 👤 Falar com um atendente\n\nOu peça agora: " + linkCardapio;
            }
            envio.enviar(i, de, resposta);
        } catch (Exception e) {
            log.warn("WhatsApp loja {}: erro ao processar mensagem: {}", lojaId, e.getMessage());
        }
        return Map.of("status", "ok");
    }
}
