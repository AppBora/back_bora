package br.com.bora.service;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Papel;
import br.com.bora.entity.Usuario;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Recebimento white-label: a plataforma cria uma SUBCONTA Asaas para o lojista via API
 * (com a chave-mãe). O PIX do cliente cai direto na subconta do lojista; a taxa da
 * plataforma é retida por split. O lojista só conclui o KYC pelo link de onboarding.
 *
 * Fica inerte enquanto a chave-mãe (asaas.api-key) não estiver definida — o app roda normal.
 */
@Slf4j
@Service
public class AsaasSubcontaService {

    private final String baseUrl;
    private final String masterKey;
    private final String urlPublica;
    private final LojaRepository lojas;
    private final UsuarioRepository usuarios;
    private final AuthContext ctx;

    public AsaasSubcontaService(@Value("${asaas.base-url:https://sandbox.asaas.com/api/v3}") String baseUrl,
                                @Value("${asaas.api-key:}") String masterKey,
                                @Value("${asaas.url-publica:https://borahapp.com.br}") String urlPublica,
                                LojaRepository lojas, UsuarioRepository usuarios, AuthContext ctx) {
        this.baseUrl = baseUrl;
        this.masterKey = masterKey;
        this.urlPublica = urlPublica;
        this.lojas = lojas;
        this.usuarios = usuarios;
        this.ctx = ctx;
    }

    public boolean configurado() {
        return masterKey != null && !masterKey.isBlank();
    }

    private RestClient client(String apiKey) {
        return RestClient.builder().baseUrl(baseUrl).defaultHeader("access_token", apiKey).build();
    }

    /** Estado do recebimento da loja logada. Restrito ao admin: a resposta traz o link de KYC
     *  bancário da subconta, que decide para onde vai o dinheiro do PIX. */
    public Map<String, Object> status() {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        Loja loja = lojas.findById(ctx.lojaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
        return view(loja);
    }

    /**
     * Ativa o recebimento: cria a subconta do lojista no Asaas (idempotente) e devolve o link de KYC.
     * `dados` pode trazer campos extras exigidos pelo Asaas (mobilePhone, address, postalCode…).
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> ativar(String cpfCnpj, Map<String, Object> dados) {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        if (!configurado()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Recebimento não configurado na plataforma. Defina ASAAS_API_KEY (conta-mãe).");
        }
        if (cpfCnpj == null || cpfCnpj.replaceAll("\\D", "").length() < 11) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe um CPF/CNPJ válido");
        }
        Loja loja = lojas.findById(ctx.lojaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
        if (loja.asaasApiKey != null && !loja.asaasApiKey.isBlank()) {
            return view(loja); // já provisionada — não recria
        }

        String email = usuarios.findByLojaId(loja.getId()).stream()
                .filter(u -> u.getPapel() == Papel.ADMINISTRADOR_LOJA)
                .findFirst().map(Usuario::getEmail).orElse("contato@borahapp.com.br");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", loja.getNome() == null ? "Loja BoraHapp" : loja.getNome());
        body.put("email", email);
        body.put("cpfCnpj", cpfCnpj.replaceAll("\\D", ""));
        if (dados != null) {
            copiar(dados, body, "mobilePhone", "phone", "address", "addressNumber",
                    "complement", "province", "postalCode", "companyType", "incomeValue", "birthDate");
        }

        try {
            Map<String, Object> resp = client(masterKey).post().uri("/accounts").body(body).retrieve().body(Map.class);
            if (resp == null || resp.get("apiKey") == null) {
                loja.asaasStatus = "ERRO";
                lojas.save(loja);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Asaas não retornou as credenciais da subconta");
            }
            loja.asaasSubcontaId = str(resp.get("id"));
            loja.asaasWalletId = str(resp.get("walletId"));
            loja.asaasApiKey = str(resp.get("apiKey"));
            loja.asaasOnboardingUrl = str(resp.getOrDefault("onboardingUrl", resp.get("onboardingUrlLink")));
            loja.asaasStatus = "PENDENTE"; // vira ATIVO quando o KYC é aprovado (webhook/consulta)
            criarWebhookPix(loja);
            lojas.save(loja);
            log.info("Subconta Asaas criada para a loja {} (id {})", loja.getId(), loja.asaasSubcontaId);
            return view(loja);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            loja.asaasStatus = "ERRO";
            lojas.save(loja);
            log.warn("Falha ao criar subconta Asaas da loja {}: {}", loja.getId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Não foi possível criar a subconta no Asaas: " + e.getMessage());
        }
    }

    /** Cria, na subconta do lojista, o webhook que confirma o pagamento do PIX. */
    @SuppressWarnings("unchecked")
    private void criarWebhookPix(Loja loja) {
        if (loja.asaasApiKey == null || loja.asaasApiKey.isBlank()) return;
        try {
            String token = UUID.randomUUID().toString().replace("-", "");
            loja.asaasWebhookToken = token; // persistido junto com a loja pelo chamador — sem isso o webhook e rejeitado
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "BoraHapp PIX - loja " + loja.getId());
            body.put("url", urlPublica + "/public/pix-webhook/" + loja.getId());
            body.put("enabled", true);
            body.put("interrupted", false);
            body.put("apiVersion", 3);
            body.put("sendType", "SEQUENTIALLY");
            body.put("authToken", token);
            body.put("events", List.of("PAYMENT_RECEIVED", "PAYMENT_CONFIRMED"));
            client(loja.asaasApiKey).post().uri("/webhooks").body(body).retrieve().body(Map.class);
        } catch (Exception e) {
            log.warn("Loja {}: subconta criada, mas falhou ao registrar webhook PIX: {}", loja.getId(), e.getMessage());
        }
    }

    private Map<String, Object> view(Loja loja) {
        boolean provisionada = loja.asaasApiKey != null && !loja.asaasApiKey.isBlank();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configuradoPlataforma", configurado());
        m.put("provisionada", provisionada);
        m.put("status", loja.asaasStatus == null ? (provisionada ? "PENDENTE" : "DESATIVADO") : loja.asaasStatus);
        m.put("walletId", loja.asaasWalletId);
        m.put("onboardingUrl", loja.asaasOnboardingUrl);
        return m;
    }

    private void copiar(Map<String, Object> de, Map<String, Object> para, String... chaves) {
        for (String k : chaves) {
            Object v = de.get(k);
            if (v != null && !String.valueOf(v).isBlank()) para.put(k, v);
        }
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
}
