package br.com.bora.service;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Papel;
import br.com.bora.entity.Usuario;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** So imagem e PDF: o Asaas recusa o resto e o lojista ficaria sem entender o erro. */
    private static final Set<String> ACEITOS = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/heic", "image/heif", "application/pdf");

    private final String baseUrl;
    private final String masterKey;
    private final String urlPublica;
    private final String webhookEmail;
    private final LojaRepository lojas;
    private final UsuarioRepository usuarios;
    private final AuthContext ctx;

    public AsaasSubcontaService(@Value("${asaas.base-url:https://sandbox.asaas.com/api/v3}") String baseUrl,
                                @Value("${asaas.api-key:}") String masterKey,
                                @Value("${asaas.url-publica:https://borahapp.com.br}") String urlPublica,
                                @Value("${asaas.webhook-email:}") String webhookEmail,
                                LojaRepository lojas, UsuarioRepository usuarios, AuthContext ctx) {
        this.baseUrl = baseUrl;
        this.masterKey = masterKey;
        this.urlPublica = urlPublica;
        this.webhookEmail = webhookEmail;
        this.lojas = lojas;
        this.usuarios = usuarios;
        this.ctx = ctx;
    }

    public boolean configurado() {
        return masterKey != null && !masterKey.isBlank();
    }

    /**
     * Timeout explicito: sem ele uma lentidao do Asaas prende a thread do request. A tela de
     * Integracoes agora consulta o Asaas a cada abertura, entao isso deixou de ser teorico.
     */
    private RestClient client(String apiKey) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(5));
        fabrica.setReadTimeout(Duration.ofSeconds(12));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(fabrica)
                .defaultHeader("access_token", apiKey).build();
    }

    /** Estado do recebimento da loja logada. Restrito ao admin: a resposta traz o link de KYC
     *  bancário da subconta, que decide para onde vai o dinheiro do PIX. */
    @Transactional
    public Map<String, Object> status() {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        Loja loja = lojas.findById(ctx.lojaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
        if (atualizarStatus(loja)) lojas.save(loja);
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

        String email = emailDoAdmin(loja);

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
            criarWebhookPix(loja, email);
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

    /** E-mail do admin da loja - e o dono da subconta no Asaas. */
    private String emailDoAdmin(Loja loja) {
        return usuarios.findByLojaId(loja.getId()).stream()
                .filter(u -> u.getPapel() == Papel.ADMINISTRADOR_LOJA)
                .findFirst().map(Usuario::getEmail).orElse("contato@borahapp.com.br");
    }

    /**
     * Registra de novo o webhook de PIX de uma loja que ja tem subconta. Existe porque a criacao da
     * subconta e do webhook sao duas chamadas: a segunda ja falhou em producao (o Asaas passou a exigir
     * e-mail) e a loja ficou com conta viva e nenhum aviso de pagamento chegando.
     */
    @Transactional
    public Map<String, Object> repararWebhook() {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        Loja loja = lojas.findById(ctx.lojaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja nao encontrada"));
        if (loja.asaasApiKey == null || loja.asaasApiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ative o recebimento antes");
        }
        criarWebhookPix(loja, emailDoAdmin(loja));
        if (loja.asaasWebhookToken == null || loja.asaasWebhookToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Nao foi possivel registrar o aviso de pagamento no Asaas. Veja o log da API.");
        }
        lojas.save(loja);
        return view(loja);
    }

    /** Cria, na subconta do lojista, o webhook que confirma o pagamento do PIX. */
    @SuppressWarnings("unchecked")
    private void criarWebhookPix(Loja loja, String email) {
        if (loja.asaasApiKey == null || loja.asaasApiKey.isBlank()) return;
        try {
            String token = UUID.randomUUID().toString().replace("-", "");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "BoraHapp PIX - loja " + loja.getId());
            // O Asaas exige e-mail no webhook (invalid_email 400) - e para onde ele avisa quando o
            // webhook falha. Vai para quem consegue agir: a plataforma, se configurada; senao o lojista.
            body.put("email", webhookEmail == null || webhookEmail.isBlank() ? email : webhookEmail);
            body.put("url", urlPublica + "/public/pix-webhook/" + loja.getId());
            body.put("enabled", true);
            body.put("interrupted", false);
            body.put("apiVersion", 3);
            body.put("sendType", "SEQUENTIALLY");
            body.put("authToken", token);
            body.put("events", List.of("PAYMENT_RECEIVED", "PAYMENT_CONFIRMED"));
            client(loja.asaasApiKey).post().uri("/webhooks").body(body).retrieve().body(Map.class);
            // So agora: token gravado antes da chamada dava webhook "valido" no nosso lado que o Asaas
            // nunca chamaria. Quem persiste a loja e o chamador.
            loja.asaasWebhookToken = token;
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
        m.put("webhookOk", loja.asaasWebhookToken != null && !loja.asaasWebhookToken.isBlank());
        // Sem onboardingUrl a tela precisa dizer POR ONDE concluir o cadastro: o acesso e por este e-mail.
        if (provisionada) m.put("email", emailDoAdmin(loja));
        return m;
    }

    private void copiar(Map<String, Object> de, Map<String, Object> para, String... chaves) {
        for (String k : chaves) {
            Object v = de.get(k);
            if (v != null && !String.valueOf(v).isBlank()) para.put(k, v);
        }
    }

    /**
     * Checklist de identidade que o Asaas ainda espera da subconta (documento com foto, selfie...).
     * Cada item pode vir com `onboardingUrl` proprio: quando vem, a documentacao do Asaas PROIBE
     * enviar o arquivo por API e manda usar o link. E isso que decide se a nossa tela vai ter
     * upload ou um botao que abre o link do Asaas.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> documentos() {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        Loja loja = lojas.findById(ctx.lojaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja nao encontrada"));
        if (loja.asaasApiKey == null || loja.asaasApiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ative o recebimento antes");
        }
        try {
            Map<String, Object> resp = client(loja.asaasApiKey).get().uri("/myAccount/documents")
                    .retrieve().body(Map.class);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("lojaId", loja.getId());
            out.put("statusDaConta", loja.asaasStatus);
            out.put("asaas", resp);
            return out;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Nao consegui consultar os documentos no Asaas: " + e.getMessage());
        }
    }

    /**
     * Repassa ao Asaas a foto que o lojista tirou na nossa tela (documento ou selfie).
     *
     * <p>O arquivo NAO e gravado em lugar nenhum: vive na memoria durante a requisicao e morre com
     * ela. Guardar RG e selfie de lojista no nosso banco seria responsabilidade de LGPD que nao
     * precisamos ter - quem custodia esses documentos e a instituicao financeira, nao a plataforma.
     * O limiar de multipart no application.yml existe para o Tomcat nao escrever em disco no meio.</p>
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> enviarDocumento(String documentoId, String tipo, MultipartFile arquivo) {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        Loja loja = lojas.findById(ctx.lojaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja nao encontrada"));
        if (loja.asaasApiKey == null || loja.asaasApiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ative o recebimento antes");
        }
        if (arquivo == null || arquivo.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie a foto");
        }
        String mime = arquivo.getContentType() == null ? "" : arquivo.getContentType().toLowerCase();
        if (!ACEITOS.contains(mime)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Formato nao aceito. Envie uma foto JPG ou PNG, ou um PDF.");
        }
        byte[] bytes;
        try {
            bytes = arquivo.getBytes();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nao consegui ler o arquivo enviado");
        }

        String nome = arquivo.getOriginalFilename();
        if (nome == null || nome.isBlank()) nome = "documento" + (mime.contains("pdf") ? ".pdf" : ".jpg");
        final String nomeFinal = nome;

        MultiValueMap<String, Object> corpo = new LinkedMultiValueMap<>();
        if (tipo != null && !tipo.isBlank()) corpo.add("type", tipo);
        corpo.add("documentFile", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return nomeFinal; }
        });

        try {
            Map<String, Object> resp = client(loja.asaasApiKey).post()
                    .uri("/myAccount/documents/{id}", documentoId)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(corpo).retrieve().body(Map.class);
            // Sem nome de arquivo nem conteudo no log: o que interessa e que a loja enviou.
            log.info("Loja {}: documento {} ({}) enviado ao Asaas, {} KB",
                    loja.getId(), documentoId, tipo, bytes.length / 1024);
            return resp == null ? Map.of("enviado", true) : resp;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "O Asaas nao aceitou o arquivo: " + e.getMessage());
        }
    }

    /**
     * Pergunta ao Asaas se o KYC da subconta ja foi aprovado. Existe porque asaasStatus nascia
     * "PENDENTE" e NINGUEM nunca o virava "ATIVO": a tela dizia "falta o KYC" para sempre e o
     * cardapio jamais liberava o PIX, mesmo com a conta ja aprovada do outro lado.
     * Devolve true quando mudou (o chamador persiste).
     */
    @SuppressWarnings("unchecked")
    public boolean atualizarStatus(Loja loja) {
        if (loja.asaasApiKey == null || loja.asaasApiKey.isBlank()) return false;
        if ("ATIVO".equals(loja.asaasStatus)) return false;
        try {
            Map<String, Object> s = client(loja.asaasApiKey).get().uri("/myAccount/status")
                    .retrieve().body(Map.class);
            if (s == null || !aprovada(s)) return false;
            loja.asaasStatus = "ATIVO";
            log.info("Subconta Asaas da loja {} aprovada no KYC - PIX liberado no cardapio", loja.getId());
            return true;
        } catch (Exception e) {
            // Conta ainda em analise, chave invalida ou Asaas fora do ar: mantem o status atual.
            log.debug("Loja {}: nao consegui conferir o status da subconta: {}", loja.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * O Asaas devolve "general" (resumo) + os itens do cadastro. Vale o resumo quando ele vem;
     * senao exige os tres itens que travam o recebimento. Cada valor pode ser texto ou objeto.
     */
    private boolean aprovada(Map<String, Object> s) {
        Object geral = s.get("general");
        if (geral != null) return "APPROVED".equalsIgnoreCase(valor(geral));
        return "APPROVED".equalsIgnoreCase(valor(s.get("commercialInfo")))
                && "APPROVED".equalsIgnoreCase(valor(s.get("documentation")))
                && "APPROVED".equalsIgnoreCase(valor(s.get("bankAccountInfo")));
    }

    @SuppressWarnings("unchecked")
    private String valor(Object o) {
        if (o == null) return "";
        if (o instanceof Map) {
            Object st = ((Map<String, Object>) o).get("status");
            return st == null ? "" : String.valueOf(st);
        }
        return String.valueOf(o);
    }

    /**
     * Sem isto o lojista so descobriria a aprovacao se abrisse a tela de Integracoes: o KYC e
     * aprovado no Asaas, ninguem avisa o nosso lado e o PIX segue escondido no cardapio.
     */
    @Scheduled(initialDelay = 120_000L, fixedDelay = 1_800_000L)
    @Transactional
    public void reconciliarSubcontas() {
        if (!configurado()) return;
        for (Loja loja : lojas.findAll()) {
            if (loja.asaasApiKey == null || loja.asaasApiKey.isBlank()) continue;
            if ("ATIVO".equals(loja.asaasStatus)) continue;
            if (atualizarStatus(loja)) lojas.save(loja);
        }
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
}
