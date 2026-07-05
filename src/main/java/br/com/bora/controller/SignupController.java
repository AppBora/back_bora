package br.com.bora.controller;

import br.com.bora.dto.SignupRequest;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Papel;
import br.com.bora.entity.Plano;
import br.com.bora.entity.Usuario;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Cadastro self-service de nova loja (público, sem autenticação).
 * Cria a loja no plano ÚNICO + o usuário administrador dela.
 * URL: POST /public/signup
 */
@RestController
@RequestMapping("/public/signup")
public class SignupController {

    private final LojaRepository lojas;
    private final UsuarioRepository usuarios;
    private final PasswordEncoder encoder;

    public SignupController(LojaRepository lojas, UsuarioRepository usuarios, PasswordEncoder encoder) {
        this.lojas = lojas;
        this.usuarios = usuarios;
        this.encoder = encoder;
    }

    @PostMapping
    @Transactional
    public Map<String, Object> cadastrar(@RequestBody SignupRequest req) {
        if (req.nomeLoja() == null || req.nomeLoja().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome da loja é obrigatório");
        }
        if (req.adminEmail() == null || !req.adminEmail().contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "E-mail inválido");
        }
        if (req.adminSenha() == null || req.adminSenha().length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A senha deve ter ao menos 6 caracteres");
        }
        String email = req.adminEmail().trim().toLowerCase();
        usuarios.findByEmail(email).ifPresent(u -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "E-mail já cadastrado");
        });

        Loja loja = new Loja();
        loja.setNome(req.nomeLoja().trim());
        loja.setDocumento(req.documento());
        loja.setPlano(Plano.UNICO); // plano único: R$ 299/mês por loja
        loja = lojas.save(loja);

        Usuario admin = new Usuario();
        admin.setLojaId(loja.getId());
        admin.setNome(req.adminNome() == null || req.adminNome().isBlank() ? "Administrador" : req.adminNome().trim());
        admin.setEmail(email);
        admin.setSenhaHash(encoder.encode(req.adminSenha()));
        admin.setPapel(Papel.ADMINISTRADOR_LOJA);
        usuarios.save(admin);

        return Map.of(
                "lojaId", loja.getId(),
                "plano", loja.getPlano().name(),
                "adminEmail", email,
                "mensagem", "Loja criada com sucesso! Faça login para começar.");
    }
}
