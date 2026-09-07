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

    /**
     * Loja do contexto atual. O ADMINISTRADOR_BORA logado na plataforma não tem loja: para ele,
     * qualquer tela de operação precisa dizer o que fazer em vez de quebrar.
     *
     * <p>Antes desta guarda, sessão sem loja produzia dois estragos silenciosos: telas de leitura
     * devolviam lista vazia (parecia loja sem cadastro) e as que semeiam padrões — formas de
     * pagamento, horários, motivos — tentavam inserir com loja_id nulo e estouravam 500.</p>
     */
    public Long lojaId() {
        Long id = atual().lojaId();
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Nenhuma loja selecionada. Em Configurações › Plataforma, use \"Entrar na loja\" "
                            + "do cliente antes de abrir as telas de operação.");
        }
        return id;
    }

    /** Loja do contexto, ou null quando é a plataforma — para quem sabe lidar com os dois casos. */
    public Long lojaIdOuNulo() {
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
