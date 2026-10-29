package br.com.bora.service;

import br.com.bora.dto.NovoUsuario;
import br.com.bora.dto.UsuarioView;
import br.com.bora.entity.Papel;
import br.com.bora.entity.Usuario;
import br.com.bora.entity.UsuarioLoja;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Criar usuario, trocar papel, redefinir senha e desativar: as guardas de acesso da loja.
 *
 * <h2>Por que este arquivo existe (pente fino de 29/10/2026)</h2>
 * <p>O caminho inteiro de administracao de acesso da loja <b>nao tinha um unico teste de
 * comportamento</b>, em nenhuma das duas camadas. Medido assim:
 * {@code grep -rl "\bUsuarioService\b" src/test/java} devolvia 0 arquivos, e no frontend nenhum
 * teste carregava {@code usuarios.js}. O que ficava sem rede:</p>
 * <ul>
 *   <li>a trava de <b>escalada de privilegio</b> ({@code parsePapel}): a loja nao pode se promover a
 *       ADMINISTRADOR_BORA -- o papel que, como o {@code RebaixarTiraOPoderNaHoraTest} prova, entra
 *       em loja suspensa e atravessa a checagem de suspensao. Seria acesso de plataforma concedido
 *       pela propria loja;</li>
 *   <li>os {@code requirePapel("ADMINISTRADOR_LOJA")} dos cinco metodos;</li>
 *   <li>o minimo de 8 caracteres da senha, na criacao e na redefinicao;</li>
 *   <li>o {@code impedirAlvoProprio}, que evita o admin tirar o proprio poder e trancar a loja;</li>
 *   <li>o isolamento entre lojas no {@code buscar}.</li>
 * </ul>
 *
 * <h2>AuthContext de verdade, nao mock</h2>
 * <p>{@code requirePapel} e varargs, e mock com {@code thenCallRealMethod} nao repassa a chamada: o
 * mock simplesmente nao estoura. Isso ja fez <b>cinco testes deste projeto passarem verde medindo
 * nada</b> (ver {@code NumeroDeDonoNaoSaiParaObalcaoTest}). Aqui a classe e a real, com o
 * {@code SecurityContextHolder} montado -- o que esta sob teste e o que roda em producao.</p>
 */
class OacessoDaLojaTemGuardaTest {

    private static final Long LOJA = 18L;
    private static final Long EU = 7L;

    private UsuarioRepository repo;
    private PlanoService planos;
    private PasswordEncoder encoder;
    private UsuarioLojaRepository vinculos;
    private PedidoDeSenhaService pedidosDeSenha;

    @BeforeEach
    void montar() {
        repo = mock(UsuarioRepository.class);
        planos = mock(PlanoService.class);
        encoder = mock(PasswordEncoder.class);
        vinculos = mock(UsuarioLojaRepository.class);
        pedidosDeSenha = mock(PedidoDeSenhaService.class);
        when(encoder.encode(anyString())).thenAnswer(i -> "hash:" + i.getArgument(0));
        when(repo.save(any(Usuario.class))).thenAnswer(i -> {
            Usuario u = i.getArgument(0);
            if (u.getId() == null) u.setId(99L);
            return u;
        });
    }

    @AfterEach
    void limparSessao() {
        SecurityContextHolder.clearContext();
    }

