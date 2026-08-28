package br.com.bora.service;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PIX no cardápio: cobranças criadas na conta Asaas DO LOJISTA (chave dele na integração PIX).
 * O dinheiro do pedido vai direto para o lojista — a plataforma nunca toca no valor.
 */
@Slf4j
@Service
public class PixService {

    private final String baseUrl;
    private final String walletPlataforma;
    private final java.math.BigDecimal taxaPercentual;
    private final br.com.bora.repository.UsuarioRepository usuarios;

    public PixService(@Value("${asaas.base-url:https://sandbox.asaas.com/api/v3}") String baseUrl,
                      @Value("${asaas.platform-wallet-id:}") String walletPlataforma,
                      @Value("${asaas.taxa-percentual:0}") String taxaPercentual,
                      br.com.bora.repository.UsuarioRepository usuarios) {
        this.baseUrl = baseUrl;
        this.walletPlataforma = walletPlataforma;
        this.taxaPercentual = parse(taxaPercentual);
        this.usuarios = usuarios;
    }

    private static java.math.BigDecimal parse(String v) {
        try { return v == null || v.isBlank() ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(v.trim()); }
        catch (Exception e) { return java.math.BigDecimal.ZERO; }
    }

    private RestClient client(String apiKey) {
        return RestClient.builder().baseUrl(baseUrl).defaultHeader("access_token", apiKey).build();
    }

    /** Ao conectar a integração PIX: gera token e cria o webhook na conta Asaas do lojista. */
    public void provisionarWebhook(IntegracaoCanal i, String urlPublica) {
        if (i.clientSecret == null || i.clientSecret.isBlank()) return;
        try {
            if (i.webhookToken == null || i.webhookToken.isBlank()) {
                i.webhookToken = UUID.randomUUID().toString().replace("-", "");
            }
            String email = usuarios.findByLojaId(i.lojaId).stream()
                    .filter(u -> u.getPapel() == br.com.bora.entity.Papel.ADMINISTRADOR_LOJA)
                    .findFirst().map(br.com.bora.entity.Usuario::getEmail).orElse("contato@borahapp.com.br");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "BoraHapp PIX - loja " + i.lojaId);
            body.put("email", email);
            body.put("url", urlPublica + "/public/pix-webhook/" + i.lojaId);
            body.put("enabled", true);
            body.put("interrupted", false);
            body.put("apiVersion", 3);
            body.put("sendType", "SEQUENTIALLY");
            body.put("authToken", i.webhookToken);
            body.put("events", List.of("PAYMENT_RECEIVED", "PAYMENT_CONFIRMED"));
            client(i.clientSecret).post().uri("/webhooks").body(body).retrieve().body(Map.class);
            i.status = "CONECTADO";
        } catch (Exception e) {
            log.warn("PIX loja {}: falha ao criar webhook no Asaas do lojista: {}", i.lojaId, e.getMessage());
            i.status = "ERRO";
        }
    }

    /**
     * Cria a cobrança PIX do pedido na conta do lojista e devolve o QR Code.
     * Retorno: paymentId, payload (copia-e-cola) e encodedImage (PNG base64).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> criarCobranca(IntegracaoCanal i, Loja loja, Pedido pedido,
                                             String nomeCliente, String cpfCnpj) {
        // Preferir a subconta do lojista (recebimento white-label); cair para a chave legada se não houver.
        boolean viaSubconta = loja.asaasApiKey != null && !loja.asaasApiKey.isBlank();
        String apiKey = viaSubconta ? loja.asaasApiKey : (i == null ? null : i.clientSecret);
        if (apiKey == null || apiKey.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Recebimento PIX não ativado para esta loja");
        }
        RestClient c = client(apiKey);
        Map<String, Object> cliente = c.post().uri("/customers").body(Map.of(
                "name", nomeCliente == null || nomeCliente.isBlank() ? "Cliente Cardápio" : nomeCliente,
                "cpfCnpj", cpfCnpj)).retrieve().body(Map.class);
        String customerId = cliente == null ? null : (String) cliente.get("id");

        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("customer", customerId);
        corpo.put("billingType", "PIX");
        corpo.put("value", pedido.valorTotal);
        corpo.put("dueDate", LocalDate.now().toString());
        corpo.put("description", "Pedido " + (pedido.codigo == null ? pedido.id : pedido.codigo) + " - " + loja.nome);
        corpo.put("externalReference", String.valueOf(pedido.id));
        // Split: retém a taxa da plataforma quando o recebimento é pela subconta do lojista.
        // A loja pode ter taxa própria (fundador = 0); sem valor definido, vale o padrão global.
        java.math.BigDecimal taxa = loja.splitPercentual != null ? loja.splitPercentual : taxaPercentual;
        if (viaSubconta && walletPlataforma != null && !walletPlataforma.isBlank()
                && taxa.signum() > 0) {
            corpo.put("split", List.of(Map.of(
                    "walletId", walletPlataforma,
                    "percentualValue", taxa)));
        }
        Map<String, Object> pagamento = c.post().uri("/payments").body(corpo).retrieve().body(Map.class);
        String paymentId = pagamento == null ? null : (String) pagamento.get("id");

        Map<String, Object> qr = c.get().uri("/payments/{id}/pixQrCode", paymentId).retrieve().body(Map.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paymentId", paymentId);
        out.put("payload", qr == null ? null : qr.get("payload"));
        out.put("encodedImage", qr == null ? null : qr.get("encodedImage"));
        return out;
    }
}
