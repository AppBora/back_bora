package br.com.bora.controller;

import br.com.bora.service.AssinaturaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Recebe os webhooks de pagamento do Asaas (público). Se ASAAS_WEBHOOK_TOKEN estiver definido,
 * exige o header 'asaas-access-token' igual ao token configurado.
 * URL: POST /public/asaas-webhook
 */
@RestController
@RequestMapping("/public/asaas-webhook")
public class AsaasWebhookController {

    private final AssinaturaService service;
    private final String token;

    public AsaasWebhookController(AssinaturaService service, @Value("${asaas.webhook-token:}") String token) {
        this.service = service;
        this.token = token;
    }

    @PostMapping
    public Map<String, Object> receber(@RequestHeader(value = "asaas-access-token", required = false) String header,
                                       @RequestBody Map<String, Object> payload) {
        if (token != null && !token.isBlank() && !token.equals(header)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token de webhook inválido");
        }
        String event = (String) payload.get("event");
        String subscriptionId = null;
        Object pay = payload.get("payment");
        if (pay instanceof Map<?, ?> m && m.get("subscription") != null) {
            subscriptionId = m.get("subscription").toString();
        }
        service.processarWebhook(event, subscriptionId);
        return Map.of("received", true);
    }
}
