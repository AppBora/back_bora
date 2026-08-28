package br.com.bora.service.marketplace;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.repository.IntegracaoCanalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Integração pelo padrão <b>Open Delivery</b> — usado pela 99Food e pela Keeta.
 *
 * <p>O Open Delivery foi especificado espelhando a Merchant API do iFood, então o ciclo é o
 * mesmo (polling de eventos → acknowledgment → detalhe do pedido → status). A diferença está
 * na autenticação: aqui é {@code client_credentials} puro, sem autorização por lojista — a
 * credencial vale para a integradora e o merchantId identifica a loja.</p>
 *
 * <p>Por isso o "vínculo" desta implementação só valida a credencial e marca a loja como
 * conectada: não há código para o lojista digitar.</p>
 */
@Slf4j
@Component
public class OpenDeliveryClient implements MarketplaceClient {

    private static final String TOKEN = "/oauth/token";
    private static final String EVENTS = "/events/v1.0/events";
    private static final String ORDERS = "/order/v1.0/orders";

    private static final long RENOVAR_ANTES_MIN = 5;

    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final IntegracaoCanalRepository repo;
    private final MarketplaceHttp http;

    public OpenDeliveryClient(@Value("${marketplace.opendelivery.base-url:https://openapi.99food.com/v4/opendelivery}") String baseUrl,
                              @Value("${marketplace.opendelivery.client-id:}") String clientId,
                              @Value("${marketplace.opendelivery.client-secret:}") String clientSecret,
                              IntegracaoCanalRepository repo, MarketplaceHttp http) {
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.repo = repo;
        this.http = http;
    }

    @Override
    public String canal() {
        return "NOVE_NOVE";
    }

    @Override
    public boolean configurado() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    private RestClient autenticado(IntegracaoCanal i) {
        return http.client(baseUrl, tokenValido(i));
    }

