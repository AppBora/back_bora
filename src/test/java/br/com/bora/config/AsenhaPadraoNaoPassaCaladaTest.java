package br.com.bora.config;

import br.com.bora.entity.Papel;
import br.com.bora.entity.Usuario;
import br.com.bora.entity.UsuarioLoja;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A conta de bootstrap com a senha publicada no código não passa calada.
 *
 * <h2>O que a produção mostrou (30/10/2026)</h2>
 * <p>A conta {@code admin@bora.app} existe, está ATIVA, é ADMINISTRADOR_LOJA da loja 1 e foi criada
 * em <b>01/07/2026</b>. O arquivo de variáveis do servidor foi mexido pela última vez em
 * <b>04/10/2026</b> — três meses depois.</p>
 *
 * <p>O bootstrap <b>só cria</b>: ele nunca atualiza a senha de uma conta que já existe. Então, se a
 * variável foi definida depois, o hash gravado continua sendo o da senha padrão
 * ({@code bora123}), que está publicada no repositório. De fora não dá para saber qual dos dois
 * casos é — a única forma seria tentar o login.</p>
 *
 * <p><b>Aqui dentro a pergunta é barata.</b> O sistema compara o hash que já está no banco com a
 * senha que já está no código, e grita no log de auditoria a cada start. Isso transforma um "não dá
 * para saber" num alarme que aparece sozinho no próximo deploy.</p>
 *
 * <h2>Por que grita em vez de derrubar</h2>
 * <p>Uma API que se recusa a subir por causa disto tiraria as lojas do ar para resolver um problema
 * que depende de uma pessoa entrar no painel e trocar a senha.</p>
 */
class AsenhaPadraoNaoPassaCaladaTest {

    private UsuarioRepository usuarios;
    private LojaRepository lojas;
    private UsuarioLojaRepository vinculos;
    private PasswordEncoder encoder;

    @BeforeEach
    void montar() {
        usuarios = mock(UsuarioRepository.class);
        lojas = mock(LojaRepository.class);
        vinculos = mock(UsuarioLojaRepository.class);
        encoder = new BCryptPasswordEncoder();   // o de verdade: o que se mede aqui e a comparacao
        when(usuarios.findByEmail(anyString())).thenReturn(Optional.empty());
        when(usuarios.count()).thenReturn(1L);
        when(usuarios.findAll()).thenReturn(List.of(comPapel(Papel.ADMINISTRADOR_LOJA, "ja@existe.com")));
    }

    private Usuario comPapel(Papel papel, String email) {
        Usuario u = new Usuario();
        u.setId(1L);
        u.setEmail(email);
        u.setPapel(papel);
        u.setAtivo(true);
        u.setSenhaHash("hash-qualquer");
        return u;
    }

    /** A conta que já está no banco, com a senha informada. */
    private Usuario contaGravadaCom(String email, String senha, boolean ativo) {
        Usuario u = comPapel(Papel.ADMINISTRADOR_LOJA, email);
        u.setSenhaHash(encoder.encode(senha));
        u.setAtivo(ativo);
        when(usuarios.findByEmail(email)).thenReturn(Optional.of(u));
        return u;
    }

    private BootstrapAdmin bootstrap(String senhaConfigurada, String superEmail, String superSenha) {
        return new BootstrapAdmin(usuarios, lojas, encoder, vinculos,
                "admin@bora.app", senhaConfigurada, superEmail, superSenha);
    }

    /** Captura o que foi para o logger AUDITORIA durante o start. */
    private String auditoriaDurante(Runnable acao) {
        ch.qos.logback.classic.Logger log =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("AUDITORIA");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captura =
                new ch.qos.logback.core.read.ListAppender<>();
        captura.start();
        log.addAppender(captura);
        try {
            acao.run();
        } finally {
            log.detachAppender(captura);
        }
        StringBuilder sb = new StringBuilder();
        for (var e : captura.list) sb.append(e.getLevel()).append(' ').append(e.getFormattedMessage()).append('\n');
        return sb.toString();
    }

    // =============================================== o alarme

    @Test
    void aCONTAqueAINDAusaASENHApadraoAPARECEnoLOGdeAUDITORIA() {
        contaGravadaCom("admin@bora.app", BootstrapAdmin.SENHA_PADRAO, true);

        String auditoria = auditoriaDurante(() -> bootstrap("outra-senha-boa", "", "").run());

        assertTrue(auditoria.contains("SENHA PADRAO DO CODIGO"),
                "o start passou calado com a conta usando a senha publicada: " + auditoria);
        assertTrue(auditoria.contains("ERROR"), "o alarme tem de ser ERROR, nao um warn no meio do log");
        assertTrue(auditoria.contains("admin@bora.app"), "o alarme nao diz QUAL conta");
    }

