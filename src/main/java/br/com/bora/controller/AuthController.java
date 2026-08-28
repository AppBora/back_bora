package br.com.bora.controller;

import br.com.bora.dto.LoginRequest;
import br.com.bora.dto.LoginResponse;
import br.com.bora.entity.Usuario;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import br.com.bora.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UsuarioRepository repo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuthContext ctx;
    private final br.com.bora.service.RedeService rede;

    public AuthController(UsuarioRepository repo, PasswordEncoder encoder, JwtService jwt, AuthContext ctx,
                          br.com.bora.service.RedeService rede) {
        this.repo = repo;
        this.encoder = encoder;
        this.jwt = jwt;
        this.ctx = ctx;
        this.rede = rede;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        Usuario u = repo.findByEmail(req.email() == null ? "" : req.email().trim().toLowerCase())
                .filter(Usuario::getAtivo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciais inválidas"));
        if (!encoder.matches(req.senha(), u.getSenhaHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciais inválidas");
        }
        return new LoginResponse(jwt.gerar(u), u.getNome(), u.getPapel().name(), u.getLojaId());
    }

    /**
     * Troca a senha do usuário logado. Exige a senha atual — sem isso, um token vazado
     * viraria sequestro definitivo da conta.
     */
    @PostMapping("/trocar-senha")
    public java.util.Map<String, Object> trocarSenha(@RequestBody java.util.Map<String, String> body) {
        String atual = body == null ? null : body.get("senhaAtual");
        String nova = body == null ? null : body.get("novaSenha");
        if (nova == null || nova.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A nova senha precisa ter ao menos 8 caracteres");
        }
        Usuario u = repo.findById(ctx.atual().userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));
        if (atual == null || !encoder.matches(atual, u.getSenhaHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Senha atual incorreta");
        }
        if (encoder.matches(nova, u.getSenhaHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A nova senha precisa ser diferente da atual");
        }
        u.setSenhaHash(encoder.encode(nova));
        repo.save(u);
        return java.util.Map.of("trocada", true);
    }

    @GetMapping("/me")
    public BoraPrincipal me() {
        return ctx.atual();
    }

    /** Troca o contexto para outra loja vinculada (rede). Corpo: { "lojaId": 2 }. Devolve novo token. */
    @PostMapping("/trocar-loja")
    public java.util.Map<String, Object> trocarLoja(@RequestBody java.util.Map<String, Long> body) {
        return rede.trocarLoja(body == null ? null : body.get("lojaId"));
    }
}
