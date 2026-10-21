package br.com.bora.security;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Papel;
import br.com.bora.entity.Usuario;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.repository.UsuarioRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Rebaixar alguém tira o poder dele NA HORA, não em 24 horas.
 *
 * <h2>O defeito (medido em 21/10/2026)</h2>
 * <p>O papel saía do <b>token</b> ({@code c.get("papel")}), e o token vale 24h. O filtro já
 * reconsultava o banco para {@code ativo} e para o vínculo com a loja — <b>o papel tinha ficado de
 * fora</b>. Rebaixar não tirava o poder: um administrador demitido seguia usando o token antigo para
 * trocar a senha de todo mundo — <b>inclusive de quem o rebaixou</b> — e recuperar o acesso. Por até
 * um dia.</p>
 *
 * <p>Todos os endpoints de usuário exigem {@code ADMINISTRADOR_LOJA} via
 * {@code AuthContext.requirePapel}, que lê o papel do {@code BoraPrincipal} — montado aqui. Então é
 * aqui que o furo existia e é aqui que ele fecha.</p>
 *
 * <p><b>Custa zero consulta a mais:</b> o registro do usuário já era carregado neste filtro. Na
 * verdade economiza uma — havia dois {@code findById} do mesmo registro na mesma requisição.</p>
 *
 * <p><b>Não há divergência de desenho entre token e banco:</b> todo token sai de
 * {@code JwtService.gerar(Usuario)}, que lê {@code u.getPapel()}. Ler do banco é a versão mais atual
 * da mesma informação. E o vínculo {@code usuario_loja} <b>não tem papel próprio</b> — conferido na
 * entidade —, então o papel do usuário vale em qualquer loja da rede.</p>
 */
class RebaixarTiraOPoderNaHoraTest {

    private UsuarioRepository usuarios;
    private LojaRepository lojas;
    private UsuarioLojaRepository vinculos;
    private JwtService jwt;
    private JwtAuthFilter filtro;

    private static final Long USER = 7L;
    private static final Long LOJA = 18L;

    @BeforeEach
    void montar() throws Exception {
        usuarios = mock(UsuarioRepository.class);
        lojas = mock(LojaRepository.class);
        vinculos = mock(UsuarioLojaRepository.class);
        // JwtService de verdade: o token tem que ser assinado e lido como em producao. A expiracao e
        // em MILISSEGUNDOS (bora.jwt.expiration-ms), nao em segundos.
        jwt = new JwtService("uma-chave-de-teste-com-tamanho-mais-que-suficiente-para-hmac-256", 86_400_000L);
        filtro = new JwtAuthFilter(jwt, usuarios, lojas, vinculos, mock(RegraDeAcesso.class));

        Loja l = new Loja();
        l.id = LOJA;
        l.ativo = true;
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void limpar() throws Exception {
        SecurityContextHolder.clearContext();
    }

    private Usuario usuario(Papel papel, boolean ativo) {
        Usuario u = new Usuario();
        u.setId(USER);
        u.setLojaId(LOJA);
        u.setEmail("ana@zira.com.br");
        u.setPapel(papel);
        u.setAtivo(ativo);
        return u;
    }

    /** Emite o token com o papel de ENTÃO, e depois o banco passa a dizer outra coisa. */
    private BoraPrincipal passarPeloFiltro(Papel noToken, Usuario noBanco) throws Exception {
        Usuario paraOToken = usuario(noToken, true);
        String token = jwt.gerar(paraOToken, LOJA);
        when(usuarios.findById(USER)).thenReturn(Optional.ofNullable(noBanco));

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer " + token);
        filtro.doFilter(req, mock(HttpServletResponse.class), mock(FilterChain.class));

        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : (BoraPrincipal) auth.getPrincipal();
    }

    // =============================================================== o conserto

    @Test
    void REBAIXADO_oTokenAntigoJAvaleComoOpapelNOVO() throws Exception {
        // O conserto. Antes, este principal saia com ADMINISTRADOR_LOJA por ate 24h.
        var p = passarPeloFiltro(Papel.ADMINISTRADOR_LOJA, usuario(Papel.OPERADOR, true));

        assertNotNull(p, "a sessao continua valida: rebaixar nao e desativar");
        assertEquals("OPERADOR", p.papel(), "o papel tem que ser o do BANCO, nao o do token");
    }

    @Test
    void oADMINdemitidoNAOpassaMaisPeloRequirePapel() throws Exception {
        // O ataque descrito na varredura: trocar a senha de todo mundo, inclusive de quem o
        // rebaixou. Os endpoints de usuario exigem ADMINISTRADOR_LOJA via requirePapel, que le o
        // papel daqui.
        var p = passarPeloFiltro(Papel.ADMINISTRADOR_LOJA, usuario(Papel.OPERADOR, true));

        AuthContext ctx = new AuthContext();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> ctx.requirePapel("ADMINISTRADOR_LOJA"),
                "com o papel do banco, o rebaixado tem que ser recusado");
        assertEquals("OPERADOR", p.papel());
    }

