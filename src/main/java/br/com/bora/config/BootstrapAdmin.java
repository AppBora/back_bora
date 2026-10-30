package br.com.bora.config;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Papel;
import br.com.bora.entity.Usuario;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Cria um administrador de loja no primeiro start (se não houver nenhum usuário),
 * usando BCrypt — evita seed com hash fixo. Credenciais ajustáveis por env.
 */
@Slf4j
@Component
public class BootstrapAdmin implements CommandLineRunner {

    private static final org.slf4j.Logger AUDITORIA = org.slf4j.LoggerFactory.getLogger("AUDITORIA");

    /**
     * A senha que o codigo usa quando ninguem configura nada — e que esta PUBLICADA aqui dentro.
     *
     * <p>Ela existe para o primeiro start de uma maquina de desenvolvimento funcionar sem
     * configuracao. Em producao ela e um problema, por dois caminhos:</p>
     * <ul>
     *   <li>a variavel nunca foi definida, e a conta nasceu com esta senha;</li>
     *   <li>a variavel foi definida DEPOIS: o bootstrap so CRIA, nunca atualiza senha de conta que
     *       ja existe — entao o hash continua sendo o desta senha, e ninguem fica sabendo.</li>
     * </ul>
     *
     * <p>O segundo caso e real: na producao medida em 30/10/2026, a conta de bootstrap foi criada em
     * 01/07 e o arquivo de variaveis do servidor foi mexido pela ultima vez em 04/10 — tres meses
     * depois. Nao da para saber de fora qual dos dois casos e; <b>o proprio sistema sabe</b>, e por
     * isso ele passa a conferir e gritar no start. Ver {@link #avisarSeAindaUsaASenhaPadrao}.</p>
     */
    static final String SENHA_PADRAO = "bora123";

    private final UsuarioRepository usuarios;
    private final LojaRepository lojas;
    private final PasswordEncoder encoder;
    private final br.com.bora.repository.UsuarioLojaRepository vinculos;
    private final String email;
    private final String senha;
    private final String superEmail;
    private final String superSenha;

    public BootstrapAdmin(UsuarioRepository usuarios, LojaRepository lojas, PasswordEncoder encoder,
                          br.com.bora.repository.UsuarioLojaRepository vinculos,
                          @Value("${bora.bootstrap.admin-email:admin@bora.app}") String email,
                          @Value("${bora.bootstrap.admin-senha:bora123}") String senha,
                          @Value("${bora.bootstrap.superadmin-email:}") String superEmail,
                          @Value("${bora.bootstrap.superadmin-senha:}") String superSenha) {
        this.usuarios = usuarios;
        this.lojas = lojas;
        this.encoder = encoder;
        this.vinculos = vinculos;
        this.email = email.trim().toLowerCase();
        this.senha = senha;
        this.superEmail = superEmail == null ? "" : superEmail.trim().toLowerCase();
        this.superSenha = superSenha;
    }

