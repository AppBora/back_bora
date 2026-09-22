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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 99Food pelo padrao <b>Open Delivery v1.7.1</b>.
 *
 * <p>Reescrito em 15/09/2026 contra dois documentos: a especificacao oficial (openapi.yaml da
 * Abrasel) e o "Roteiro de Integracao OpenDelivery" que o time de integracao da 99 mandou. A
 * versao anterior assumia que o Open Delivery espelhava o iFood e usava as rotas e os campos DO
 * iFood — nada chegaria a funcionar: o token ia no formato errado, os eventos eram lidos por um
 * campo que nao existe e todo pedido era descartado em silencio.</p>
 *
 * <p>O ponto de desenho mais importante: <b>a autenticacao e por loja</b>. A 99 monta o
 * {@code client_id} como {@code {app_id}_{app_shop_id}}, onde o App ID e o App Secret sao do
 * aplicativo da plataforma (variaveis de ambiente) e o App Shop ID e o codigo que NOS damos a
 * loja ao cadastra-la no portal da 99. Aqui ele fica em {@code merchantId}, que e o papel que a
 * especificacao da a esse identificador. Cada loja tem o seu token.</p>
 */
@Slf4j
@Component
public class OpenDeliveryClient implements MarketplaceClient {

    // Base da 99 (roteiro, pag. 19): .../v4/opendelivery/ para o token e .../v4/opendelivery/v1/ para o resto.
    private static final String TOKEN = "/oauth/token";
    private static final String POLLING = "/v1/events:polling";
    private static final String ACK = "/v1/events/acknowledgment";
    private static final String ORDERS = "/v1/orders";

    private static final long RENOVAR_ANTES_MIN = 5;

    private final String baseUrl;
    private final CredenciaisMarketplace credenciais;
    private final IntegracaoCanalRepository repo;
    private final MarketplaceHttp http;

    public OpenDeliveryClient(@Value("${marketplace.opendelivery.base-url:https://openapi.99food.com/v4/opendelivery}") String baseUrl,
                              CredenciaisMarketplace credenciais,
                              IntegracaoCanalRepository repo, MarketplaceHttp http) {
        this.baseUrl = baseUrl;
        this.credenciais = credenciais;
        this.repo = repo;
        this.http = http;
    }

    @Override
    public String canal() {
        return "NOVE_NOVE";
    }

    /** O aplicativo da plataforma (App ID + App Secret) esta configurado. */
    @Override
    public boolean configurado() {
        return preenchida(appId(), appSecret());
    }

    /** App ID/Secret da plataforma na 99: o colado na tela vale mais que o do servidor. */
    private String appId() {
        return credenciais.clientId(canal());
    }

    private String appSecret() {
        return credenciais.clientSecret(canal());
    }

    /**
     * A loja autentica pela plataforma (App ID + App Shop ID) ou, no autoatendimento que a 99
     * oferece, com o client_id/secret completos do aplicativo proprio dela.
     */
    @Override
    public boolean configurado(IntegracaoCanal i) {
        return credencialDaLoja(i) || configurado();
    }

    private boolean credencialDaLoja(IntegracaoCanal i) {
        return i != null && preenchida(i.clientId, i.clientSecret);
    }

    /** client_id = {app_id}_{app_shop_id} (roteiro, pag. 17). */
    String clientIdDaLoja(IntegracaoCanal i) {
        return credencialDaLoja(i) ? i.clientId : appId() + "_" + i.merchantId;
    }

    /** client_secret = {app_secret}. E tambem a chave que assina o webhook. */
    private String secretDaLoja(IntegracaoCanal i) {
        return credencialDaLoja(i) ? i.clientSecret : appSecret();
    }

    private static boolean preenchida(String id, String secret) {
        return id != null && !id.isBlank() && secret != null && !secret.isBlank();
    }

    private RestClient autenticado(IntegracaoCanal i) {
        return http.client(baseUrl, tokenValido(i));
    }