    @Test
    void PROMOVIDO_oTokenAntigoJAvaleComoOpapelNOVO() throws Exception {
        // O outro sentido: quem foi promovido nao precisa esperar o token expirar nem sair e entrar.
        var p = passarPeloFiltro(Papel.OPERADOR, usuario(Papel.ADMINISTRADOR_LOJA, true));

        assertEquals("ADMINISTRADOR_LOJA", p.papel());
    }

    @Test
    void oADMINdaPLATAFORMArebaixadoPASSAaValerComoUsuarioDeLOJA() throws Exception {
        // O `daPlataforma` tambem e lido do banco. Se ficasse no token, o rebaixado continuaria
        // pulando as checagens de loja (suspensao e vinculo) por 24h.
        Loja suspensa = new Loja();
        suspensa.id = LOJA;
        suspensa.ativo = true;
        suspensa.suspensaPelaPlataforma = true;
        when(lojas.findById(LOJA)).thenReturn(Optional.of(suspensa));

        var p = passarPeloFiltro(Papel.ADMINISTRADOR_BORA, usuario(Papel.GERENTE, true));

        assertNull(p, "rebaixado em loja suspensa deixa de pular a checagem de suspensao");
    }

    // =============================================================== o que nao pode mudar

    @Test
    void semMUDANCAnenhuma_oPapelContinuaOmesmo() throws Exception {
        var p = passarPeloFiltro(Papel.GERENTE, usuario(Papel.GERENTE, true));

        assertEquals("GERENTE", p.papel());
        assertEquals(USER, p.userId());
        assertEquals(LOJA, p.lojaId());
        assertEquals("ana@zira.com.br", p.email());
    }

    @Test
    void DESATIVADOcontinuaPerdendoAsessao() throws Exception {
        var p = passarPeloFiltro(Papel.ADMINISTRADOR_LOJA, usuario(Papel.ADMINISTRADOR_LOJA, false));

        assertNull(p, "usuario desativado nao autentica");
    }

    @Test
    void usuarioSEMpapelNAOautentica() throws Exception {
        // Guarda defensivo. Na producao a coluna `papel` e NOT NULL com padrao OPERADOR (conferido em
        // 21/10/2026), entao isto nao acontece hoje -- e por isso a mutacao "sem papel autentica"
        // passou verde na primeira rodada.
        //
        // O guarda fica porque o erro oposto e pior: com papel nulo, o requirePapel compararia nulo
        // com cada papel permitido, nao casaria, e o usuario cairia num 403 confuso em vez de uma
        // sessao recusada. E se alguem tornar a coluna nula um dia, falhar FECHADO e o certo.
        Usuario semPapel = usuario(Papel.GERENTE, true);
        semPapel.setPapel(null);

        var p = passarPeloFiltro(Papel.ADMINISTRADOR_LOJA, semPapel);

        assertNull(p, "sem papel nao se opera nada");
    }