    @Override
    public void run(String... args) {
        avisarSeAindaUsaASenhaPadrao();

        // Super-admin da plataforma (ADMINISTRADOR_BORA) — só se configurado e ainda não existir.
        if (!superEmail.isBlank() && !usuarios.existsByPapel(Papel.ADMINISTRADOR_BORA)
                && usuarios.findByEmail(superEmail).isEmpty()) {
            Usuario sa = new Usuario();
            sa.setNome("Administrador BoraHapp");
            sa.setEmail(superEmail);
            // Nascer com a senha publicada no codigo e pior que nao nascer: o super-admin da
            // PLATAFORMA entra em loja suspensa e atravessa a checagem de assinatura.
            if (superSenha == null || superSenha.isBlank() || SENHA_PADRAO.equals(superSenha)) {
                AUDITORIA.error("SUPER-ADMIN DA PLATAFORMA NAO FOI CRIADO: a variavel "
                        + "BORA_BOOTSTRAP_SUPERADMIN_SENHA esta vazia ou e a senha padrao do codigo. "
                        + "Defina uma senha propria e reinicie. Criar a conta com a senha publicada "
                        + "aqui dentro seria abrir a plataforma inteira.");
                return;
            }
            sa.setSenhaHash(encoder.encode(superSenha));
            sa.setPapel(Papel.ADMINISTRADOR_BORA); // global, sem loja
            usuarios.save(sa);
            log.warn("Super-admin da plataforma criado: {} (troque a senha padrão).", superEmail);
        }

        if (usuarios.count() > 0
                && usuarios.findAll().stream().anyMatch(u -> u.getPapel() != Papel.ADMINISTRADOR_BORA)) {
            return; // já há admin de loja
        }

        Loja loja = lojas.findAll().stream().findFirst().orElseGet(() -> {
            Loja l = new Loja();
            l.setNome("Loja Demo");
            return lojas.save(l);
        });

        if (SENHA_PADRAO.equals(senha)) {
            AUDITORIA.error("ADMIN INICIAL CRIADO COM A SENHA PADRAO DO CODIGO ({}). Ela esta "
                    + "publicada no repositorio. Defina BORA_BOOTSTRAP_ADMIN_SENHA e TROQUE a senha "
                    + "desta conta pelo painel — definir a variavel depois NAO troca o que ja esta "
                    + "gravado.", email);
        }
        Usuario admin = new Usuario();
        admin.setLojaId(loja.getId());
        admin.setNome("Administrador");
        admin.setEmail(email);
        admin.setSenhaHash(encoder.encode(senha));
        admin.setPapel(Papel.ADMINISTRADOR_LOJA);
        admin = usuarios.save(admin);

        br.com.bora.entity.UsuarioLoja v = new br.com.bora.entity.UsuarioLoja();
        v.setUsuarioId(admin.getId());
        v.setLojaId(loja.getId());
        vinculos.save(v);

        log.warn("Admin inicial criado: {} (troque a senha padrão). Loja: {}", email, loja.getId());
    }

    /**
     * As contas de bootstrap ainda estao com a senha publicada no codigo?
     *
     * <h2>Por que isto so o sistema consegue responder</h2>
     * <p>O bootstrap <b>so cria</b>: ele nunca atualiza a senha de uma conta que ja existe. Entao
     * definir a variavel depois de a conta ter nascido nao troca nada, e de fora nao da para saber
     * se o hash gravado e o da senha configurada ou o da senha padrao — a unica forma seria tentar
     * o login, que ninguem deve ficar fazendo.</p>
     *
     * <p>Aqui dentro a pergunta e barata e nao expoe nada: o {@code encoder.matches} compara o hash
     * que ja esta no banco com uma senha que ja esta no codigo. Se bater, grita no log de auditoria
     * a cada start, ate alguem trocar.</p>
     *
     * <p>Grita, e nao derruba: uma API que se recusa a subir por causa disto tiraria as lojas do ar
     * para resolver um problema que depende de uma pessoa entrar no painel e trocar a senha.</p>
     */
    private void avisarSeAindaUsaASenhaPadrao() {
        for (String conta : new String[] {email, superEmail}) {
            if (conta == null || conta.isBlank()) continue;
            usuarios.findByEmail(conta).ifPresent(u -> {
                if (!Boolean.TRUE.equals(u.getAtivo())) return;   // desativada nao entra em lugar nenhum
                if (encoder.matches(SENHA_PADRAO, u.getSenhaHash())) {
                    AUDITORIA.error("A CONTA {} ({}) AINDA ESTA COM A SENHA PADRAO DO CODIGO, que esta "
                            + "publicada no repositorio. Troque AGORA pelo painel (Usuarios > redefinir "
                            + "senha) ou desative a conta. Definir a variavel de ambiente nao resolve: o "
                            + "bootstrap so cria, nunca troca senha de conta que ja existe.",
                            conta, u.getPapel());
                }
            });
        }
    }
}
