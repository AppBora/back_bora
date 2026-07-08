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

    public IntegracaoService(IntegracaoCanalRepository repo, AuthContext ctx, PixService pix) {
        this.repo = repo;
        this.ctx = ctx;
        this.pix = pix;
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
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("canal", e.getKey());
            m.put("label", e.getValue());
            m.put("configurado", i != null);
            m.put("ativo", i != null && Boolean.TRUE.equals(i.ativo));
            m.put("status", i == null ? "DESCONECTADO" : i.status);
            m.put("merchantId", i == null ? null : i.merchantId);
            m.put("clientId", i == null ? null : i.clientId);
            m.put("temSecret", i != null && i.clientSecret != null && !i.clientSecret.isBlank());
            m.put("autoAceitar", i == null || Boolean.TRUE.equals(i.autoAceitar));
            m.put("pedidosRecebidos", i == null ? 0 : i.pedidosRecebidos);
            m.put("ultimaSync", i == null ? null : i.ultimaSync);
            m.put("webhookPath", i == null || i.webhookToken == null ? null
                    : "/webhooks/" + e.getKey().toLowerCase() + "?loja=" + lojaId + "&token=" + i.webhookToken);
            out.add(m);
        }
        return out;
    }

    /** Cria/atualiza a conexão de um canal (credenciais inseridas pelo lojista). */
    public Map<String, Object> salvar(String canal, Map<String, Object> body) {
        Long lojaId = ctx.lojaId();
        String code = canal == null ? "" : canal.toUpperCase();
        if (!CATALOGO.containsKey(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Marketplace não suportado: " + canal);
        }
        IntegracaoCanal i = repo.findByLojaIdAndCanal(lojaId, code).orElseGet(() -> {
            IntegracaoCanal n = new IntegracaoCanal();
            n.lojaId = lojaId; n.canal = code; return n;
        });
        if (body.containsKey("merchantId")) i.merchantId = str(body.get("merchantId"));
        if (body.containsKey("clientId")) i.clientId = str(body.get("clientId"));
        if (body.containsKey("clientSecret")) { String s = str(body.get("clientSecret")); if (s != null && !s.isBlank()) i.clientSecret = s; }
        if (body.containsKey("autoAceitar")) i.autoAceitar = Boolean.parseBoolean(str(body.get("autoAceitar")));
        if (body.containsKey("ativo")) i.ativo = Boolean.parseBoolean(str(body.get("ativo")));
        if (i.webhookToken == null) i.webhookToken = UUID.randomUUID().toString().replace("-", "");
        boolean temCred = i.clientSecret != null && !i.clientSecret.isBlank();
        i.status = Boolean.TRUE.equals(i.ativo) ? (temCred ? "CONECTADO" : "PRONTO") : (temCred ? "PRONTO" : "DESCONECTADO");
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
        if (Boolean.TRUE.equals(i.ativo)) i.status = "CONECTADO";
        repo.save(i);
    }

    /** Sincroniza o novo status do pedido de volta ao marketplace (push). No-op sem credenciais. */
    public void notificarStatus(Pedido p, String novoStatus) {
        if (p == null || p.canalExterno == null) return;
        repo.findByLojaIdAndCanal(p.lojaId, p.canalExterno.toUpperCase()).ifPresent(i -> {
            boolean temCred = i.clientSecret != null && !i.clientSecret.isBlank();
            if (!Boolean.TRUE.equals(i.ativo) || !temCred) {
                log.debug("[{}] status {} do pedido {} não enviado (conexão sem credenciais)", p.canalExterno, novoStatus, p.idExterno);
                return;
            }
            // PRONTO-PARA-PRODUÇÃO: aqui entra a chamada HTTP autenticada à API do marketplace.
            // Ex. iFood: PUT /order/v1.0/orders/{id}/statuses/{status} com Bearer token.
            log.info("[{}] -> push status {} do pedido externo {}", p.canalExterno, novoStatus, p.idExterno);
        });
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
}
