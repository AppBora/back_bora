package br.com.bora.service;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Pedido;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.security.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.*;

/** Gerencia as conexões da loja com os marketplaces e a sincronização de status. */
@Service
public class IntegracaoService {

    private static final Logger log = LoggerFactory.getLogger(IntegracaoService.class);

    /** Catálogo de marketplaces suportados (código → nome de exibição). */
    public static final Map<String, String> CATALOGO = new LinkedHashMap<>() {{
        put("IFOOD", "iFood");
        put("NOVE_NOVE", "99Food");
        put("RAPPI", "Rappi");
        put("UBER_EATS", "Uber Eats");
        put("AIQFOME", "aiqfome");
        put("GOOMER", "Goomer");
        put("PIX", "PIX Online (Asaas do lojista)");
        put("WHATSAPP", "Robô WhatsApp (API oficial Meta)");
    }};

    private final IntegracaoCanalRepository repo;
    private final AuthContext ctx;
    private final PixService pix;
    private final List<br.com.bora.service.marketplace.MarketplaceClient> clients;

    public IntegracaoService(IntegracaoCanalRepository repo, AuthContext ctx, PixService pix,
                             List<br.com.bora.service.marketplace.MarketplaceClient> clients) {
        this.repo = repo;
        this.ctx = ctx;
        this.pix = pix;
        this.clients = clients;
    }

    /** Implementação oficial do canal, quando existe (hoje: iFood e Open Delivery/99Food). */
    private Optional<br.com.bora.service.marketplace.MarketplaceClient> clientDe(String canal) {
        if (canal == null) return Optional.empty();
        return clients.stream().filter(c -> c.canal().equalsIgnoreCase(canal)).findFirst();
    }

