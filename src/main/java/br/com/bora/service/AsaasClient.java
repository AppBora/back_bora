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
public class AsaasClient {

    private final String apiKey;
    private final RestClient http;

    public AsaasClient(@Value("${asaas.base-url:https://sandbox.asaas.com/api/v3}") String baseUrl,
                       @Value("${asaas.api-key:}") String apiKey) {
        this.apiKey = apiKey;
        this.http = RestClient.builder().baseUrl(baseUrl).build();
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
                        "name", nome == null ? "Cliente Bora" : nome,
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
}
