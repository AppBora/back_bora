package br.com.bora.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Acesso à identidade do usuário autenticado e checagens de papel. */
@Component
public class AuthContext {

    public BoraPrincipal atual() {
        var a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof BoraPrincipal p)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Não autenticado");
        }
        return p;
    }

    public Long lojaId() {
        return atual().lojaId();
    }

    public String papel() {
        return atual().papel();
    }

    public boolean isAdminBora() {
        return "ADMINISTRADOR_BORA".equals(papel());
    }

    /** Bloqueia (403) se não for o administrador da plataforma. */
    public void requireAdminBora() {
        if (!isAdminBora()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Restrito ao administrador da plataforma");
        }
    }

    /** Bloqueia (403) se o papel atual não estiver na lista. ADMINISTRADOR_BORA sempre pode. */
    public void requirePapel(String... papeisPermitidos) {
        if (isAdminBora()) return;
        String p = papel();
        for (String x : papeisPermitidos) {
            if (x.equals(p)) return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ação não permitida para o seu perfil");
    }
}
