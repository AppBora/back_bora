package br.com.bora.security;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Papel;
import br.com.bora.entity.Usuario;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.repository.UsuarioRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TROCAR A SENHA DERRUBA O TOKEN. (item 1 da secao 4 da varredura; confirmado em 19/11/2026,
 * consertado e aprovado pelo dono em 20/11/2026)
 *
 * <h2>O defeito</h2>
 * <p>O token carregava {@code email}, {@code papel}, {@code lojaId} e validade -- e <b>nenhuma
 * marca da senha</b>. Ele vale 24 h. Logo, um token vazado continuava valendo por ate um dia
 * <b>depois</b> de a senha ser trocada -- e trocar a senha e exatamente o que a pessoa faz ao
 * desconfiar do vazamento. O unico corte de verdade era DESATIVAR o usuario, que nem sempre e o que
 * se quer: a pessoa continua trabalhando ali.</p>
 *
 * <h2>Por que isto desloga todo mundo uma vez</h2>
 * <p>Token emitido ANTES do deploy nao tem a marca, e sem marca o filtro recusa. O dono aprovou
 * isso de olhos abertos em 20/11/2026. A alternativa -- aceitar token sem marca -- deixaria o
 * defeito aberto por 24 h justamente para os tokens que ja estao por aí, que sao os unicos que
 * poderiam ter vazado antes do conserto.</p>
 *
 * <p>Este e o teste gemeo do {@link RebaixarTiraOPoderNaHoraTest}: mesmo desenho (o BANCO decide,
 * nao o token) e mesma classe de defeito.</p>
 */
class TrocarASENHAderrubaOtokenTest {

    private static final String SEGREDO = "uma-chave-de-teste-com-tamanho-mais-que-suficiente-para-hmac-256";
    private static final Long USER = 7L;
    private static final Long LOJA = 18L;

    private UsuarioRepository usuarios;
    private LojaRepository lojas;
    private JwtService jwt;
    private JwtAuthFilter filtro;

    @BeforeEach
    void montar() {
        usuarios = mock(UsuarioRepository.class);
        lojas = mock(LojaRepository.class);
        // JwtService de verdade: o token tem de ser assinado e lido como em producao.
        jwt = new JwtService(SEGREDO, 86_400_000L);
        filtro = new JwtAuthFilter(jwt, usuarios, lojas, mock(UsuarioLojaRepository.class),
                mock(RegraDeAcesso.class));

        Loja l = new Loja();
        l.id = LOJA;
        l.ativo = true;
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void limpar() {
        SecurityContextHolder.clearContext();
    }

    /** Usuario com a versao de senha que se quiser, sem passar pelo setter (para montar o cenario). */
    private Usuario usuarioNaVersao(int versao) {
        Usuario u = new Usuario();
        u.setId(USER);
        u.setEmail("maria@loja.com");
        u.setPapel(Papel.ADMINISTRADOR_LOJA);
        u.setAtivo(true);
        u.setLojaId(LOJA);
        u.setVersaoSenha(versao);
        return u;
    }

    private BoraPrincipal autenticarCom(String token) throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer " + token);
        filtro.doFilter(req, mock(HttpServletResponse.class), mock(FilterChain.class));
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : (BoraPrincipal) auth.getPrincipal();
    }

    // =================================================================== a premissa

    // SEM ISTO, todo teste abaixo passaria com um filtro que simplesmente recusa tudo -- e o sistema
    // inteiro ficaria sem login, com a suite verde.
    @Test
    void premissa_oTOKENdeAGORAautentica() throws Exception {
        Usuario u = usuarioNaVersao(1);
        when(usuarios.findById(USER)).thenReturn(Optional.of(u));

        BoraPrincipal p = autenticarCom(jwt.gerar(u, LOJA));

        assertNotNull(p, "o token recem-emitido tem de autenticar");
        assertEquals(USER, p.userId());
        assertEquals("ADMINISTRADOR_LOJA", p.papel());
    }

    // =================================================================== o conserto

    @Test
    void depoisDeTROCARaSENHAoTOKENantigoNAOautentica() throws Exception {
        Usuario antes = usuarioNaVersao(1);
        String tokenAntigo = jwt.gerar(antes, LOJA);

        // a senha foi trocada: o banco subiu para 2
        when(usuarios.findById(USER)).thenReturn(Optional.of(usuarioNaVersao(2)));

        assertNull(autenticarCom(tokenAntigo),
                "o token emitido antes da troca de senha continuou valendo -- era o defeito");
    }