    private br.com.bora.service.marketplace.MarketplaceClient exigirClient(String canal) {
        return clientDe(canal).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                label(canal) + " ainda não tem integração oficial. Hoje funcionam iFood e 99Food."));
    }

    private IntegracaoCanal exigirIntegracao(String canal) {
        Long lojaId = ctx.lojaId();
        return repo.findByLojaIdAndCanal(lojaId, canal.toUpperCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Salve o merchantId da loja em " + label(canal) + " antes de conectar."));
    }

    /** Passo 1 do vínculo oficial com o marketplace. */
    public Map<String, Object> iniciarVinculo(String canal) {
        ctx.requirePapel("ADMINISTRADOR_LOJA", "GERENTE");
        return exigirClient(canal).iniciarVinculo(exigirIntegracao(canal));
    }

    /** Passo 2 (iFood): troca o código autorizado pelo lojista por tokens de acesso. */
    public Map<String, Object> concluirVinculo(String canal, String authorizationCode) {
        ctx.requirePapel("ADMINISTRADOR_LOJA", "GERENTE");
        IntegracaoCanal i = exigirIntegracao(canal);
        exigirClient(canal).concluirVinculo(i, authorizationCode);
        return Map.of("canal", i.canal, "status", i.status, "conectado", true);
    }

    /** Diagnóstico da conexão — o que o lojista precisa ver quando "não chega pedido". */
    public Map<String, Object> diagnostico(String canal) {
        ctx.requirePapel("ADMINISTRADOR_LOJA", "GERENTE");
        IntegracaoCanal i = exigirIntegracao(canal);
        var client = clientDe(canal);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("canal", i.canal);
        m.put("label", label(i.canal));
        m.put("integracaoOficial", client.isPresent());
        m.put("appDaPlataformaConfigurado", client.map(c -> c.configurado()).orElse(false));
        m.put("merchantId", i.merchantId);
        m.put("status", i.status);
        m.put("ativo", Boolean.TRUE.equals(i.ativo));
        m.put("ultimoPollingEm", i.ultimoPollingEm);
        m.put("ultimaSync", i.ultimaSync);
        m.put("pedidosRecebidos", i.pedidosRecebidos);
        m.put("ultimoErro", i.ultimoErro);
        m.put("tokenExpiraEm", i.tokenExpiraEm);
        // No iFood a loja só aparece como aberta enquanto o polling roda.
        boolean online = i.ultimoPollingEm != null
                && i.ultimoPollingEm.isAfter(OffsetDateTime.now().minusMinutes(2))
                && i.ultimoErro == null;
        m.put("onlineNoMarketplace", online);
        return m;
    }

    public static String label(String canal) {
        return CATALOGO.getOrDefault(canal == null ? "" : canal.toUpperCase(), canal);
    }

    /** Lista TODOS os marketplaces do catálogo, com o estado de conexão da loja. */
    public List<Map<String, Object>> listar() {
        Long lojaId = ctx.lojaId();
        Map<String, IntegracaoCanal> existentes = new HashMap<>();
        repo.findByLojaIdOrderByCanalAsc(lojaId).forEach(i -> existentes.put(i.canal, i));

        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : CATALOGO.entrySet()) {
            IntegracaoCanal i = existentes.get(e.getKey());
            var client = clientDe(e.getKey());
            boolean oficial = client.isPresent();
            // Canal oficial so conta como conectado quando o marketplace aceitou o vinculo (devolveu token).
            // Antes bastava ligar a chave ou receber um "Simular pedido" e o card dizia CONECTADO e
            // "Recebendo pedidos" sem a loja estar ligada ao marketplace (caso da Zira no 99, 15/09).
            boolean vinculado = i != null && "CONECTADO".equals(i.status) && i.accessToken != null
                    && client.map(c -> c.configurado(i)).orElse(false);
            String status = i == null ? "DESCONECTADO"
                    : (!oficial || !"CONECTADO".equals(i.status)) ? i.status
                    : vinculado ? "CONECTADO" : "DESCONECTADO";
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("canal", e.getKey());
            m.put("label", e.getValue());
            m.put("configurado", i != null);
            m.put("ativo", i != null && Boolean.TRUE.equals(i.ativo));
            m.put("status", status);
            m.put("recebendo", i != null && Boolean.TRUE.equals(i.ativo) && (!oficial || vinculado));
            m.put("merchantId", i == null ? null : i.merchantId);
            m.put("clientId", i == null ? null : i.clientId);
            m.put("temSecret", i != null && i.clientSecret != null && !i.clientSecret.isBlank());
            m.put("autoAceitar", i == null || Boolean.TRUE.equals(i.autoAceitar));
            m.put("pedidosRecebidos", i == null ? 0 : i.pedidosRecebidos);
            m.put("ultimaSync", i == null ? null : i.ultimaSync);
            m.put("webhookPath", i == null || i.webhookToken == null ? null
                    : "/webhooks/" + e.getKey().toLowerCase() + "?loja=" + lojaId + "&token=" + i.webhookToken);
            // Canais com integração oficial não pedem credencial ao lojista: o app é da plataforma.
            m.put("oficial", oficial);
            m.put("appConfigurado", client.map(c -> c.configurado()).orElse(false));
            m.put("userCode", i == null ? null : i.userCode);
            m.put("verificationUrl", i == null ? null : i.verificationUrl);
            m.put("vinculoExpiraEm", i == null ? null : i.vinculoExpiraEm);
            m.put("ultimoPollingEm", i == null ? null : i.ultimoPollingEm);
            m.put("ultimoErro", i == null ? null : i.ultimoErro);
            out.add(m);
        }
        return out;
    }

    /** Cria/atualiza a conexão de um canal (credenciais inseridas pelo lojista). */
    public Map<String, Object> salvar(String canal, Map<String, Object> body) {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        Long lojaId = ctx.lojaId();
        String code = canal == null ? "" : canal.toUpperCase();
        if (!CATALOGO.containsKey(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Marketplace não suportado: " + canal);
        }
        IntegracaoCanal i = repo.findByLojaIdAndCanal(lojaId, code).orElseGet(() -> {
            IntegracaoCanal n = new IntegracaoCanal();
            n.lojaId = lojaId; n.canal = code; return n;
        });
        boolean oficial = clientDe(code).isPresent();
        if (body.containsKey("merchantId")) {
            String novo = limpo(str(body.get("merchantId")));
            if (!java.util.Objects.equals(novo, limpo(i.merchantId))) {
                // No iFood e na 99 o código da loja decide DE QUEM são os pedidos que entram aqui (na 99
                // ele vira parte da credencial). Um lojista digitando o código de outro receberia os
                // pedidos do outro: só a plataforma liga uma loja a esse código.
                if (oficial && !ctx.isAdminBora()) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "O código da loja no " + label(code) + " é definido pelo suporte do BoraHapp.");
                }
                if (novo != null && repo.findByCanal(code).stream()
                        .anyMatch(o -> !lojaId.equals(o.lojaId) && novo.equalsIgnoreCase(limpo(o.merchantId)))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Este código já está ligado a outra loja do BoraHapp no " + label(code) + ".");
                }
                i.merchantId = novo;
                if (oficial) {
                    // Loja trocada: o vínculo antigo não vale para a nova — conecta de novo.
                    i.accessToken = null;
                    i.refreshToken = null;
                    i.tokenExpiraEm = null;
                    i.userCode = null;
                    i.codeVerifier = null;
                    i.verificationUrl = null;
                    i.vinculoExpiraEm = null;
                    i.status = novo == null ? "DESCONECTADO" : "PRONTO";
                }
            }
        }
        if (body.containsKey("clientId")) i.clientId = str(body.get("clientId"));
        if (body.containsKey("clientSecret")) { String s = str(body.get("clientSecret")); if (s != null && !s.isBlank()) i.clientSecret = s; }
        if (body.containsKey("autoAceitar")) i.autoAceitar = Boolean.parseBoolean(str(body.get("autoAceitar")));
        if (body.containsKey("ativo")) i.ativo = Boolean.parseBoolean(str(body.get("ativo")));
        if (i.webhookToken == null) i.webhookToken = UUID.randomUUID().toString().replace("-", "");
        boolean temCred = i.clientSecret != null && !i.clientSecret.isBlank();
        // Nos canais oficiais quem diz se está conectado é o vínculo com o marketplace, não esta tela.
        // Antes, salvar o card de uma loja JÁ conectada rebaixava o status para PRONTO e o envio de
        // status (aceitar, pronto, cancelar) parava de ir para o iFood sem ninguém perceber.
        if (!oficial) {
            i.status = Boolean.TRUE.equals(i.ativo) ? (temCred ? "CONECTADO" : "PRONTO") : (temCred ? "PRONTO" : "DESCONECTADO");
        } else if ("DESCONECTADO".equals(i.status) && limpo(i.merchantId) != null) {
            i.status = "PRONTO";
        }
        // PIX: ao ativar com a chave Asaas do lojista, cria automaticamente o webhook na conta dele.
        if ("PIX".equals(code) && Boolean.TRUE.equals(i.ativo) && temCred) {
            pix.provisionarWebhook(i, "https://borahapp.com.br");
        }
        repo.save(i);
        return Map.of("canal", code, "status", i.status, "ativo", Boolean.TRUE.equals(i.ativo));
    }

    /** Webhook: integração da loja+canal válida para o token informado. */
    public Optional<IntegracaoCanal> autenticarWebhook(Long lojaId, String canal, String token) {
        return repo.findByLojaIdAndCanal(lojaId, canal.toUpperCase())
                .filter(i -> token != null && token.equals(i.webhookToken));
    }

    public void registrarRecebido(IntegracaoCanal i) {
        i.pedidosRecebidos = (i.pedidosRecebidos == null ? 0 : i.pedidosRecebidos) + 1;
        i.ultimaSync = OffsetDateTime.now();
        // Em canal oficial quem conecta e o vinculo com o marketplace, nao um pedido que chegou
        // (o "Simular pedido" da tela passa por aqui).
        if (Boolean.TRUE.equals(i.ativo) && clientDe(i.canal).isEmpty()) i.status = "CONECTADO";
        repo.save(i);
    }

    /** Pedido do canal excluido (teste ou lancado errado): o card deixa de conta-lo. */
    public void descontarRecebido(Long lojaId, String canal) {
        if (canal == null) return;
        repo.findByLojaIdAndCanal(lojaId, canal.toUpperCase()).ifPresent(i -> {
            if (i.pedidosRecebidos != null && i.pedidosRecebidos > 0) {
                i.pedidosRecebidos = i.pedidosRecebidos - 1;
                repo.save(i);
            }
        });
    }

    /**
     * Cancela no marketplace ANTES de cancelar no Bora e deixa o erro subir se ele recusar — assim os dois
     * lados nunca ficam diferentes (antes o pedido ficava cancelado aqui e ativo no iFood). Loja não
     * conectada ou canal sem integração oficial: nada a fazer, o cancelamento é só local.
     */
    public void cancelarNoMarketplace(Pedido p, String motivo) {
        if (p == null || p.canalExterno == null || p.idExterno == null) return;
        var client = clientDe(p.canalExterno);
        if (client.isEmpty()) return;
        repo.findByLojaIdAndCanal(p.lojaId, p.canalExterno.toUpperCase())
                .filter(i -> Boolean.TRUE.equals(i.ativo) && i.prontaParaSincronizar())
                .ifPresent(i -> client.get().enviarCancelamento(i, p.idExterno, motivo));
    }

    /** Sincroniza o novo status do pedido de volta ao marketplace (push). No-op sem credenciais. */
    public void notificarStatus(Pedido p, String novoStatus) {
        if (p == null || p.canalExterno == null) return;
        repo.findByLojaIdAndCanal(p.lojaId, p.canalExterno.toUpperCase()).ifPresent(i -> {
            // Nos canais oficiais o que autoriza é o token do vínculo, não uma senha colada pelo lojista.
            boolean temCred = clientDe(i.canal).isPresent()
                    ? i.prontaParaSincronizar()
                    : (i.clientSecret != null && !i.clientSecret.isBlank());
            if (!Boolean.TRUE.equals(i.ativo) || !temCred) {
                log.debug("[{}] status {} do pedido {} não enviado (conexão sem credenciais)", p.canalExterno, novoStatus, p.idExterno);
                return;
            }
            clientDe(i.canal).ifPresentOrElse(
                    c -> {
                        // O motivo so existe no cancelamento e o marketplace exige ele; mandar
                        // "CANCELADO" seco faz o iFood recusar e o pedido fica aberto la dentro.
                        // CANCELADO já foi pedido ao marketplace ANTES de salvar (cancelarNoMarketplace).
                        if (!"CANCELADO".equals(novoStatus)) c.enviarStatus(i, p.idExterno, novoStatus);
                    },
                    () -> log.debug("[{}] canal sem integração oficial; status {} não propagado",
                            p.canalExterno, novoStatus));
        });
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static String limpo(String v) { return v == null || v.isBlank() ? null : v.trim(); }
}