    private void exigirConfigurado() {
        if (!configurado()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Credenciais Open Delivery não configuradas na plataforma. "
                            + "Defina BORA_OPENDELIVERY_CLIENT_ID e BORA_OPENDELIVERY_CLIENT_SECRET.");
        }
    }

    // ---------------------------------------------------------------- vínculo

    @Override
    public Map<String, Object> iniciarVinculo(IntegracaoCanal i) {
        exigirConfigurado();
        if (i.merchantId == null || i.merchantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Informe o merchantId da loja na 99Food antes de conectar.");
        }
        tokenValido(i); // valida a credencial de verdade, batendo no /oauth/token
        i.status = "CONECTADO";
        i.ativo = true;
        i.ultimoErro = null;
        repo.save(i);
        log.info("Open Delivery: loja {} conectada (merchant {})", i.lojaId, i.merchantId);
        return Map.of(
                "conectado", true,
                "instrucao", "Conexão validada. Os pedidos passam a chegar sozinhos em até 30 segundos.");
    }

    @Override
    public void concluirVinculo(IntegracaoCanal i, String autorizacao) {
        // O Open Delivery não tem etapa de autorização por lojista: conectar já basta.
        iniciarVinculo(i);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String tokenValido(IntegracaoCanal i) {
        if (vigente(i)) return i.accessToken;
        synchronized (http.trava(i)) {
            if (i.id != null) {
                repo.findById(i.id).ifPresent(atual -> {
                    i.accessToken = atual.accessToken;
                    i.tokenExpiraEm = atual.tokenExpiraEm;
                });
            }
            if (vigente(i)) return i.accessToken;
            return autenticar(i);
        }
    }

    private boolean vigente(IntegracaoCanal i) {
        return i.accessToken != null && i.tokenExpiraEm != null
                && i.tokenExpiraEm.isAfter(OffsetDateTime.now().plusMinutes(RENOVAR_ANTES_MIN));
    }

    private String autenticar(IntegracaoCanal i) {
        exigirConfigurado();
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("grantType", "client_credentials");
        corpo.put("clientId", clientId);
        corpo.put("clientSecret", clientSecret);

        Map<String, Object> resp;
        try {
            resp = http.client(baseUrl)
                    .post().uri(TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(corpo).retrieve().body(Map.class);
        } catch (Exception e) {
            i.status = "ERRO";
            i.ultimoErro = "Falha ao autenticar: " + resumo(e);
            repo.save(i);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Não foi possível autenticar na 99Food");
        }
        if (resp == null || resp.get("accessToken") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "A 99Food não devolveu o token de acesso");
        }
        i.accessToken = String.valueOf(resp.get("accessToken"));
        i.tokenExpiraEm = OffsetDateTime.now().plusSeconds(segundos(resp.get("expiresIn"), 3600));
        i.ultimoErro = null;
        repo.save(i);
        return i.accessToken;
    }

    // ---------------------------------------------------------------- eventos

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> polling(IntegracaoCanal i) {
        if (i.merchantId == null || i.merchantId.isBlank()) return List.of();
        try {
            List<Map<String, Object>> eventos = autenticado(i).get()
                    .uri(EVENTS + ":polling")
                    .header("x-polling-merchants", i.merchantId)
                    .retrieve().body(List.class);
            i.ultimoPollingEm = OffsetDateTime.now();
            i.ultimoErro = null;
            repo.save(i);
            return eventos == null ? List.of() : eventos;
        } catch (Exception e) {
            i.ultimoPollingEm = OffsetDateTime.now();
            i.ultimoErro = resumo(e);
            repo.save(i);
            log.warn("Open Delivery: polling falhou para a loja {}: {}", i.lojaId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void acknowledge(IntegracaoCanal i, List<String> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) return;
        List<Map<String, String>> corpo = new ArrayList<>();
        for (String id : eventIds) corpo.add(Map.of("id", id));
        try {
            autenticado(i).post().uri(EVENTS + "/acknowledgment")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(corpo).retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("Open Delivery: acknowledgment falhou para a loja {}: {}", i.lojaId, e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> detalhePedido(IntegracaoCanal i, String orderId) {
        try {
            Map<String, Object> pedido = autenticado(i).get().uri(ORDERS + "/{id}", orderId)
                    .retrieve().body(Map.class);
            return pedido == null ? Map.of() : pedido;
        } catch (Exception e) {
            log.warn("Open Delivery: não consegui buscar o pedido {} da loja {}: {}", orderId, i.lojaId, e.getMessage());
            return Map.of();
        }
    }

    @Override
    public void enviarStatus(IntegracaoCanal i, String orderId, String statusInterno) {
        String verbo = verbo(i, statusInterno);
        if (verbo == null) return;
        try {
            autenticado(i).post().uri(ORDERS + "/{id}/{verbo}", orderId, verbo)
                    .retrieve().toBodilessEntity();
            log.info("Open Delivery: pedido {} -> {}", orderId, verbo);
        } catch (Exception e) {
            log.warn("Open Delivery: falha ao enviar {} do pedido {}: {}", verbo, orderId, e.getMessage());
        }
    }

    /** Mesma lógica do iFood; o Open Delivery nomeia o "pronto" como readyForPickup. */
    private String verbo(IntegracaoCanal i, String statusInterno) {
        if (statusInterno == null) return null;
        return switch (statusInterno) {
            case ACEITE_INICIAL -> "confirm";
            case "CONFIRMADO" -> Boolean.TRUE.equals(i.autoAceitar) ? null : "confirm";
            case "EM_PREPARO" -> "startPreparation";
            case "PRONTO" -> "readyForPickup";
            case "SAIU_PARA_ENTREGA" -> "dispatch";
            case "CANCELADO" -> "requestCancellation";
            default -> null;
        };
    }

    private String resumo(Exception e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        return m.length() > 400 ? m.substring(0, 400) : m;
    }

    private static long segundos(Object v, long padrao) {
        try { return v == null ? padrao : (long) Double.parseDouble(String.valueOf(v)); }
        catch (Exception e) { return padrao; }
    }
}
