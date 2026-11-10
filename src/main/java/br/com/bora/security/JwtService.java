package br.com.bora.security;

import br.com.bora.entity.Usuario;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Service
public class JwtService {

    private final SecretKey key;
    private final long expMs;

    /** O valor que ficava como padrao no codigo, e que agora e motivo para RECUSAR a subida. */
    private static final String PADRAO_ANTIGO = "troque-este-segredo-em-producao-com-no-minimo-32-bytes!!";

    /** Minimo do HMAC-SHA256. Abaixo disto a propria biblioteca recusa, mas com mensagem obscura. */
    private static final int MINIMO_BYTES = 32;

    public JwtService(
            @Value("${bora.jwt.secret:}") String secret,
            @Value("${bora.jwt.expiration-ms:86400000}") long expMs) {
        this.key = Keys.hmacShaKeyFor(exigirSegredoDeVerdade(secret).getBytes(StandardCharsets.UTF_8));
        this.expMs = expMs;
    }

    /**
     * Confere o segredo do JWT e <b>impede a aplicacao de subir</b> quando ele nao presta.
     *
     * <h2>O defeito que isto fecha (confirmado em 10/11/2026)</h2>
     * <p>Faltando a variavel, isto aqui <b>deixava a aplicacao subir</b> com o segredo
     * {@code troque-este-segredo-...} -- um valor que esta <b>publicado no repositorio</b> -- e so
     * escrevia um {@code log.warn}. Quem conhecesse o repositorio forjaria um token de qualquer
     * usuario, inclusive do admin da plataforma: o {@code validar()} aceita qualquer coisa assinada
     * com essa chave. Um aviso no log nao protege nada; ele some na primeira pagina de log e a API
     * segue atendendo.</p>
     *
     * <p><b>Neste projeto isso nao e teorico:</b> em 30/10/2026 a chave de cifragem das colunas
     * pareceu ausente porque {@code docker restart} <b>nao rele o {@code --env-file}</b>. A mesma
     * coisa com o JWT teria passado em silencio, com a API no ar e assinando com a chave publica.</p>
     *
     * <p>Falhar fechado e o padrao do resto do sistema: o {@code SegredoGuardado} recusa a subida
     * quando ha credencial cifrada no banco e a chave nao esta definida. Aqui era a excecao.</p>
     *
     * <p>A mensagem <b>nao imprime o valor</b>, apenas o nome da variavel -- e o que vale para todo
     * segredo neste projeto.</p>
     *
     * @return o proprio segredo, quando presta
     * @throws IllegalStateException quando esta vazio, e o padrao antigo, ou e curto demais
     */
    public static String exigirSegredoDeVerdade(String secret) {
        String s = secret == null ? "" : secret.trim();
        if (s.isEmpty()) {
            throw new IllegalStateException(
                    "BORA_JWT_SECRET nao esta definido. A API NAO vai subir: sem ela, os tokens "
                    + "seriam assinados com um segredo publicado no repositorio, e qualquer pessoa "
                    + "que o conheca forjaria um token de admin da plataforma. Defina a variavel no "
                    + "api.env (no minimo " + MINIMO_BYTES + " bytes) e RECRIE o container -- "
                    + "`docker restart` NAO rele o --env-file.");
        }
        if (s.equals(PADRAO_ANTIGO) || s.startsWith("troque-")) {
            throw new IllegalStateException(
                    "BORA_JWT_SECRET esta com o valor de exemplo que vem no codigo. A API NAO vai "
                    + "subir: esse valor esta publicado no repositorio, e assinar token com ele e o "
                    + "mesmo que nao assinar. Gere uma chave aleatoria de " + MINIMO_BYTES
                    + " bytes ou mais.");
        }
        if (s.getBytes(StandardCharsets.UTF_8).length < MINIMO_BYTES) {
            throw new IllegalStateException(
                    "BORA_JWT_SECRET tem menos de " + MINIMO_BYTES + " bytes, que e o minimo do "
                    + "HMAC-SHA256. A API NAO vai subir. (A mensagem nao mostra o valor, de "
                    + "proposito.)");
        }
        return s;
    }

    public String gerar(Usuario u) {
        return gerar(u, u.getLojaId());
    }

    /** Gera token com contexto de loja específico (troca de loja na rede — vínculo já validado). */
    public String gerar(Usuario u, Long lojaId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(u.getId()))
                .claim("email", u.getEmail())
                .claim("papel", u.getPapel().name())
                .claim("lojaId", lojaId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expMs))
                .signWith(key)
                .compact();
    }

    public Jws<Claims> validar(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
    }
}
