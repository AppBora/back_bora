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
        String paymentId = null;
        java.math.BigDecimal valor = null;
        java.time.OffsetDateTime pagoEm = null;
        Object pay = payload.get("payment");
        if (pay instanceof Map<?, ?> m) {
            if (m.get("subscription") != null) subscriptionId = m.get("subscription").toString();
            if (m.get("id") != null) paymentId = m.get("id").toString();
            valor = decimal(m.get("value"));
            // paymentDate é o dia em que o dinheiro entrou; nos avisos de confirmação o Asaas às vezes
            // manda só confirmedDate. Sem nenhum dos dois, quem grava usa a hora de agora.
            pagoEm = dia(m.get("paymentDate") != null ? m.get("paymentDate") : m.get("confirmedDate"));
        }
        service.processarWebhook(event, subscriptionId, paymentId, valor, pagoEm);
        return Map.of("received", true);
    }

    private static java.math.BigDecimal decimal(Object v) {
        if (v == null) return null;
        try {
            return new java.math.BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null; // valor estranho não pode derrubar o webhook: sem ele, grava o da assinatura
        }
    }

    /** "2026-10-02" (formato que o Asaas manda) vira o começo daquele dia. */
    private static java.time.OffsetDateTime dia(Object v) {
        if (v == null) return null;
        try {
            return java.time.LocalDate.parse(v.toString().substring(0, 10))
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toOffsetDateTime();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
