package br.com.bora.service.marketplace;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.repository.IntegracaoCanalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Integração oficial com o iFood (Merchant API), no modelo de <b>aplicativo distribuído</b>:
 * o app é da plataforma e cada lojista autoriza o acesso digitando um código no Portal do Parceiro.
 *
 * <p>Fluxo de vínculo:</p>
 * <ol>
 *   <li>{@code POST /authentication/v1.0/oauth/userCode} → devolve o código que o lojista digita</li>
 *   <li>o lojista digita o código no portal do iFood</li>
 *   <li>{@code POST /authentication/v1.0/oauth/token} com grantType=authorization_code → tokens</li>
 *   <li>renovação com grantType=refresh_token (o token dura ~6h)</li>
 * </ol>
 *
 * <p>Atenção: o iFood usa os campos em <b>camelCase</b> ({@code grantType}, {@code clientId}),
 * e não o snake_case do OAuth padrão.</p>
 */
@Slf4j
@Component
public class IfoodClient implements MarketplaceClient {

    private static final String AUTH = "/authentication/v1.0/oauth";
    private static final String EVENTS = "/events/v1.0/events";
    private static final String ORDERS = "/order/v1.0/orders";

    /** Renova o token com folga: o do iFood dura ~6h. */
    private static final long RENOVAR_ANTES_MIN = 30;

    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final IntegracaoCanalRepository repo;
    private final MarketplaceHttp http;

    public IfoodClient(@Value("${marketplace.ifood.base-url:https://merchant-api.ifood.com.br}") String baseUrl,
                       @Value("${marketplace.ifood.client-id:}") String clientId,
                       @Value("${marketplace.ifood.client-secret:}") String clientSecret,
                       IntegracaoCanalRepository repo, MarketplaceHttp http) {
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.repo = repo;
        this.http = http;
    }

    @Override
    public String canal() {
        return "IFOOD";
    }

    @Override
    public boolean configurado() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    private RestClient client() {
        return http.client(baseUrl);
    }

    private RestClient autenticado(IntegracaoCanal i) {
        return http.client(baseUrl, tokenValido(i));
    }

    private void exigirConfigurado() {
        if (!configurado()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Aplicativo do iFood não configurado na plataforma. Defina BORA_IFOOD_CLIENT_ID e BORA_IFOOD_CLIENT_SECRET.");
        }
    }

    // ---------------------------------------------------------------- vínculo

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> iniciarVinculo(IntegracaoCanal i) {
        exigirConfigurado();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("clientId", clientId);

        Map<String, Object> resp;
        try {
            resp = client().post().uri(AUTH + "/userCode")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(Map.class);
        } catch (Exception e) {
            throw falha("Não foi possível pedir o código de vínculo ao iFood", e, i);
        }
        if (resp == null || resp.get("userCode") == null) {
            throw falha("O iFood não devolveu o código de vínculo", null, i);
        }

        i.userCode = str(resp.get("userCode"));
        i.codeVerifier = str(resp.get("authorizationCodeVerifier"));
        i.verificationUrl = str(firstNonNull(resp.get("verificationUrlComplete"), resp.get("verificationUrl")));
        i.vinculoExpiraEm = OffsetDateTime.now().plusSeconds(segundos(resp.get("expiresIn"), 600));
        i.status = "AGUARDANDO_AUTORIZACAO";
        i.ultimoErro = null;
        repo.save(i);

        log.info("iFood: vínculo iniciado para a loja {} (userCode {})", i.lojaId, i.userCode);
        return Map.of(
                "userCode", i.userCode,
                "verificationUrl", i.verificationUrl == null ? "" : i.verificationUrl,
                "expiraEm", i.vinculoExpiraEm.toString(),
                "instrucao", "Entre no Portal do Parceiro iFood e informe este código para autorizar o BoraHapp.");
    }

