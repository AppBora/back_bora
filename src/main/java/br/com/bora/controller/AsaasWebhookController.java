package br.com.bora.controller;

import br.com.bora.service.AssinaturaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Recebe os webhooks de pagamento do Asaas (público).
 *
 * <p>URL: {@code POST /public/asaas-webhook}. Exige o header {@code asaas-access-token} igual ao
 * {@code ASAAS_WEBHOOK_TOKEN} configurado.</p>
 *
 * <h2>Por que ele RECUSA quando não há token (item 18 da lista de 23/10)</h2>
 * <p>Antes a conferência era {@code if (token != null && !token.isBlank() && ...)}: sem token
 * configurado, o endpoint <b>aceitava qualquer corpo de qualquer um</b>, calado. Hoje a variável
 * está definida em produção — conferi —, mas ela é ambiente, e ambiente some: basta um deploy sem
 * ela, ou um arquivo de env trocado, para esta porta abrir sozinha, sem erro e sem log.</p>
 *
 * <p>E o que entra por ela não é pouco: este webhook <b>libera e corta o acesso de loja</b> a
 * partir de evento de pagamento. Quem conseguisse postar aqui manteria a própria loja no ar de
 * graça, ou tiraria a de um concorrente do ar.</p>
 *
 * <p>Falhar fechado tem um custo conhecido e pequeno: se alguém subir a API sem a variável, o
 * Asaas passa a receber 503 e <b>reenvia</b> — ele tem fila de retentativa. Nada se perde, e o
 * 503 aparece no painel do Asaas, que é onde alguém olha. Falhar aberto não tem conserto: não dá
 * para saber depois quais chamadas eram legítimas.</p>
 */
@RestController
@RequestMapping("/public/asaas-webhook")
public class AsaasWebhookController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AsaasWebhookController.class);

    private final AssinaturaService service;
    private final String token;

    public AsaasWebhookController(AssinaturaService service, @Value("${asaas.webhook-token:}") String token) {
        this.service = service;
        this.token = token;
    }

    @PostMapping
    public Map<String, Object> receber(@RequestHeader(value = "asaas-access-token", required = false) String header,
                                       @RequestBody Map<String, Object> payload) {
        // SEM TOKEN CONFIGURADO, NINGUEM ENTRA. Ver o cabecalho desta classe: era aqui que a porta
        // abria sozinha num deploy sem a variavel. 503 e nao 401 de proposito -- o problema e
        // nosso, nao de quem chamou, e o Asaas reenvia o que recebe 5xx.
        if (token == null || token.isBlank()) {
            log.error("ASAAS_WEBHOOK_TOKEN nao esta configurado: recusando o webhook do Asaas. "
                    + "Enquanto isso, pagamento e estorno NAO entram -- o Asaas vai reenviar. "
                    + "Defina a variavel e reinicie a API.");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Webhook não configurado");
        }
        if (!token.equals(header)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token de webhook inválido");
        }
        String event = (String) payload.get("event");
        String subscriptionId = null;
        String paymentId = null;
        java.math.BigDecimal valor = null;
        java.math.BigDecimal valorEstornado = null;
        java.time.OffsetDateTime pagoEm = null;
        Object pay = payload.get("payment");
        if (pay instanceof Map<?, ?> m) {
            if (m.get("subscription") != null) subscriptionId = m.get("subscription").toString();
            if (m.get("id") != null) paymentId = m.get("id").toString();
            valor = decimal(m.get("value"));
            // paymentDate é o dia em que o dinheiro entrou; nos avisos de confirmação o Asaas às vezes
            // manda só confirmedDate. Sem nenhum dos dois, quem grava usa a hora de agora.
            pagoEm = dia(m.get("paymentDate") != null ? m.get("paymentDate") : m.get("confirmedDate"));
            valorEstornado = somaDosEstornos(m.get("refunds"));
        }
        service.processarWebhook(event, subscriptionId, paymentId, valorEstornado, valor, pagoEm);
        return Map.of("received", true);
    }

    /**
     * Quanto voltou ao cliente, somando as devolucoes do payload.
     *
     * <h2>Item 3.6 da varredura</h2>
     * <p>No estorno PARCIAL, o {@code value} do payload continua sendo o valor da COBRANCA -- nao o
     * que voltou. A pagina de webhooks do Asaas mostra o campo {@code refunds} (nulo quando nao ha
     * devolucao) mas <b>nao descreve a estrutura dele</b>. Entao aqui ele e lido com cuidado: lista de
     * objetos, somando o {@code value} de cada um, e qualquer coisa fora desse formato devolve nulo.</p>
     *
     * <p>Nulo faz o servico avisar alto e deixar a mensalidade cheia, em vez de inventar um numero:
     * nao se chuta valor em faturamento.</p>
     */
    @SuppressWarnings("unchecked")
    private static java.math.BigDecimal somaDosEstornos(Object refunds) {
        if (!(refunds instanceof java.util.List<?> lista) || lista.isEmpty()) return null;
        java.math.BigDecimal soma = java.math.BigDecimal.ZERO;
        for (Object item : lista) {
            if (!(item instanceof java.util.Map<?, ?> r)) continue;
            // So devolucao concluida conta. "PENDING" ainda nao tirou dinheiro de ninguem.
            Object st = r.get("status");
            if (st != null && !"DONE".equalsIgnoreCase(String.valueOf(st))) continue;
            java.math.BigDecimal v = decimal(r.get("value"));
            if (v != null && v.signum() > 0) soma = soma.add(v);
        }
        return soma.signum() > 0 ? soma : null;
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