    @Test
    void usuarioAPAGADOcontinuaPerdendoAsessao() throws Exception {
        var p = passarPeloFiltro(Papel.ADMINISTRADOR_LOJA, null);

        assertNull(p);
    }

    @Test
    void oADMINdaPLATAFORMAcontinuaEntrandoEmLojaSUSPENSA() throws Exception {
        // Ele precisa alcancar a loja suspensa para arrumar. Esta regra ja existia e nao pode quebrar.
        Loja suspensa = new Loja();
        suspensa.id = LOJA;
        suspensa.ativo = true;
        suspensa.suspensaPelaPlataforma = true;
        when(lojas.findById(LOJA)).thenReturn(Optional.of(suspensa));

        var p = passarPeloFiltro(Papel.ADMINISTRADOR_BORA, usuario(Papel.ADMINISTRADOR_BORA, true));

        assertNotNull(p, "o suporte tem que alcancar a loja suspensa");
        assertEquals("ADMINISTRADOR_BORA", p.papel());
    }

    @Test
    void lojaSUSPENSAcontinuaDerrubandoOusuarioDeLOJA() throws Exception {
        Loja suspensa = new Loja();
        suspensa.id = LOJA;
        suspensa.ativo = true;
        suspensa.suspensaPelaPlataforma = true;
        when(lojas.findById(LOJA)).thenReturn(Optional.of(suspensa));

        var p = passarPeloFiltro(Papel.GERENTE, usuario(Papel.GERENTE, true));

        assertNull(p);
    }

    @Test
    void semVINCULOcomALOJAdoTokenContinuaDerrubando() throws Exception {
        // Token emitido para uma loja da rede que nao e a principal: vale enquanto o vinculo existir.
        Usuario doBanco = usuario(Papel.GERENTE, true);
        doBanco.setLojaId(99L);                       // a principal dele e outra
        when(vinculos.existsByUsuarioIdAndLojaId(USER, LOJA)).thenReturn(false);

        var p = passarPeloFiltro(Papel.GERENTE, doBanco);

        assertNull(p, "sem vinculo, o token daquela loja nao vale mais");
    }

    @Test
    void comVINCULOcomALOJAdoTokenContinuaValendo() throws Exception {
        Usuario doBanco = usuario(Papel.GERENTE, true);
        doBanco.setLojaId(99L);
        when(vinculos.existsByUsuarioIdAndLojaId(USER, LOJA)).thenReturn(true);

        var p = passarPeloFiltro(Papel.GERENTE, doBanco);

        assertNotNull(p);
        assertEquals(LOJA, p.lojaId(), "o lojaId continua vindo do TOKEN: e a loja desta sessao");
    }

    @Test
    void semTOKENnaoAutentica() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn(null);

        filtro.doFilter(req, mock(HttpServletResponse.class), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void tokenASSINADOporOUTRAchaveNaoAutentica() throws Exception {
        JwtService outro = new JwtService("uma-OUTRA-chave-de-teste-com-tamanho-mais-que-suficiente-256", 86_400_000L);
        String tokenFalso = outro.gerar(usuario(Papel.ADMINISTRADOR_BORA, true), LOJA);
        when(usuarios.findById(USER)).thenReturn(Optional.of(usuario(Papel.OPERADOR, true)));

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer " + tokenFalso);
        filtro.doFilter(req, mock(HttpServletResponse.class), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // =============================================================== uma consulta, nao duas

    @Test
    void oUSUARIOehCarregadoUMAvezPorRequISICAO() throws Exception {
        // Havia dois findById do mesmo registro na mesma requisicao: um para `ativo`, outro para a
        // loja principal. O conserto reusa o que ja estava carregado.
        Usuario doBanco = usuario(Papel.GERENTE, true);
        doBanco.setLojaId(99L);
        when(vinculos.existsByUsuarioIdAndLojaId(USER, LOJA)).thenReturn(true);

        passarPeloFiltro(Papel.GERENTE, doBanco);

        verify(usuarios, times(1)).findById(USER);
    }
}