    @Override
    @SuppressWarnings("unchecked")
    public void concluirVinculo(IntegracaoCanal i, String autorizacao) {
        exigirConfigurado();
        if (i.codeVerifier == null || i.codeVerifier.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Peça um novo código de vínculo antes de concluir.");
        }
        if (autorizacao == null || autorizacao.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Informe o código de autorização exibido pelo iFood.");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grantType", "authorization_code");
        form.add("clientId", clientId);
        form.add("clientSecret", clientSecret);
        form.add("authorizationCode", autorizacao.trim());
        form.add("authorizationCodeVerifier", i.codeVerifier);

        Map<String, Object> resp;
        try {
            resp = client().post().uri(AUTH + "/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(Map.class);
        } catch (Exception e) {
            throw falha("O iFood recusou a autorização", e, i);
        }
        guardarTokens(i, resp);
        i.userCode = null;
        i.codeVerifier = null;
        i.verificationUrl = null;
        i.vinculoExpiraEm = null;
        i.status = "CONECTADO";
        i.ativo = true;
        repo.save(i);
        log.info("iFood: loja {} conectada (merchant {})", i.lojaId, i.merchantId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String tokenValido(IntegracaoCanal i) {
        if (vigente(i)) return i.accessToken;
        synchronized (http.trava(i)) {
            // Outra thread pode ter renovado enquanto esperávamos a trava: relê antes de decidir.
            if (i.id != null) {
                repo.findById(i.id).ifPresent(atual -> {
                    i.accessToken = atual.accessToken;
                    i.refreshToken = atual.refreshToken;
                    i.tokenExpiraEm = atual.tokenExpiraEm;
                });
            }
            if (vigente(i)) return i.accessToken;
            return renovar(i);
        }
    }

    /** Token presente e ainda longe do vencimento. */
    private boolean vigente(IntegracaoCanal i) {
        return i.accessToken != null && i.tokenExpiraEm != null
                && i.tokenExpiraEm.isAfter(OffsetDateTime.now().plusMinutes(RENOVAR_ANTES_MIN));
    }

    private String renovar(IntegracaoCanal i) {
        if (i.refreshToken == null || i.refreshToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "Esta loja ainda não autorizou o BoraHapp no iFood.");
        }
        exigirConfigurado();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grantType", "refresh_token");
        form.add("clientId", clientId);
        form.add("clientSecret", clientSecret);
        form.add("refreshToken", i.refreshToken);

        Map<String, Object> resp;
        try {
            resp = client().post().uri(AUTH + "/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(Map.class);
        } catch (Exception e) {
            i.status = "ERRO";
            i.ultimoErro = "Falha ao renovar o token: " + e.getMessage();
            repo.save(i);
            throw falha("Não foi possível renovar o acesso ao iFood", e, i);
        }
        guardarTokens(i, resp);
        repo.save(i);
        return i.accessToken;
    }

    private void guardarTokens(IntegracaoCanal i, Map<String, Object> resp) {
        if (resp == null || resp.get("accessToken") == null) {
            throw falha("O iFood não devolveu o token de acesso", null, i);
        }
        i.accessToken = str(resp.get("accessToken"));
        String novoRefresh = str(resp.get("refreshToken"));
        if (novoRefresh != null && !novoRefresh.isBlank()) i.refreshToken = novoRefresh;
        i.tokenExpiraEm = OffsetDateTime.now().plusSeconds(segundos(resp.get("expiresIn"), 21600));
        i.ultimoErro = null;
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
            log.warn("iFood: polling falhou para a loja {}: {}", i.lojaId, e.getMessage());
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
            // Não relança: um ack perdido só faz o evento voltar no próximo ciclo.
            log.warn("iFood: acknowledgment falhou para a loja {}: {}", i.lojaId, e.getMessage());
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
            log.warn("iFood: não consegui buscar o pedido {} da loja {}: {}", orderId, i.lojaId, e.getMessage());
            return Map.of();
        }
    }

    @Override
    public void enviarStatus(IntegracaoCanal i, String orderId, String statusInterno) {
        String verbo = verbo(i, statusInterno);
        if (verbo == null) return; // status sem correspondência no iFood
        try {
            autenticado(i).post().uri(ORDERS + "/{id}/{verbo}", orderId, verbo)
                    .retrieve().toBodilessEntity();
            log.info("iFood: pedido {} -> {}", orderId, verbo);
        } catch (Exception e) {
            log.warn("iFood: falha ao enviar {} do pedido {}: {}", verbo, orderId, e.getMessage());
        }
    }

    /**
     * Traduz o status interno do BoraHapp para o verbo da Merchant API.
     *
     * <p>Com o aceite automático ligado, o pedido já foi confirmado no momento da importação —
     * confirmar de novo quando o operador move o card seria uma segunda chamada de confirmação
     * para um pedido já confirmado, que o iFood recusa.</p>
     */
    private String verbo(IntegracaoCanal i, String statusInterno) {
        if (statusInterno == null) return null;
        return switch (statusInterno) {
            case ACEITE_INICIAL -> "confirm";
            case "CONFIRMADO" -> Boolean.TRUE.equals(i.autoAceitar) ? null : "confirm";
            case "EM_PREPARO" -> "startPreparation";
            case "PRONTO" -> "readyToPickup";
            case "SAIU_PARA_ENTREGA" -> "dispatch";
            case "CANCELADO" -> "requestCancellation";
            default -> null; // ENTREGUE é concluído pelo próprio iFood
        };
    }

    // ---------------------------------------------------------------- apoio

    private ResponseStatusException falha(String msg, Exception causa, IntegracaoCanal i) {
        if (i != null) {
            i.status = "ERRO";
            i.ultimoErro = msg + (causa == null ? "" : ": " + resumo(causa));
            repo.save(i);
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, msg);
    }

    private String resumo(Exception e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        return m.length() > 400 ? m.substring(0, 400) : m;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static Object firstNonNull(Object a, Object b) { return a != null ? a : b; }

    private static long segundos(Object v, long padrao) {
        try { return v == null ? padrao : (long) Double.parseDouble(String.valueOf(v)); }
        catch (Exception e) { return padrao; }
    }
}
