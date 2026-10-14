package br.com.bora.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Cliente HTTP do Asaas (cobrança recorrente). Fica inerte enquanto ASAAS_API_KEY não estiver
 * definido — o app roda normalmente, e as chamadas de cobrança respondem 503 até haver a chave.
 */
@Service
@lombok.extern.slf4j.Slf4j
public class AsaasClient {

    private final String apiKey;
    private final RestClient http;

    public AsaasClient(@Value("${asaas.base-url:https://sandbox.asaas.com/api/v3}") String baseUrl,
                       @Value("${asaas.api-key:}") String apiKey) {
        this.apiKey = apiKey;
        this.http = TempoLimite.cliente(baseUrl).build();
    }

    public boolean configurado() {
        return apiKey != null && !apiKey.isBlank();
    }

    private void exigirConfig() {
        if (!configurado()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Cobrança não configurada. Defina ASAAS_API_KEY.");
        }
    }

    /** Cria um cliente no Asaas e devolve o id (cus_...). */
    @SuppressWarnings("unchecked")
    public String criarCliente(String nome, String email, String cpfCnpj) {
        exigirConfig();
        Map<String, Object> resp = http.post().uri("/customers")
                .header("access_token", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "name", nome == null ? "Cliente BoraHapp" : nome,
                        "email", email == null ? "" : email,
                        "cpfCnpj", cpfCnpj == null ? "" : cpfCnpj))
                .retrieve().body(Map.class);
        return resp == null ? null : (String) resp.get("id");
    }

    /** Cria uma assinatura mensal e devolve a resposta do Asaas (contém "id" = sub_...). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> criarAssinatura(String customerId, double valor, String descricao, String nextDueDate) {
        exigirConfig();
        return http.post().uri("/subscriptions")
                .header("access_token", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "customer", customerId,
                        "billingType", "UNDEFINED",
                        "value", valor,
                        "nextDueDate", nextDueDate,
                        "cycle", "MONTHLY",
                        "description", descricao))
                .retrieve().body(Map.class);
    }

    /**
     * Cancela a assinatura no Asaas. Chamado quando a plataforma suspende ou arquiva um cliente —
     * sem isso a loja sai do ar aqui e o lojista continua sendo cobrado lá.
     * Devolve false quando a assinatura já não existe no Asaas (404), para o cancelamento do
     * cliente não travar por causa de um id órfão.
     */
    @SuppressWarnings("unchecked")
    public boolean cancelarAssinatura(String subscriptionId) {
        exigirConfig();
        if (subscriptionId == null || subscriptionId.isBlank()) return false;
        try {
            Map<String, Object> resp = http.delete().uri("/subscriptions/" + subscriptionId)
                    .header("access_token", apiKey)
                    .retrieve().body(Map.class);
            return resp == null || !Boolean.FALSE.equals(resp.get("deleted"));
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    /** Atualiza o valor/descrição de uma assinatura existente (usado ao trocar de plano). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> atualizarAssinatura(String subscriptionId, double valor, String descricao) {
        exigirConfig();
        return http.post().uri("/subscriptions/" + subscriptionId)
                .header("access_token", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("value", valor, "description", descricao, "updatePendingPayments", true))
                .retrieve().body(Map.class);
    }

    /**
     * A cobranca aberta mais antiga desta assinatura (atrasada primeiro, depois a proxima).
     *
     * <p>Serve ao registro de pagamento recebido FORA do Asaas: para dar baixa, primeiro e preciso
     * saber em qual cobranca. Devolve o id, ou null quando nao ha nada aberto.</p>
     */
    @SuppressWarnings("unchecked")
    public String cobrancaAbertaDaAssinatura(String subscriptionId) {
        exigirConfig();
        if (subscriptionId == null || subscriptionId.isBlank()) return null;
        for (String status : new String[]{"OVERDUE", "PENDING"}) {
            try {
                Map<String, Object> resp = http.get()
                        .uri("/payments?subscription=" + subscriptionId + "&status=" + status + "&limit=1")
                        .header("access_token", apiKey)
                        .retrieve().body(Map.class);
                Object dados = resp == null ? null : resp.get("data");
                if (dados instanceof java.util.List<?> lista && !lista.isEmpty()
                        && lista.get(0) instanceof Map<?, ?> m && m.get("id") != null) {
                    return m.get("id").toString();
                }
            } catch (Exception e) {
                log.warn("Asaas: falha ao procurar cobranca {} da assinatura {}: {}",
                        status, subscriptionId, e.getMessage());
                return null;
            }
        }
        return null;
    }

    /**
     * Da baixa numa cobranca como recebida em dinheiro (ou PIX direto, fora do Asaas).
     *
     * <p><b>Por que isto e necessario e nao so cosmetico:</b> registrar o pagamento apenas no nosso
     * banco deixa a cobranca ABERTA no Asaas. Ele continua cobrando o lojista, e o aviso de atraso que
     * ele reenvia cai no nosso webhook como {@code PAYMENT_OVERDUE} — que marca a assinatura
     * INADIMPLENTE de novo e abre outra carencia de 10 dias. O registro manual seria desfeito pelo
     * proprio Asaas, e a loja cortada dez dias depois.</p>
     *
     * <p>{@code notifyCustomer=false}: o lojista ja pagou e combinou o pagamento direto; nao faz
     * sentido ele receber um comprovante automatico de uma cobranca que nao usou.</p>
     *
     * @return true se o Asaas aceitou a baixa
     */
    @SuppressWarnings("unchecked")
    public boolean receberEmDinheiro(String paymentId, double valor, String dataPagamento) {
        exigirConfig();
        if (paymentId == null || paymentId.isBlank()) return false;
        try {
            Map<String, Object> resp = http.post().uri("/payments/" + paymentId + "/receiveInCash")
                    .header("access_token", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("paymentDate", dataPagamento, "value", valor, "notifyCustomer", false))
                    .retrieve().body(Map.class);
            return resp != null && !Boolean.FALSE.equals(resp.get("deleted"));
        } catch (Exception e) {
            log.warn("Asaas: falha ao dar baixa em dinheiro na cobranca {}: {}", paymentId, e.getMessage());
            return false;
        }
    }
}
