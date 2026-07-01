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

    public JwtService(
            @Value("${bora.jwt.secret:troque-este-segredo-em-producao-com-no-minimo-32-bytes!!}") String secret,
            @Value("${bora.jwt.expiration-ms:86400000}") long expMs) {
        if (secret.startsWith("troque-")) {
            log.warn("⚠️  BORA_JWT_SECRET não definido — usando o segredo PADRÃO INSEGURO. Defina uma chave forte em produção!");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expMs = expMs;
    }

    public String gerar(Usuario u) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(u.getId()))
                .claim("email", u.getEmail())
                .claim("papel", u.getPapel().name())
                .claim("lojaId", u.getLojaId())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expMs))
                .signWith(key)
                .compact();
    }

    public Jws<Claims> validar(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
    }
}
