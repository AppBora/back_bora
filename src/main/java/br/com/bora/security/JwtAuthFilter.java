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
    private final br.com.bora.repository.UsuarioLojaRepository vinculos;

    private final RegraDeAcesso regra;

    public JwtAuthFilter(JwtService jwt, br.com.bora.repository.UsuarioRepository usuarios,
                         br.com.bora.repository.LojaRepository lojas,
                         br.com.bora.repository.UsuarioLojaRepository vinculos,
                         RegraDeAcesso regra) {
        this.regra = regra;
        this.vinculos = vinculos;
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
                // O usuário é carregado UMA vez e reusado. Antes havia dois findById do mesmo registro
                // na mesma requisição (um para `ativo`, outro para a loja principal).
                var usuario = usuarios.findById(userId).orElse(null);
                boolean ativo = usuario != null && Boolean.TRUE.equals(usuario.getAtivo());

                // O PAPEL VEM DO BANCO, NÃO DO TOKEN.
                //
                // O defeito que isto fecha (medido em 21/10/2026): o papel saía do token
                // (`c.get("papel")`) e o token vale 24h. O filtro já reconsultava o banco para
                // `ativo` e para o vínculo com a loja — e o papel tinha ficado de fora. Rebaixar
                // alguém não tirava o poder dele: um administrador demitido seguia trocando a senha
                // de todo mundo (inclusive de quem o rebaixou) e recuperando o acesso, por até um dia.
                // O contorno era DESATIVAR em vez de rebaixar, o que nem sempre é o que se quer.
                //
                // Custa zero consulta a mais: o registro já estava carregado aqui.
                //
                // Nenhum token nasce com papel diferente do banco -- todos saem de
                // `JwtService.gerar(Usuario)`, que lê `u.getPapel()`. Então ler do banco é só a versão
                // mais atual da MESMA informação, nunca uma divergência de desenho.
                String papel = ativo && usuario.getPapel() != null ? usuario.getPapel().name() : null;
                if (papel == null) ativo = false;   // sem papel não se opera nada

                Number loja = c.get("lojaId", Number.class);
                // Suspensão/arquivamento pela plataforma vale a partir do request seguinte: o lojaId
                // viaja assinado no token, então sem esta checagem o token já emitido continuaria
                // valendo por até 24h depois de o cliente ser cortado.
                // O administrador da plataforma entra em qualquer loja pelo acesso de suporte: não
                // tem vínculo com nenhuma, e precisa alcançar inclusive a loja suspensa para
                // arrumá-la. As duas checagens abaixo valem para os usuários de loja.
                //
                // Lido do BANCO também: se um administrador da plataforma for rebaixado, ele passa a
                // valer como usuário de loja na requisição seguinte, e aí as duas checagens abaixo
                // têm que valer para ele.
                boolean daPlataforma = "ADMINISTRADOR_BORA".equals(papel);
                if (ativo && loja != null && !daPlataforma) {
                    Long lojaId = loja.longValue();
                    // So a decisao da plataforma derruba a sessao inteira. O prazo de acesso vencido
                    // e tratado pelo BloqueioPorAssinaturaFilter, que responde 402 e deixa o lojista
                    // chegar na tela de assinatura em vez de ficar sem saida.
                    ativo = lojas.findById(lojaId)
                            .map(l -> !l.bloqueadaPelaPlataforma())
                            .orElse(false);
                    // Token emitido para uma loja que não é a principal do usuário só vale enquanto
                    // o vínculo existir: sem isto, quem perde o acesso a uma loja da rede continua
                    // entrando nela até o token expirar (24h). Consulta por chave primária.
                    if (ativo) {
                        Long principal = usuario.getLojaId();
                        if (!lojaId.equals(principal)) {
                            ativo = vinculos.existsByUsuarioIdAndLojaId(userId, lojaId);
                        }
                    }
                }
                if (ativo) {
                    BoraPrincipal principal = new BoraPrincipal(
                            userId,
                            loja == null ? null : loja.longValue(),
                            papel,
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
