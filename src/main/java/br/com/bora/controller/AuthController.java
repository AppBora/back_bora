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

    public AuthController(UsuarioRepository repo, PasswordEncoder encoder, JwtService jwt, AuthContext ctx) {
        this.repo = repo;
        this.encoder = encoder;
        this.jwt = jwt;
        this.ctx = ctx;
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

    @GetMapping("/me")
    public BoraPrincipal me() {
        return ctx.atual();
    }
}
