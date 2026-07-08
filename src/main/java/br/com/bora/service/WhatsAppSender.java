package br.com.bora.service;

import br.com.bora.entity.IntegracaoCanal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Envio de mensagens via WhatsApp Cloud API (Meta) usando a integração WHATSAPP da loja. */
@Slf4j
@Component
public class WhatsAppSender {

    /** Envia texto; devolve true se a Meta aceitou. Nunca lança exceção (log + false). */
    public boolean enviar(IntegracaoCanal i, String para, String texto) {
        if (i == null || i.clientId == null || i.clientSecret == null || para == null || para.isBlank()) return false;
        try {
            RestClient.create().post()
                    .uri("https://graph.facebook.com/v20.0/" + i.clientId + "/messages")
                    .header("Authorization", "Bearer " + i.clientSecret)
                    .header("Content-Type", "application/json")
                    .body(Map.of("messaging_product", "whatsapp", "to", para.replaceAll("\\D", ""),
                            "type", "text", "text", Map.of("body", texto)))
                    .retrieve().body(Map.class);
            return true;
        } catch (Exception e) {
            log.warn("WhatsApp loja {}: falha ao enviar p/ {}: {}", i.lojaId, para, e.getMessage());
            return false;
        }
    }
}