    /** O servico com a sessao de quem esta clicando. */
    private UsuarioService comoPapel(String papel) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new BoraPrincipal(EU, LOJA, papel, "eu@loja.com"), null, List.of()));
        return new UsuarioService(repo, planos, encoder, new AuthContext(), vinculos, pedidosDeSenha);
    }

    private Usuario daLoja(Long id, Papel papel) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setLojaId(LOJA);
        u.setNome("Balconista");
        u.setEmail("balcao@loja.com");
        u.setSenhaHash("hash:antiga");
        u.setPapel(papel);
        u.setAtivo(true);
        when(repo.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    // ================================================ a escalada de privilegio

    @Test
    void aLOJAnaoPODEcriarUMadminDAplataforma() {
        UsuarioService s = comoPapel("ADMINISTRADOR_LOJA");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> s.criar(new NovoUsuario("Eu mesmo", "novo@loja.com", "senhaforte1", "ADMINISTRADOR_BORA")));

        assertEquals(HttpStatus.FORBIDDEN, HttpStatus.valueOf(e.getStatusCode().value()),
                "sem esta trava, a loja se promove a admin da PLATAFORMA -- o papel que entra em loja "
                        + "suspensa e atravessa a checagem de assinatura");
        verify(repo, never()).save(any(Usuario.class));
    }

    @Test
    void aLOJAnaoPODEpromoverALGUEMaADMINdaPLATAFORMA() {
        UsuarioService s = comoPapel("ADMINISTRADOR_LOJA");
        daLoja(42L, Papel.OPERADOR);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> s.atualizarPapel(42L, "ADMINISTRADOR_BORA"));

        assertEquals(HttpStatus.FORBIDDEN, HttpStatus.valueOf(e.getStatusCode().value()));
        verify(repo, never()).save(any(Usuario.class));
    }

    @Test
    void papelQueNAOexisteEh400_naoUM500() {
        UsuarioService s = comoPapel("ADMINISTRADOR_LOJA");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> s.criar(new NovoUsuario("X", "x@loja.com", "senhaforte1", "DONO_DE_TUDO")));

        assertEquals(HttpStatus.BAD_REQUEST, HttpStatus.valueOf(e.getStatusCode().value()));
    }

    @Test
    void osPAPEISdaLOJAsaoACEITOS() {
        for (Papel p : List.of(Papel.OPERADOR, Papel.GERENTE, Papel.ADMINISTRADOR_LOJA)) {
            UsuarioService s = comoPapel("ADMINISTRADOR_LOJA");
            when(repo.findByEmail(anyString())).thenReturn(Optional.empty());

            UsuarioView v = s.criar(new NovoUsuario("N", p.name().toLowerCase() + "@loja.com",
                    "senhaforte1", p.name()));

            assertEquals(p.name(), v.papel(), "o papel " + p + " deixou de poder ser criado pela loja");
        }
    }

    // ================================================ quem pode mexer em acesso

    @Test
    void soOADMINdaLOJAmexeEMacesso() {
        daLoja(42L, Papel.OPERADOR);
        for (String papel : List.of("OPERADOR", "GERENTE")) {
            UsuarioService s = comoPapel(papel);
            when(repo.findByEmail(anyString())).thenReturn(Optional.empty());

            assertThrows(ResponseStatusException.class,
                    () -> s.criar(new NovoUsuario("N", "n@loja.com", "senhaforte1", "OPERADOR")),
                    papel + " conseguiu CRIAR usuario");
            assertThrows(ResponseStatusException.class, () -> s.atualizarPapel(42L, "GERENTE"),
                    papel + " conseguiu trocar PAPEL");
            assertThrows(ResponseStatusException.class, () -> s.definirSenha(42L, "senhaforte1"),
                    papel + " conseguiu redefinir SENHA de outro");
            assertThrows(ResponseStatusException.class, () -> s.definirAtivo(42L, false),
                    papel + " conseguiu DESATIVAR usuario");
            assertThrows(ResponseStatusException.class, s::listar, papel + " conseguiu LISTAR usuarios");
        }
        verify(repo, never()).save(any(Usuario.class));
    }

    @Test
    void oADMINdaPLATAFORMApassa() {
        // `requirePapel` libera o ADMINISTRADOR_BORA em qualquer lista: e ele quem entra na loja do
        // cliente para resolver. Tirar isso tiraria o suporte.
        UsuarioService s = comoPapel("ADMINISTRADOR_BORA");
        Usuario u = daLoja(42L, Papel.OPERADOR);

        assertEquals("GERENTE", s.atualizarPapel(42L, "GERENTE").papel());
        assertEquals(Papel.GERENTE, u.getPapel());
    }

    // ================================================ a senha

    @Test
    void senhaCURTAnaoVIRAusuario() {
        UsuarioService s = comoPapel("ADMINISTRADOR_LOJA");
        when(repo.findByEmail(anyString())).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> s.criar(new NovoUsuario("N", "n@loja.com", "1234567", "OPERADOR")));

        assertEquals(HttpStatus.BAD_REQUEST, HttpStatus.valueOf(e.getStatusCode().value()));
        verify(repo, never()).save(any(Usuario.class));
        verify(planos, never()).checarLimiteUsuarios(anyLong());
    }

    @Test
    void redefinirPARAsenhaCURTAnaoMEXEnaSENHA() {
        UsuarioService s = comoPapel("ADMINISTRADOR_LOJA");
        Usuario u = daLoja(42L, Papel.OPERADOR);

        assertThrows(ResponseStatusException.class, () -> s.definirSenha(42L, "1234567"));

        assertEquals("hash:antiga", u.getSenhaHash(), "a senha foi trocada por uma curta");
        verify(pedidosDeSenha, never()).atendido(anyLong());
    }

    @Test
    void aSENHAehGRAVADAcifradaENUNCAemTEXTO() {
        UsuarioService s = comoPapel("ADMINISTRADOR_LOJA");
        when(repo.findByEmail(anyString())).thenReturn(Optional.empty());

        s.criar(new NovoUsuario("N", "n@loja.com", "senhaforte1", "OPERADOR"));

        ArgumentCaptor<Usuario> gravado = ArgumentCaptor.forClass(Usuario.class);
        verify(repo).save(gravado.capture());
        assertNotEquals("senhaforte1", gravado.getValue().getSenhaHash(),
                "a senha foi gravada em texto puro");
        assertEquals("hash:senhaforte1", gravado.getValue().getSenhaHash());
    }

    @Test
    void redefinirSENHAtambemFECHAoPEDIDOdeSENHA() {
        // O aviso de "esqueci minha senha" some pela ACAO que resolve. Se isto parar de acontecer, o
        // aviso velho acumula na tela ate ninguem mais olhar para ela.
        UsuarioService s = comoPapel("ADMINISTRADOR_LOJA");
        Usuario u = daLoja(42L, Papel.OPERADOR);

        s.definirSenha(42L, "senhaforte1");

        assertEquals("hash:senhaforte1", u.getSenhaHash());
        verify(pedidosDeSenha).atendido(42L);
    }

    // ================================================ o email

    @Test
    void oEMAILeGRAVADOminusculoESEMespaco() {
        // Entrar no sistema procura o email em minusculo. Gravar "  Ana@Loja.com " criava um usuario
        // que nao consegue entrar com o proprio email.
        UsuarioService s = comoPapel("ADMINISTRADOR_LOJA");
        when(repo.findByEmail(anyString())).thenReturn(Optional.empty());

        s.criar(new NovoUsuario("Ana", "  Ana@Loja.com ", "senhaforte1", "OPERADOR"));

        ArgumentCaptor<Usuario> gravado = ArgumentCaptor.forClass(Usuario.class);
        verify(repo).save(gravado.capture());
        assertEquals("ana@loja.com", gravado.getValue().getEmail());
    }

    @Test
    void emailJAcadastradoEh409_eNAOcriaNADA() {
        UsuarioService s = comoPapel("ADMINISTRADOR_LOJA");
        Usuario existente = new Usuario();
        existente.setId(1L);
        existente.setEmail("ana@loja.com");
        when(repo.findByEmail("ana@loja.com")).thenReturn(Optional.of(existente));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> s.criar(new NovoUsuario("Ana", "ana@loja.com", "senhaforte1", "OPERADOR")));

        assertEquals(HttpStatus.CONFLICT, HttpStatus.valueOf(e.getStatusCode().value()));
        verify(repo, never()).save(any(Usuario.class));
        verify(vinculos, never()).save(any(UsuarioLoja.class));
    }

    @Test
    void oUSUARIOnasceVINCULADOaLOJA() {
        // Sem o vinculo, o usuario nao aparece na tela da loja de rede: a listagem e por vinculo.
        UsuarioService s = comoPapel("ADMINISTRADOR_LOJA");
        when(repo.findByEmail(anyString())).thenReturn(Optional.empty());

        s.criar(new NovoUsuario("N", "n@loja.com", "senhaforte1", "OPERADOR"));

        ArgumentCaptor<UsuarioLoja> v = ArgumentCaptor.forClass(UsuarioLoja.class);
        verify(vinculos).save(v.capture());
        assertEquals(LOJA, v.getValue().getLojaId());
        assertEquals(99L, v.getValue().getUsuarioId());
    }

    @Test
    void oLIMITEdoPLANOeCHECADOantesDEgravar() {
        UsuarioService s = comoPapel("ADMINISTRADOR_LOJA");
        when(repo.findByEmail(anyString())).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "limite"))
                .when(planos).checarLimiteUsuarios(LOJA);

        assertThrows(ResponseStatusException.class,
                () -> s.criar(new NovoUsuario("N", "n@loja.com", "senhaforte1", "OPERADOR")));

        verify(repo, never()).save(any(Usuario.class));
    }

    // ================================================ nao se trancar fora

    @Test
    void oADMINnaoTIRAoPROPRIOpapelNEMseDESATIVA() {
        UsuarioService s = comoPapel("ADMINISTRADOR_LOJA");

        ResponseStatusException papel = assertThrows(ResponseStatusException.class,
                () -> s.atualizarPapel(EU, "OPERADOR"));
        ResponseStatusException ativo = assertThrows(ResponseStatusException.class,
                () -> s.definirAtivo(EU, false));

        assertEquals(HttpStatus.BAD_REQUEST, HttpStatus.valueOf(papel.getStatusCode().value()));
        assertEquals(HttpStatus.BAD_REQUEST, HttpStatus.valueOf(ativo.getStatusCode().value()));
        // Sem estas duas guardas a loja fica sem nenhum administrador, e so o suporte da plataforma
        // consegue destravar.
        verify(repo, never()).save(any(Usuario.class));
    }

    @Test
    void oADMINpodeREDEFINIRaPROPRIAsenha() {
        // Esta e permitida de proposito: nao tranca ninguem fora, e e o caminho de quem quer trocar a
        // senha propria sem depender de outro admin.
        UsuarioService s = comoPapel("ADMINISTRADOR_LOJA");
        Usuario eu = daLoja(EU, Papel.ADMINISTRADOR_LOJA);

        s.definirSenha(EU, "senhanova123");

        assertEquals("hash:senhanova123", eu.getSenhaHash());
    }

    // ================================================ uma loja nao mexe na outra

    @Test
    void usuarioDEOUTRAlojaEH404paraTODAacao() {
        UsuarioService s = comoPapel("ADMINISTRADOR_LOJA");
        Usuario deOutra = new Usuario();
        deOutra.setId(500L);
        deOutra.setLojaId(LOJA + 1);
        deOutra.setEmail("outro@outra.com");
        deOutra.setPapel(Papel.OPERADOR);
        deOutra.setSenhaHash("hash:dele");
        when(repo.findById(500L)).thenReturn(Optional.of(deOutra));

        for (var acao : List.<org.junit.jupiter.api.function.Executable>of(
                () -> s.atualizarPapel(500L, "GERENTE"),
                () -> s.definirSenha(500L, "senhaforte1"),
                () -> s.definirAtivo(500L, false))) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class, acao);
            assertEquals(HttpStatus.NOT_FOUND, HttpStatus.valueOf(e.getStatusCode().value()),
                    "uma loja mexeu no acesso de outra");
        }
        assertEquals("hash:dele", deOutra.getSenhaHash());
        assertEquals(Papel.OPERADOR, deOutra.getPapel());
    }

    @Test
    void usuarioQUEnaoEXISTEeh404() {
        UsuarioService s = comoPapel("ADMINISTRADOR_LOJA");
        when(repo.findById(anyLong())).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> s.definirSenha(12345L, "senhaforte1"));

        assertEquals(HttpStatus.NOT_FOUND, HttpStatus.valueOf(e.getStatusCode().value()));
    }

    // ================================================ a listagem

    @Test
    void aLISTAGEMeDAlojaDEquemESTAolhando() {
        UsuarioService s = comoPapel("ADMINISTRADOR_LOJA");
        Usuario a = new Usuario();
        a.setId(1L); a.setLojaId(LOJA); a.setNome("Ana"); a.setEmail("ana@loja.com");
        a.setPapel(Papel.GERENTE); a.setAtivo(true); a.setSenhaHash("x");
        when(repo.daLoja(LOJA)).thenReturn(List.of(a));

        List<UsuarioView> lista = s.listar();

        assertEquals(1, lista.size());
        assertEquals("ana@loja.com", lista.get(0).email());
        // A view nao leva o hash da senha para a tela.
        assertTrue(String.valueOf(lista.get(0)).contains("GERENTE"));
        assertTrue(!String.valueOf(lista.get(0)).contains("x"), "o hash da senha vazou na listagem");
    }
}
