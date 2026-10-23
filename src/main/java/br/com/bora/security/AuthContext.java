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

    /**
     * O papel atual esta na lista? <b>Pergunta</b>, nao bloqueia.
     *
     * <h2>Por que isto existe (item 8.3 da varredura)</h2>
     * <p>Numero de dono — faturamento, custo, margem — nao deve sair para o balconista. Mas as telas
     * que ele USA precisam da mesma consulta: a Frente de Caixa pede {@code GET /api/produtos} para
     * ter nome, preco e estoque, e esse mesmo retorno levava o <b>custo</b> de cada produto. Barrar a
     * consulta inteira com {@code requirePapel} tiraria o PDV do balconista; o certo e <b>omitir o
     * campo</b> que ele nao pode ver, e nao negar a tela.</p>
     *
     * <p>Entao: {@code requirePapel} para quando a ACAO e proibida, {@code temPapel} para quando o
     * que muda e o CONTEUDO da resposta.</p>
     */
    public boolean temPapel(String... papeisPermitidos) {
        if (isAdminBora()) return true;
        String p = papel();
        for (String x : papeisPermitidos) {
            if (x.equals(p)) return true;
        }
        return false;
    }

    /** Quem pode ver dinheiro da loja: faturamento, custo, margem, lucro. */
    public boolean podeVerNumeroDeDono() {
        return temPapel("ADMINISTRADOR_LOJA", "GERENTE");
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
