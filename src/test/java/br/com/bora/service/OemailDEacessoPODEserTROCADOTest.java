package br.com.bora.service;

import br.com.bora.entity.Papel;
import br.com.bora.entity.Usuario;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * O e-mail de acesso passa a poder ser trocado.
 *
 * <h2>Por que este teste existe (15/11/2026)</h2>
 * <p>Nao havia jeito nenhum de trocar: o {@code setEmail} do servico so rodava na criacao e a tela
 * de Usuarios fazia {@code $('email').disabled = true} ao editar. A loja <b>Doce Jardim</b> foi
 * aberta com um e-mail provisorio e ficaria presa a ele.</p>
 *
 * <h2>O defeito silencioso que este teste existe para trancar</h2>
 * <p>O login procura por {@code findByEmail(req.email().trim().toLowerCase())}
 * (AuthController). Se a troca gravar <b>com maiuscula ou com espaco sobrando</b>, nada falha: a
 * tela diz "salvo", a linha muda no banco, e no proximo login o e-mail <b>nao casa</b>. A pessoa
 * fica trancada para fora sem nenhuma mensagem de erro em lugar nenhum.</p>
 */
class OemailDEacessoPODEserTROCADOTest {

    private static final Long LOJA = 23L;
    private static final Long EU = 36L;

    private UsuarioRepository repo;
    private AuthContext ctx;
    private UsuarioService service;
    private Map<Long, Usuario> banco;

    private Usuario pessoa(Long id, Long lojaId, String email) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setLojaId(lojaId);
        u.setNome("Fulano " + id);
        u.setEmail(email);
        u.setSenhaHash("x");
        u.setPapel(Papel.ADMINISTRADOR_LOJA);
        u.setAtivo(true);
        return u;
    }

    @BeforeEach
    void montar() {
        repo = mock(UsuarioRepository.class);
        ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        when(ctx.atual()).thenReturn(new BoraPrincipal(EU, LOJA, "ADMINISTRADOR_LOJA", "provisorio@exemplo.com"));

        banco = new HashMap<>();
        banco.put(EU, pessoa(EU, LOJA, "provisorio@exemplo.com"));

        when(repo.findById(any())).thenAnswer(i -> Optional.ofNullable(banco.get(i.getArgument(0))));
        when(repo.findByEmail(anyString())).thenAnswer(i -> banco.values().stream()
                .filter(u -> i.getArgument(0).equals(u.getEmail())).findFirst());
        when(repo.save(any(Usuario.class))).thenAnswer(i -> i.getArgument(0));

        service = new UsuarioService(repo, mock(PlanoService.class), mock(PasswordEncoder.class), ctx,
                mock(br.com.bora.repository.UsuarioLojaRepository.class), mock(PedidoDeSenhaService.class));
    }

    @Test
    void oEMAILnovoEhGRAVADO() {
        assertEquals("ferreira_114@hotmail.com",
                service.definirEmail(EU, "ferreira_114@hotmail.com").email());
    }

    /**
     * O TESTE QUE JUSTIFICA O ARQUIVO. Sem o {@code trim().toLowerCase()} a gravacao "funciona"
     * e o login para de funcionar — calado.
     */
    @Test
    void MAIUSCULAeESPACOviramMINUSCULAsemESPACO() {
        assertEquals("ferreira_114@hotmail.com",
                service.definirEmail(EU, "  Ferreira_114@Hotmail.COM  ").email(),
                "o login busca o e-mail em minusculas: gravar assim tranca a pessoa para fora");
    }

    @Test
    void trocarOPROPRIOemailEhPERMITIDO() {
        // De proposito, ao contrario de papel/ativo: o caso real e o unico administrador da loja
        // com login provisorio. Barrar alvo proprio deixaria o recurso inutil para quem precisa.
        assertEquals("patricia@docejardim.com.br",
                service.definirEmail(EU, "patricia@docejardim.com.br").email());
    }

    @Test
    void emailSEMarrobaEhRECUSADO() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.definirEmail(EU, "ferreira_114.hotmail.com"));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void emailSEMpontoDEPOISdoARROBAehRECUSADO() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.definirEmail(EU, "patricia@hotmail"));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void emailEMbrancoEhRECUSADO() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.definirEmail(EU, "   "));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertEquals("provisorio@exemplo.com", banco.get(EU).getEmail(), "nao podia ter mexido");
    }

    @Test
    void emailJAusadoPOROUTRApessoaEhRECUSADO() {
        banco.put(40L, pessoa(40L, LOJA, "atendente@docejardim.com.br"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.definirEmail(EU, "atendente@docejardim.com.br"));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    /** Salvar o mesmo e-mail da propria pessoa nao pode virar erro. */
    @Test
    void oMESMOemailNAmesmaPESSOAnaoEhConflito() {
        assertEquals("provisorio@exemplo.com",
                service.definirEmail(EU, "PROVISORIO@exemplo.com").email());
    }

    /** Isolamento: ninguem troca o e-mail de usuario de outra loja. */
    @Test
    void usuarioDEoutraLOJAdaNAOencontrado() {
        banco.put(99L, pessoa(99L, 18L, "zira@exemplo.com"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.definirEmail(99L, "invasor@exemplo.com"));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        assertEquals("zira@exemplo.com", banco.get(99L).getEmail(), "nao podia ter mexido");
    }

    @Test
    void soOadministradorDAlojaTROCA() {
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "sem permissao"))
                .when(ctx).requirePapel("ADMINISTRADOR_LOJA");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.definirEmail(EU, "qualquer@exemplo.com"));
        assertTrue(e.getStatusCode().is4xxClientError());
    }
}
