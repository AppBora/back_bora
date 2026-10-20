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
     * A assinatura ainda existe no Asaas?
     *
     * <h2>Por que isto nao pode ser um booleano</h2>
     * <p>Sao TRES respostas, nao duas, e confundi-las custa dinheiro. "Nao consegui falar com o
     * Asaas" nao e "a assinatura nao existe": tratar erro de rede como ausencia faria o
     * {@code assinar} cancelar e recriar no escuro, que e exatamente o defeito que ele veio
     * consertar.</p>
     *
     * <p>O caso real: a assinatura da Zira esta {@code deleted: true} no Asaas e o nosso banco nao
     * sabe. Sem distinguir NAO_EXISTE de INDISPONIVEL, ou a loja fica sem como pagar, ou a cobranca e
     * recriada a cada falha de rede.</p>
     */
    public enum EstadoDaAssinatura {
        /** Existe e nao esta deletada. */
        VIVA,
        /** O Asaas respondeu 404, ou respondeu com {@code deleted: true}. */
        NAO_EXISTE,
        /** Nao deu para saber: rede, 5xx, chave errada. NUNCA decidir nada no escuro com isto. */
        INDISPONIVEL
    }

    /** Ver {@link EstadoDaAssinatura}. */
    @SuppressWarnings("unchecked")
    public EstadoDaAssinatura estadoDaAssinatura(String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isBlank()) return EstadoDaAssinatura.NAO_EXISTE;
        if (!configurado()) return EstadoDaAssinatura.INDISPONIVEL;
        try {
            Map<String, Object> resp = http.get()
                    .uri("/subscriptions/" + subscriptionId)
                    .header("access_token", apiKey)
                    .retrieve()
                    .onStatus(st -> st.value() == 404, (req, res) -> { })   // 404 nao e excecao aqui
                    .body(Map.class);
            if (resp == null || resp.get("id") == null) return EstadoDaAssinatura.NAO_EXISTE;
            if (Boolean.TRUE.equals(resp.get("deleted"))) return EstadoDaAssinatura.NAO_EXISTE;
            return EstadoDaAssinatura.VIVA;
        } catch (Exception e) {
            log.warn("Asaas: nao deu para consultar a assinatura {}: {}", subscriptionId, e.getMessage());
            return EstadoDaAssinatura.INDISPONIVEL;
        }
    }

    /** A fatura em aberto, com o link para o lojista pagar. */
    public record FaturaAberta(String id, String link, String vencimento, Double valor) {}

    /**
     * A fatura em aberto da assinatura, com o LINK de pagamento.
     *
     * <p>O {@link #cobrancaAbertaDaAssinatura} devolve so o id, e serve ao registro de pagamento
     * feito fora do Asaas. Este devolve o link, porque o lojista inadimplente precisa de um lugar
     * para PAGAR -- antes a unica coisa que o painel oferecia a ele era "Reativar assinatura", que
     * apagava a divida e criava outra cobranca com mais 7 dias de cortesia.</p>
     *
     * <p>Devolve null quando nao ha nada aberto OU quando a consulta falhou: quem usa isto so mostra
     * um link a mais na tela, entao a ambiguidade nao decide nada. Para DECIDIR, use o
     * {@link #estadoDaAssinatura}.</p>
     */
    @SuppressWarnings("unchecked")
    public FaturaAberta faturaAbertaDaAssinatura(String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isBlank() || !configurado()) return null;
        for (String status : new String[]{"OVERDUE", "PENDING"}) {
            try {
                Map<String, Object> resp = http.get()
                        .uri("/payments?subscription=" + subscriptionId + "&status=" + status + "&limit=1")
                        .header("access_token", apiKey)
                        .retrieve().body(Map.class);
                Object dados = resp == null ? null : resp.get("data");
                if (dados instanceof java.util.List<?> lista && !lista.isEmpty()
                        && lista.get(0) instanceof Map<?, ?> m && m.get("id") != null) {
                    Object valor = m.get("value");
                    return new FaturaAberta(
                            String.valueOf(m.get("id")),
                            m.get("invoiceUrl") == null ? null : String.valueOf(m.get("invoiceUrl")),
                            m.get("dueDate") == null ? null : String.valueOf(m.get("dueDate")),
                            valor instanceof Number n ? n.doubleValue() : null);
                }
            } catch (Exception e) {
                log.warn("Asaas: falha ao procurar fatura {} da assinatura {}: {}",
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