    private void exigirConfigurado(IntegracaoCanal i) {
        if (!configurado(i)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Sem credencial para conectar na 99Food. Cadastre o App ID e o App Secret da plataforma em "
                            + "Configurações → Plataforma → Credenciais dos marketplaces, ou a loja informa o "
                            + "Client ID e o Client Secret do aplicativo próprio dela.");
        }
    }

    // ---------------------------------------------------------------- vinculo

    @Override
    public Map<String, Object> iniciarVinculo(IntegracaoCanal i) {
        exigirConfigurado(i);
        if (!credencialDaLoja(i) && (i.merchantId == null || i.merchantId.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Informe o App Shop ID: o codigo desta loja que voce cadastrou no portal de desenvolvedores da 99.");
        }
        // Forca a validacao de verdade: credencial trocada ou app fora da AllowList aparecem ja aqui.
        i.accessToken = null;
        i.tokenExpiraEm = null;
        tokenValido(i);
        i.status = "CONECTADO";
        i.ativo = true;
        i.ultimoErro = null;
        repo.save(i);
        log.info("Open Delivery: loja {} conectada (app shop {}, credencial {})",
                i.lojaId, i.merchantId, credencialDaLoja(i) ? "da propria loja" : "da plataforma");
        return Map.of(
                "conectado", true,
                "instrucao", "Conexao validada. Os pedidos passam a chegar sozinhos em ate 30 segundos.");
    }

    @Override
    public void concluirVinculo(IntegracaoCanal i, String autorizacao) {
        // O Open Delivery nao tem etapa de autorizacao pelo lojista: conectar ja basta.
        iniciarVinculo(i);
    }

    @Override
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

    /**
     * POST /oauth/token em application/x-www-form-urlencoded (TokenRequest da especificacao), com
     * client_id / client_secret / grant_type e resposta access_token / expires_in. A versao
     * anterior mandava JSON com grantType/clientId e lia accessToken — recusado sempre.
     */
    @SuppressWarnings("unchecked")
    private String autenticar(IntegracaoCanal i) {
        exigirConfigurado(i);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientIdDaLoja(i));
        form.add("client_secret", secretDaLoja(i));

        Map<String, Object> resp;
        try {
            resp = http.client(baseUrl).post().uri(TOKEN)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(Map.class);
        } catch (HttpClientErrorException.NotFound e) {
            // Roteiro, pag. 17: sem a liberacao na AllowList da 99, toda chamada volta Not Found.
            throw falha(i, "A 99Food respondeu Not Found: o aplicativo ainda nao esta liberado na AllowList "
                    + "deles. Avise o time de integracao da 99 que o app foi criado.");
        } catch (HttpClientErrorException.Unauthorized e) {
            throw falha(i, "A 99Food recusou a credencial: confira o App Shop ID desta loja e o App Secret da plataforma.");
        } catch (Exception e) {
            throw falha(i, "Falha ao autenticar na 99Food: " + resumo(e));
        }
        if (resp == null || resp.get("access_token") == null) {
            throw falha(i, "A 99Food nao devolveu o access_token.");
        }
        i.accessToken = String.valueOf(resp.get("access_token"));
        i.tokenExpiraEm = OffsetDateTime.now().plusSeconds(segundos(resp.get("expires_in"), 3600));
        i.ultimoErro = null;
        repo.save(i);
        return i.accessToken;
    }

    private ResponseStatusException falha(IntegracaoCanal i, String mensagem) {
        i.status = "ERRO";
        i.ultimoErro = mensagem;
        repo.save(i);
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, mensagem);
    }

    // ---------------------------------------------------------------- eventos

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> polling(IntegracaoCanal i) {
        try {
            RestClient.RequestHeadersSpec<?> req = autenticado(i).get().uri(POLLING);
            if (i.merchantId != null && !i.merchantId.isBlank()) req = req.header("x-polling-merchants", i.merchantId);
            List<Map<String, Object>> eventos = req.retrieve().body(List.class);
            i.ultimoPollingEm = OffsetDateTime.now();
            i.ultimoErro = null;
            repo.save(i);
            return eventos == null ? List.of() : eventos;
        } catch (Exception e) {
            i.ultimoPollingEm = OffsetDateTime.now();
            if (!(e instanceof ResponseStatusException)) i.ultimoErro = resumo(e);
            repo.save(i);
            log.warn("Open Delivery: polling falhou para a loja {}: {}", i.lojaId, e.getMessage());
            return List.of();
        }
    }

    /** Event do Open Delivery: eventId, eventType, orderId, orderURL, createdAt. */
    @Override
    public String eventoId(Map<String, Object> ev) {
        Object v = ev.get("eventId");
        if (v == null) v = ev.get("id");
        return v == null ? null : String.valueOf(v);
    }

    @Override
    public String tipoEvento(Map<String, Object> ev) {
        Object v = ev.get("eventType");
        return v == null ? MarketplaceClient.super.tipoEvento(ev) : String.valueOf(v);
    }

    @Override
    public boolean ehPedidoNovo(String tipo) {
        return "CREATED".equalsIgnoreCase(tipo);
    }

    @Override
    public boolean ehCancelamento(String tipo) {
        return "CANCELLED".equalsIgnoreCase(tipo);
    }

    /** O cliente pediu para cancelar e a 99 quer saber se a loja aceita. */
    @Override
    public boolean ehPedidoDeCancelamento(String tipo) {
        return "ORDER_CANCELLATION_REQUEST".equalsIgnoreCase(tipo);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String motivoDoCancelamento(Map<String, Object> ev) {
        Object meta = ev.get("metadata");
        if (!(meta instanceof Map)) return "Cancelado pela 99Food";
        Map<String, Object> m = (Map<String, Object>) meta;
        String motivo = str(m.get("reason"));
        String codigo = str(m.get("code"));
        String base = motivo == null || motivo.isBlank() ? "Cancelado pela 99Food" : "Cancelado pela 99Food: " + motivo;
        return codigo == null ? base : base + " (" + codigo + ")";
    }

    /** AckEvents exige id, orderId e eventType — a versao anterior mandava so o id. */
    @Override
    public void acknowledge(IntegracaoCanal i, List<Map<String, Object>> eventos) {
        if (eventos == null || eventos.isEmpty()) return;
        List<Map<String, String>> corpo = new ArrayList<>();
        for (Map<String, Object> ev : eventos) {
            String id = eventoId(ev);
            String orderId = pedidoDoEvento(ev);
            String tipo = tipoEvento(ev);
            if (id == null || orderId == null || tipo == null) continue;
            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("orderId", orderId);
            item.put("eventType", tipo);
            corpo.add(item);
        }
        if (corpo.isEmpty()) return;
        try {
            autenticado(i).post().uri(ACK)
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
            log.warn("Open Delivery: nao consegui buscar o pedido {} da loja {}: {}", orderId, i.lojaId, e.getMessage());
            return Map.of();
        }
    }

    // ---------------------------------------------------------------- status

    /**
     * Fluxo que a 99 espera (roteiro, pag. 23): entrega pela LOJA confirm > readyForPickup >
     * dispatch > delivered; entrega pela 99 confirm > readyForPickup e PARA — quem finaliza e a 99.
     * RETIRADA (type TAKEOUT): nao ha entrega — sem dispatch, e o "entregue" do painel vira pickedUp
     * (cliente retirou), que a especificacao so aceita em pedido TAKEOUT.
     */
    @Override
    public void enviarStatus(IntegracaoCanal i, String orderId, String statusInterno) {
        String verbo = verbo(i, statusInterno);
        if (verbo == null) return;
        if ("dispatch".equals(verbo) || "delivered".equals(verbo)) {
            Map<String, Object> pedido = detalhePedido(i, orderId);
            if ("TAKEOUT".equalsIgnoreCase(str(pedido.get("type")))) {
                if ("dispatch".equals(verbo)) {
                    log.info("Open Delivery: pedido {} e retirada; dispatch nao se aplica e nao foi enviado", orderId);
                    return;
                }
                verbo = "pickedUp";
            } else if (entregaPelaPlataforma(pedido)) {
                log.info("Open Delivery: pedido {} e entregue pela 99; {} nao se aplica e nao foi enviado", orderId, verbo);
                return;
            }
        }
        post(i, orderId, verbo, null);
    }

    private String verbo(IntegracaoCanal i, String statusInterno) {
        if (statusInterno == null) return null;
        return switch (statusInterno) {
            case ACEITE_INICIAL -> "confirm";
            case "CONFIRMADO" -> Boolean.TRUE.equals(i.autoAceitar) ? null : "confirm";
            case "EM_PREPARO" -> "preparing";         // era startPreparation, que nao existe no padrao
            case "PRONTO" -> "readyForPickup";
            case "SAIU_PARA_ENTREGA" -> "dispatch";
            case "ENTREGUE" -> "delivered";           // nao existia: o fluxo de entrega propria nunca fechava
            default -> null;                          // CANCELADO vai por enviarCancelamento
        };
    }

    @SuppressWarnings("unchecked")
    private boolean entregaPelaPlataforma(Map<String, Object> pedido) {
        Object delivery = pedido.get("delivery");
        return delivery instanceof Map
                && "MARKETPLACE".equalsIgnoreCase(str(((Map<String, Object>) delivery).get("deliveredBy")));
    }

    /** RequestCancelled exige reason (texto), code (lista fixa) e mode (AUTO/MANUAL). */
    @Override
    public void enviarCancelamento(IntegracaoCanal i, String orderId, String motivo) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("reason", motivo == null || motivo.isBlank() ? "Cancelado pela loja" : motivo.trim());
        corpo.put("code", codigoDeCancelamento(motivo));
        corpo.put("mode", "MANUAL");
        // Diferente dos outros status, aqui a falha NÃO pode ser só logada: quem chama só cancela no Bora
        // se a 99 aceitar, senão o pedido fica cancelado aqui e ativo lá.
        try {
            autenticado(i).post().uri(ORDERS + "/{id}/requestCancellation", orderId)
                    .contentType(MediaType.APPLICATION_JSON).body(corpo)
                    .retrieve().toBodilessEntity();
            log.info("Open Delivery: pedido {} -> requestCancellation ({})", orderId, corpo.get("code"));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            log.warn("Open Delivery: cancelamento do pedido {} recusado: {}", orderId, e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A 99Food recusou o cancelamento: "
                    + resumo(e));
        } catch (Exception e) {
            log.warn("Open Delivery: falha ao cancelar o pedido {}: {}", orderId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Não consegui falar com a 99Food para cancelar. Tente de novo em instantes.");
        }
    }

    /**
     * No Open Delivery a lista de motivos e FIXA (diferente do iFood, onde e dinamica por pedido).
     * Casamos o texto que o lojista escreveu com o codigo mais proximo.
     */
    static String codigoDeCancelamento(String motivo) {
        String t = motivo == null ? "" : Normalizer.normalize(motivo, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase();
        if (t.contains("duplic")) return "DUPLICATE_APPLICATION";
        if (t.contains("entregador") || t.contains("motoboy") || t.contains("sem entrega")) return "RESTAURANT_WITHOUT_DELIVERY_PERSON";
        if (t.contains("item") || t.contains("indispon") || t.contains("acabou") || t.contains("falta") || t.contains("sem estoque")) return "UNAVAILABLE_ITEM";
        if (t.contains("area") || t.contains("longe") || t.contains("distan") || t.contains("fora do raio")) return "ORDER_OUTSIDE_THE_DELIVERY_AREA";
        if (t.contains("horario") || t.contains("fechad") || t.contains("fechou")) return "OUTSIDE_DELIVERY_HOURS";
        if (t.contains("cardapio") || t.contains("menu") || t.contains("preco")) return "OUTDATED_MENU";
        if (t.contains("bloque") || t.contains("fraude") || t.contains("golpe") || t.contains("trote")) return "BLOCKED_CUSTOMER";
        if (t.contains("risco") || t.contains("perigo")) return "RISK_AREA";
        if (t.contains("sistema") || t.contains("erro") || t.contains("falha")) return "SYSTEMIC_ISSUES";
        if (t.contains("entrega")) return "DELIVERY_PROBLEM";
        return "INTERNAL_DIFFICULTIES_OF_THE_RESTAURANT";
    }

    /**
     * Resposta ao pedido de cancelamento do cliente. Negar exige motivo da lista RequestDenied:
     * DISH_ALREADY_DONE (ja preparado) ou OUT_FOR_DELIVERY (ja saiu).
     * NAO VERIFICADO: o corpo do acceptCancellation nao foi lido na especificacao; vai sem corpo.
     */
    @Override
    public void responderPedidoDeCancelamento(IntegracaoCanal i, String orderId, boolean aceitar, String statusInterno) {
        if (aceitar) {
            post(i, orderId, "acceptCancellation", null);
            return;
        }
        Map<String, Object> corpo = new LinkedHashMap<>();
        boolean saiu = "SAIU_PARA_ENTREGA".equals(statusInterno) || "ENTREGUE".equals(statusInterno);
        corpo.put("reason", saiu ? "O pedido ja saiu para entrega" : "O pedido ja foi preparado");
        corpo.put("code", saiu ? "OUT_FOR_DELIVERY" : "DISH_ALREADY_DONE");
        post(i, orderId, "denyCancellation", corpo);
    }

    private void post(IntegracaoCanal i, String orderId, String verbo, Object corpo) {
        try {
            RestClient.RequestBodySpec req = autenticado(i).post().uri(ORDERS + "/{id}/{verbo}", orderId, verbo);
            if (corpo != null) req = req.contentType(MediaType.APPLICATION_JSON).body(corpo);
            req.retrieve().toBodilessEntity();
            log.info("Open Delivery: pedido {} -> {}", orderId, verbo);
        } catch (Exception e) {
            log.warn("Open Delivery: falha ao enviar {} do pedido {}: {}", verbo, orderId, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- webhook

    /**
     * X-App-Signature: HMAC-SHA256 do corpo com o client secret (POST /v1/newEvent da especificacao).
     * A especificacao nao diz a codificacao: aceitamos hex (com ou sem "sha256=") e base64.
     */
    public boolean assinaturaValida(IntegracaoCanal i, String corpo, String assinatura) {
        if (assinatura == null || assinatura.isBlank() || corpo == null) return false;
        String segredo = secretDaLoja(i);
        if (segredo == null || segredo.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] calculado = mac.doFinal(corpo.getBytes(StandardCharsets.UTF_8));
            String recebida = assinatura.trim();
            if (recebida.toLowerCase().startsWith("sha256=")) recebida = recebida.substring(7);
            byte[] hex = HexFormat.of().formatHex(calculado).getBytes(StandardCharsets.UTF_8);
            byte[] b64 = Base64.getEncoder().encodeToString(calculado).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(hex, recebida.toLowerCase().getBytes(StandardCharsets.UTF_8))
                    || MessageDigest.isEqual(b64, recebida.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Open Delivery: nao consegui validar a assinatura do webhook: {}", e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- apoio

    private String resumo(Exception e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        return m.length() > 400 ? m.substring(0, 400) : m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long segundos(Object v, long padrao) {
        try { return v == null ? padrao : (long) Double.parseDouble(String.valueOf(v)); }
        catch (Exception e) { return padrao; }
    }
}
