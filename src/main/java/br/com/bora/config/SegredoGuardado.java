package br.com.bora.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifra em repouso as credenciais de TERCEIROS que ficam no banco.
 *
 * <h2>O problema</h2>
 * <p>{@code loja.asaas_api_key}, {@code integracao_canal.client_secret}, {@code access_token} e
 * {@code refresh_token} estavam em texto puro. Essas chaves movimentam o dinheiro DO LOJISTA e
 * autorizam pedidos no iFood e na 99 em nome dele — não são nossas. E elas saem do banco em todo
 * {@code pg_dump}: os 17 dumps diários vão para o PC do dono, dentro de uma pasta sincronizada pelo
 * OneDrive. Com uma loja isso é um descuido; com vinte, é custódia de credencial financeira de
 * terceiro em arquivo sincronizado para a nuvem.</p>
 *
 * <h2>Como funciona</h2>
 * <p>AES-256/GCM, com a chave derivada de {@code BORA_SEGREDO_COLUNAS} (variável de ambiente, nunca no
 * banco). O valor gravado fica {@code enc:v1:<base64(nonce||cifra)>}. O GCM autentica: valor adulterado
 * não decifra, estoura.</p>
 *
 * <h2>Por que ele funciona SEM a chave configurada</h2>
 * <p>Sem {@code BORA_SEGREDO_COLUNAS}, grava e lê em texto puro — exatamente como antes. Isso permite
 * subir este código sem coordenar com a configuração do servidor, e é de propósito:
 * <ul>
 *   <li><b>na leitura</b>, o prefixo decide. Valor com {@code enc:v1:} é decifrado; valor antigo, em
 *       texto puro, volta como está. Então ligar a chave não quebra o que já está gravado.</li>
 *   <li><b>na escrita</b>, cifra só se houver chave.</li>
 * </ul>
 * Os valores antigos passam a ser cifrados quando forem regravados (o lojista salvar a integração, o
 * marketplace renovar o token). Nada de migração de dados com segredo dentro.</p>
 *
 * <h2>O risco, dito claramente</h2>
 * <p><b>Perder a chave é perder as credenciais.</b> Depois de ligada, o que foi cifrado com ela só
 * decifra com ela: sem backup da chave, cada lojista precisa reconectar iFood, 99 e PIX do zero. A
 * chave tem que estar no gerenciador de senhas junto com o {@code api.env}, e trocá-la exige regravar
 * tudo antes.</p>
 *
 * <p>Para gerar: {@code openssl rand -base64 48}.</p>
 */
@Slf4j
@Converter
public class SegredoGuardado implements AttributeConverter<String, String> {

    private static final String PREFIXO = "enc:v1:";
    private static final String ALGORITMO = "AES/GCM/NoPadding";
    private static final int BITS_DA_TAG = 128;
    private static final int BYTES_DO_NONCE = 12; // o recomendado para GCM

    /**
     * Estático porque o Hibernate instancia o conversor sozinho, fora do Spring: não há injeção aqui.
     * Quem preenche é o {@link ChaveDasColunas}, no start da aplicação.
     */
    private static volatile SecretKey chave;

    private static final SecureRandom ALEATORIO = new SecureRandom();

    /**
     * Chamado uma vez no start pelo {@link ChaveDasColunas}. Valor vazio deixa tudo em texto puro.
     *
     * <p>Publico porque o teste de integracao mora em outro pacote e precisa ligar e desligar a chave
     * para provar os dois comportamentos. Nao e para ser chamado de mais nenhum lugar.</p>
     */
    public static void usarChave(String segredo) {
        if (segredo == null || segredo.isBlank()) {
            chave = null;
            log.warn("⚠️  BORA_SEGREDO_COLUNAS não definido: as credenciais de Asaas e marketplace "
                    + "continuam em TEXTO PURO no banco e nos dumps. Ver SegredoGuardado.");
            return;
        }
        try {
            // Deriva 32 bytes do segredo informado, para aceitar qualquer tamanho de entrada.
            byte[] bruto = MessageDigest.getInstance("SHA-256")
                    .digest(("bora-colunas:" + segredo).getBytes(StandardCharsets.UTF_8));
            chave = new SecretKeySpec(bruto, "AES");
            log.info("Credenciais de terceiros cifradas em repouso: LIGADO (AES-256/GCM)");
        } catch (Exception e) {
            throw new IllegalStateException("falha ao preparar a chave das colunas", e);
        }
    }

    public static boolean ligado() {
        return chave != null;
    }

    @Override
    public String convertToDatabaseColumn(String valor) {
        if (valor == null || valor.isEmpty() || chave == null) return valor;
        if (valor.startsWith(PREFIXO)) return valor; // já cifrado; não cifra duas vezes
        try {
            byte[] nonce = new byte[BYTES_DO_NONCE];
            ALEATORIO.nextBytes(nonce);
            Cipher c = Cipher.getInstance(ALGORITMO);
            c.init(Cipher.ENCRYPT_MODE, chave, new GCMParameterSpec(BITS_DA_TAG, nonce));
            byte[] cifra = c.doFinal(valor.getBytes(StandardCharsets.UTF_8));
            byte[] junto = new byte[nonce.length + cifra.length];
            System.arraycopy(nonce, 0, junto, 0, nonce.length);
            System.arraycopy(cifra, 0, junto, nonce.length, cifra.length);
            return PREFIXO + Base64.getEncoder().encodeToString(junto);
        } catch (Exception e) {
            // Falhar aqui apagaria a credencial do lojista. Melhor gravar em claro e gritar no log.
            log.error("Falha ao cifrar credencial; gravando em texto puro. Confira BORA_SEGREDO_COLUNAS.", e);
            return valor;
        }
    }

    @Override
    public String convertToEntityAttribute(String doBanco) {
        if (doBanco == null || !doBanco.startsWith(PREFIXO)) return doBanco; // valor antigo, em claro
        if (chave == null) {
            // Cifrado no banco e sem chave para abrir: a integração vai falhar, e o motivo tem que
            // aparecer. Devolver o texto cifrado faria o Asaas recusar com "chave inválida", e a
            // investigação iria para o lado errado.
            throw new IllegalStateException("há credencial cifrada no banco e BORA_SEGREDO_COLUNAS não "
                    + "está definido. Sem a chave, essas credenciais não abrem.");
        }
        try {
            byte[] junto = Base64.getDecoder().decode(doBanco.substring(PREFIXO.length()));
            byte[] nonce = java.util.Arrays.copyOf(junto, BYTES_DO_NONCE);
            Cipher c = Cipher.getInstance(ALGORITMO);
            c.init(Cipher.DECRYPT_MODE, chave, new GCMParameterSpec(BITS_DA_TAG, nonce));
            byte[] claro = c.doFinal(junto, BYTES_DO_NONCE, junto.length - BYTES_DO_NONCE);
            return new String(claro, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("credencial cifrada não abriu: a chave mudou ou o valor "
                    + "foi adulterado (o GCM autentica o conteúdo)", e);
        }
    }
}
