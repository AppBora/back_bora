package br.com.bora.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final br.com.bora.repository.UsuarioRepository usuarios;
    private final br.com.bora.repository.LojaRepository lojas;

    public JwtAuthFilter(JwtService jwt, br.com.bora.repository.UsuarioRepository usuarios,
                         br.com.bora.repository.LojaRepository lojas) {
        this.jwt = jwt;
        this.usuarios = usuarios;
        this.lojas = lojas;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest req, @NonNull HttpServletResponse res,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims c = jwt.validar(header.substring(7)).getPayload();
                Long userId = Long.valueOf(c.getSubject());
                boolean ativo = usuarios.findById(userId).map(u -> Boolean.TRUE.equals(u.getAtivo())).orElse(false);
                Number loja = c.get("lojaId", Number.class);
                // Suspensão/arquivamento pela plataforma vale a partir do request seguinte: o lojaId
                // viaja assinado no token, então sem esta checagem o token já emitido continuaria
                // valendo por até 24h depois de o cliente ser cortado.
                if (ativo && loja != null) {
                    ativo = lojas.findById(loja.longValue())
                            .map(l -> !l.bloqueadaPelaPlataforma())
                            .orElse(false);
                }
                if (ativo) {
                    BoraPrincipal principal = new BoraPrincipal(
                            userId,
                            loja == null ? null : loja.longValue(),
                            c.get("papel", String.class),
                            c.get("email", String.class));
                    var auth = new UsernamePasswordAuthenticationToken(
                            principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + principal.papel())));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } else {
                    SecurityContextHolder.clearContext(); // usuário desativado: token deixa de valer
                }
            } catch (Exception e) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(req, res);
    }
}
