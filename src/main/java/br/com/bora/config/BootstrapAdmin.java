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

    private final UsuarioRepository usuarios;
    private final LojaRepository lojas;
    private final PasswordEncoder encoder;
    private final String email;
    private final String senha;
    private final String superEmail;
    private final String superSenha;

    public BootstrapAdmin(UsuarioRepository usuarios, LojaRepository lojas, PasswordEncoder encoder,
                          @Value("${bora.bootstrap.admin-email:admin@bora.app}") String email,
                          @Value("${bora.bootstrap.admin-senha:bora123}") String senha,
                          @Value("${bora.bootstrap.superadmin-email:}") String superEmail,
                          @Value("${bora.bootstrap.superadmin-senha:}") String superSenha) {
        this.usuarios = usuarios;
        this.lojas = lojas;
        this.encoder = encoder;
        this.email = email.trim().toLowerCase();
        this.senha = senha;
        this.superEmail = superEmail == null ? "" : superEmail.trim().toLowerCase();
        this.superSenha = superSenha;
    }

    @Override
    public void run(String... args) {
        // Super-admin da plataforma (ADMINISTRADOR_BORA) — só se configurado e ainda não existir.
        if (!superEmail.isBlank() && !usuarios.existsByPapel(Papel.ADMINISTRADOR_BORA)
                && usuarios.findByEmail(superEmail).isEmpty()) {
            Usuario sa = new Usuario();
            sa.setNome("Administrador Bora");
            sa.setEmail(superEmail);
            sa.setSenhaHash(encoder.encode(superSenha == null || superSenha.isBlank() ? "bora123" : superSenha));
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

        Usuario admin = new Usuario();
        admin.setLojaId(loja.getId());
        admin.setNome("Administrador");
        admin.setEmail(email);
        admin.setSenhaHash(encoder.encode(senha));
        admin.setPapel(Papel.ADMINISTRADOR_LOJA);
        usuarios.save(admin);

        log.warn("Admin inicial criado: {} (troque a senha padrão). Loja: {}", email, loja.getId());
    }
}
