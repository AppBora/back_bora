package br.com.bora.security;

import br.com.bora.repository.LojaRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Tranca o painel da loja cujo prazo de acesso venceu — menos o caminho de pagar.
 *
 * <p>Bloquear o login inteiro seria um beco sem saída: o lojista veria "pague para continuar" e não
 * teria por onde pagar. Então ele entra, mas só alcança a tela de assinatura; qualquer outra chamada
 * recebe <b>402 Payment Required</b>, e o painel usa esse código para levar direto a Planos.</p>
 *
 * <p>Não toca em quem está em dia, em quem não tem prazo, nem no administrador da plataforma — que
 * precisa alcançar justamente a loja bloqueada para resolver. Enquanto
 * {@code bora.cobranca.corte-por-assinatura} estiver desligado, este filtro não bloqueia ninguém.</p>
 */
@Component
public class BloqueioPorAssinaturaFilter extends OncePerRequestFilter {

    /**
     * O que continua aberto para quem está bloqueado: ver o plano, assinar, carregar a marca da loja
     * e <b>aceitar os Termos</b>.
     *
     * <p>Os Termos ficaram de fora e isso virava um nó: a loja vencida tomava 402 ao consultar o
     * aceite, a faixa do painel nunca aparecia, e ela não conseguia aceitar justamente o documento que
     * rege a suspensão que a bloqueou. Quem está prestes a pagar para voltar precisa poder aceitar.</p>
     */
    private static final String[] CAMINHOS_LIVRES = {
            "/api/assinatura", "/api/plano", "/api/configuracao", "/api/termos", "/auth/"
    };

    private final LojaRepository lojas;
    private final RegraDeAcesso regra;

    public BloqueioPorAssinaturaFilter(LojaRepository lojas, RegraDeAcesso regra) {
        this.lojas = lojas;
        this.regra = regra;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest req, @NonNull HttpServletResponse res,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if (!regra.cortePorAssinaturaLigado()) { chain.doFilter(req, res); return; }

        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof BoraPrincipal p) || p.lojaId() == null
                || "ADMINISTRADOR_BORA".equals(p.papel())) {
            chain.doFilter(req, res);
            return;
        }

        String caminho = req.getRequestURI();
        for (String livre : CAMINHOS_LIVRES) {
            if (caminho.startsWith(livre)) { chain.doFilter(req, res); return; }
        }

        boolean vencido = lojas.findById(p.lojaId()).map(l -> l.acessoVencido()).orElse(false);
        if (!vencido) { chain.doFilter(req, res); return; }

        res.setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"message\":\"" + RegraDeAcesso.RECADO_PRAZO + "\"}");
    }
}