    @Test
    void tokenSEMaMARCAnaoAUTENTICA() throws Exception {
        // Este e o token de ANTES do deploy: assinado com a chave certa, com todas as claims
        // antigas, e sem `vs`. Ele tem de cair.
        String tokenDeAntes = Jwts.builder()
                .subject(String.valueOf(USER))
                .claim("email", "maria@loja.com")
                .claim("papel", "ADMINISTRADOR_LOJA")
                .claim("lojaId", LOJA)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 86_400_000L))
                .signWith(Keys.hmacShaKeyFor(SEGREDO.getBytes(StandardCharsets.UTF_8)))
                .compact();

        when(usuarios.findById(USER)).thenReturn(Optional.of(usuarioNaVersao(1)));

        assertNull(autenticarCom(tokenDeAntes),
            "token sem a marca da senha autenticou: o defeito ficaria aberto por 24h justamente "
            + "para os tokens que ja estavam por aí");
    }

    @Test
    void aSENHAtrocadaNAOderrubaQUEMnaoTROCOU() throws Exception {
        // A versao e por usuario. Se fosse global, a troca de senha de um funcionario deslogaria a
        // loja inteira -- um conserto que viraria outro defeito.
        Usuario u = usuarioNaVersao(5);
        when(usuarios.findById(USER)).thenReturn(Optional.of(u));

        assertNotNull(autenticarCom(jwt.gerar(u, LOJA)),
                "usuario na versao 5 com token da versao 5 tem de continuar logado");
    }

    // =================================================================== o setter que soma sozinho

    @Test
    void trocarAsenhaSOMAumaVERSAO() {
        Usuario u = new Usuario();
        u.setSenhaHash("$2a$10$hashANTIGO");
        assertEquals(1, u.getVersaoSenha(), "criar usuario nao deve somar: ele nasce na versao 1");

        u.setSenhaHash("$2a$10$hashNOVO");
        assertEquals(2, u.getVersaoSenha(), "trocar a senha tem de somar 1");

        u.setSenhaHash("$2a$10$hashMAISnovo");
        assertEquals(3, u.getVersaoSenha());
    }

    @Test
    void regravarOmesmoHASHnaoSOMA() {
        Usuario u = new Usuario();
        u.setSenhaHash("$2a$10$igual");
        u.setSenhaHash("$2a$10$igual");

        assertEquals(1, u.getVersaoSenha(),
            "reatribuir o hash que ja estava la nao e troca de senha e nao deve derrubar sessao");
    }

    // =================================================================== nao tem como esquecer

    /**
     * ESTE TESTE GUARDA O DESENHO, nao um comportamento.
     *
     * <p>Sao nove lugares no codigo que escrevem senha. Somar a versao "em cada lugar que troca
     * senha" seria uma lista para alguem esquecer -- e esquecer um deixa o buraco aberto por aquele
     * caminho, calado. O que garante os nove e: o campo e <b>privado</b> e o setter e escrito a
     * mao. Se alguem tornar o campo acessivel, ou apagar o setter deixando o Lombok gerar o dele,
     * este teste cai.</p>
     */
    @Test
    void oCAMPOehPRIVADOeOsetterEHaMAO() throws Exception {
        String fonte = Files.readString(Path.of("src/main/java/br/com/bora/entity/Usuario.java"));

        assertTrue(fonte.contains("private String senhaHash;"),
            "o campo senhaHash deixou de ser privado: daria para escrever senha sem passar pelo "
            + "setter, e a versao nao somaria");
        assertTrue(fonte.contains("public void setSenhaHash(String senhaHash)"),
            "o setter escrito a mao desapareceu -- o Lombok volta a gerar um que NAO soma a versao");

        int i = fonte.indexOf("public void setSenhaHash(String senhaHash)");
        String corpo = fonte.substring(i, fonte.indexOf("\n    }", i));
        assertTrue(corpo.contains("versaoSenha"),
                "o setter existe mas nao mexe mais na versao da senha");

        // E o filtro tem de continuar comparando com o BANCO, nao com o token.
        String filtroFonte = Files.readString(
                Path.of("src/main/java/br/com/bora/security/JwtAuthFilter.java"));
        assertTrue(filtroFonte.contains("usuario.getVersaoSenha()"),
            "o filtro parou de ler a versao do banco: comparar o token com ele mesmo nao prova nada");
    }
}
