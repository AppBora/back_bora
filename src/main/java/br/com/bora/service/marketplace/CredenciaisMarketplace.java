package br.com.bora.service.marketplace;

import br.com.bora.entity.ConfigPlataforma;
import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.repository.ConfigPlataformaRepository;
import br.com.bora.repository.IntegracaoCanalRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Credenciais do aplicativo da PLATAFORMA em cada marketplace (Client ID/Secret do iFood, App ID/Secret
 * da 99). Existe para o dono colar as chaves numa tela do painel — antes o único caminho era alguém
 * digitar o segredo no api.env do servidor.
 *
 * <p>O que foi salvo pela tela (tabela config_plataforma, chaves {@code credencial.*}) vale mais que o
 * do servidor; o do servidor continua funcionando como padrão. O segredo nunca sai daqui para a tela:
 * quem lista só fica sabendo SE ele existe.</p>
 */
@Service
public class CredenciaisMarketplace {

    /** Canal → nome da credencial nas chaves de config (ifood.client-id, opendelivery.client-secret…). */
    public static final Map<String, String> CANAIS = new LinkedHashMap<>() {{
        put("IFOOD", "ifood");
        put("NOVE_NOVE", "opendelivery");
    }};

    private static final String PREFIXO = "credencial.";

    private final ConfigPlataformaRepository configs;
    private final IntegracaoCanalRepository integracoes;
    private final Map<String, String> doServidor = new HashMap<>();

    public CredenciaisMarketplace(ConfigPlataformaRepository configs, IntegracaoCanalRepository integracoes,
                                  @Value("${marketplace.ifood.client-id:}") String ifoodId,
                                  @Value("${marketplace.ifood.client-secret:}") String ifoodSecret,
                                  @Value("${marketplace.opendelivery.client-id:}") String odId,
                                  @Value("${marketplace.opendelivery.client-secret:}") String odSecret) {
        this.configs = configs;
        this.integracoes = integracoes;
        doServidor.put("ifood.client-id", ifoodId);
        doServidor.put("ifood.client-secret", ifoodSecret);
        doServidor.put("opendelivery.client-id", odId);
        doServidor.put("opendelivery.client-secret", odSecret);
    }

    /** Chaves de credencial não podem sair pela listagem geral de configurações da plataforma. */
    public static boolean ehChaveDeCredencial(String chave) {
        return chave != null && chave.startsWith(PREFIXO);
    }

    public String clientId(String canal) {
        return valor(canal, "client-id");
    }

    public String clientSecret(String canal) {
        return valor(canal, "client-secret");
    }

    public boolean secretNaTela(String canal) {
        return daTela(canal, "client-secret") != null;
    }

    /**
     * QUANTOS caracteres tem o segredo salvo — nunca quais.
     *
     * <h2>Por que o tamanho sai, se o valor nao sai</h2>
     * <p>Foi exatamente o que resolveu o caso de 25/10/2026. A loja 1 estava com o 99Food em 401 por
     * dias, e a causa so ficou visivel comparando tamanhos: o segredo da plataforma tem 13
     * caracteres e o do unico app de 99Food que funciona neste sistema tem 32. Sem esse numero, a
     * tela diz "segredo salvo ✓" e o dono nao tem como desconfiar do que esta salvo.</p>
     *
     * <h2>O que isso custa, dito com honestidade</h2>
     * <p>Tamanho de segredo e informacao a menos do que zero. Estreita de leve uma tentativa de
     * adivinhacao. Mas o endpoint exige {@code ADMINISTRADOR_BORA}, o segredo e do proprio dono, e
     * sao chaves de API (nao senha de pessoa, nao reusada em outro lugar). O risco e pequeno e o
     * ganho e concreto: foi este numero que transformou "nao chega pedido" em "o segredo esta
     * errado". O VALOR continua nao saindo nunca.</p>
     *
     * <p>Devolve 0 quando nao ha segredo salvo.</p>
     */
    public int tamanhoDoSecret(String canal) {
        String v = valor(canal, "client-secret");
        return v == null ? 0 : v.length();
    }

    /**
     * Quando o segredo salvo PELA TELA foi gravado — null quando vem do servidor ou nao existe.
     *
     * <p>Responde a pergunta que o dono fez de verdade: "isso aqui e de quando?". Credencial salva na
     * epoca da homologacao e credencial salva hoje tem o mesmo jeito na tela, e a data e a unica
     * coisa que as separa.</p>
     */
    public OffsetDateTime secretAtualizadoEm(String canal) {
        return configs.findById(chave(canal, "client-secret"))
                .map(ConfigPlataforma::getAtualizadoEm)
                .orElse(null);
    }

    /** "TELA" (salvas pelo painel), "SERVIDOR" (api.env) ou null (nenhuma). */
    public String origem(String canal) {
        if (daTela(canal, "client-id") != null) return "TELA";
        if (doServidor(canal, "client-id") != null && doServidor(canal, "client-secret") != null) return "SERVIDOR";
        return null;
    }

    /** Segredo em branco mantém o que já estava salvo. */
    public void salvar(String canal, String clientId, String clientSecret) {
        gravar(canal, "client-id", clientId);
        if (clientSecret != null && !clientSecret.isBlank()) gravar(canal, "client-secret", clientSecret);
        esquecerTokens(canal);
    }

    /** Apaga o que foi salvo pela tela — volta a valer a credencial do servidor, se houver. */
    public void limpar(String canal) {
        configs.deleteById(chave(canal, "client-id"));
        configs.deleteById(chave(canal, "client-secret"));
        esquecerTokens(canal);
    }

    /**
     * No Open Delivery o token é pedido com o App ID/Secret a cada vez (client_credentials): trocar a
     * credencial e manter o token antigo faria a loja seguir no app velho até o token vencer. No iFood o
     * token vem da autorização do lojista e a renovação dele depende do mesmo app — não mexemos: se o app
     * mudar, a loja precisa conectar de novo, e o erro aparece no card.
     */
    private void esquecerTokens(String canal) {
        if (!"NOVE_NOVE".equals(canal)) return;
        for (IntegracaoCanal i : integracoes.findByCanal(canal)) {
            i.accessToken = null;
            i.tokenExpiraEm = null;
            integracoes.save(i);
        }
    }

    private String valor(String canal, String campo) {
        String v = daTela(canal, campo);
        return v != null ? v : doServidor(canal, campo);
    }

    private String daTela(String canal, String campo) {
        return configs.findById(chave(canal, campo))
                .map(ConfigPlataforma::getValor)
                .filter(v -> !v.isBlank())
                .orElse(null);
    }

    private String doServidor(String canal, String campo) {
        String nome = CANAIS.get(canal);
        String v = nome == null ? null : doServidor.get(nome + "." + campo);
        return v == null || v.isBlank() ? null : v;
    }

    private void gravar(String canal, String campo, String valor) {
        ConfigPlataforma c = configs.findById(chave(canal, campo)).orElseGet(() -> {
            ConfigPlataforma nova = new ConfigPlataforma();
            nova.setChave(chave(canal, campo));
            return nova;
        });
        c.setValor(valor.trim());
        c.setAtualizadoEm(OffsetDateTime.now());
        configs.save(c);
    }

    private static String chave(String canal, String campo) {
        String nome = CANAIS.get(canal);
        if (nome == null) throw new IllegalArgumentException("Canal sem credencial de plataforma: " + canal);
        return PREFIXO + nome + "." + campo;
    }
}