    @Test
    void aCONTAcomSENHAtrocadaNAOgeraALARME() {
        // O outro lado: alarme que toca sempre ensina a ignorar alarme.
        contaGravadaCom("admin@bora.app", "a-senha-que-o-dono-trocou", true);

        String auditoria = auditoriaDurante(() -> bootstrap("qualquer", "", "").run());

        assertFalse(auditoria.contains("SENHA PADRAO DO CODIGO"),
                "alarme falso numa conta que ja foi trocada: " + auditoria);
    }

    @Test
    void aCONTAdesativadaNAOgeraALARME() {
        // Desativada nao entra em lugar nenhum: desativar ja e uma resposta valida ao alarme.
        contaGravadaCom("admin@bora.app", BootstrapAdmin.SENHA_PADRAO, false);

        String auditoria = auditoriaDurante(() -> bootstrap("qualquer", "", "").run());

        assertFalse(auditoria.contains("SENHA PADRAO DO CODIGO"), auditoria);
    }

    @Test
    void oSUPERadminTAMBEMeCONFERIDO() {
        // E a conta que entra em loja suspensa e atravessa a checagem de assinatura.
        contaGravadaCom("super@bora.app", BootstrapAdmin.SENHA_PADRAO, true);

        String auditoria = auditoriaDurante(() -> bootstrap("qualquer", "super@bora.app", "ok").run());

        assertTrue(auditoria.contains("super@bora.app"), auditoria);
    }

    // =============================================== e nada nasce com ela

    @Test
    void oSUPERadminNAOeCRIADOcomASENHApadrao() {
        when(usuarios.existsByPapel(Papel.ADMINISTRADOR_BORA)).thenReturn(false);
        when(usuarios.findByEmail("super@bora.app")).thenReturn(Optional.empty());

        String auditoria = auditoriaDurante(
                () -> bootstrap("qualquer", "super@bora.app", BootstrapAdmin.SENHA_PADRAO).run());

        verify(usuarios, never()).save(any(Usuario.class));
        assertTrue(auditoria.contains("SUPER-ADMIN DA PLATAFORMA NAO FOI CRIADO"),
                "nasceu um super-admin com a senha publicada no codigo: " + auditoria);
    }

    @Test
    void oSUPERadminSEMsenhaCONFIGURADAtambemNAOnasce() {
        when(usuarios.existsByPapel(Papel.ADMINISTRADOR_BORA)).thenReturn(false);

        auditoriaDurante(() -> bootstrap("qualquer", "super@bora.app", "").run());

        verify(usuarios, never()).save(any(Usuario.class));
    }

    @Test
    void oSUPERadminCOMsenhaPROPRIAnasceNORMALMENTE() {
        when(usuarios.existsByPapel(Papel.ADMINISTRADOR_BORA)).thenReturn(false);
        when(usuarios.save(any(Usuario.class))).thenAnswer(i -> i.getArgument(0));

        bootstrap("qualquer", "super@bora.app", "uma-senha-de-verdade").run();

        var c = org.mockito.ArgumentCaptor.forClass(Usuario.class);
        verify(usuarios).save(c.capture());
        assertEquals(Papel.ADMINISTRADOR_BORA, c.getValue().getPapel());
        assertTrue(encoder.matches("uma-senha-de-verdade", c.getValue().getSenhaHash()));
    }

    @Test
    void oADMINinicialCRIADOcomASENHApadraoAVISAnaHORA() {
        // Primeiro start de uma instalacao sem variavel nenhuma: a conta nasce (senao nao da para
        // entrar), mas o log diz, no momento em que ela nasce, que a senha esta publicada.
        when(usuarios.count()).thenReturn(0L);
        when(usuarios.findAll()).thenReturn(List.of());
        when(lojas.findAll()).thenReturn(List.of());
        br.com.bora.entity.Loja nova = new br.com.bora.entity.Loja();
        nova.id = 1L;
        when(lojas.save(any())).thenReturn(nova);
        when(usuarios.save(any(Usuario.class))).thenAnswer(i -> {
            Usuario u = i.getArgument(0);
            u.setId(9L);
            return u;
        });
        when(vinculos.save(any(UsuarioLoja.class))).thenAnswer(i -> i.getArgument(0));

        String auditoria = auditoriaDurante(
                () -> bootstrap(BootstrapAdmin.SENHA_PADRAO, "", "").run());

        assertTrue(auditoria.contains("SENHA PADRAO DO CODIGO"), auditoria);
    }
}
