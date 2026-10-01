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
    private final br.com.bora.repository.LojaRepository lojas;

    private final br.com.bora.security.FreioDeTentativas freio;
    private final br.com.bora.security.RegraDeAcesso regra;

    public AuthController(UsuarioRepository repo, PasswordEncoder encoder, JwtService jwt, AuthContext ctx,
                          br.com.bora.service.RedeService rede, br.com.bora.repository.LojaRepository lojas,
                          br.com.bora.security.FreioDeTentativas freio,
                          br.com.bora.security.RegraDeAcesso regra) {
        this.regra = regra;
        this.freio = freio;
        this.lojas = lojas;
        this.repo = repo;
        this.encoder = encoder;
        this.jwt = jwt;
        this.ctx = ctx;
        this.rede = rede;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req, jakarta.servlet.http.HttpServletRequest http) {
        String chave = br.com.bora.security.FreioDeTentativas.chave(req.email(), br.com.bora.security.FreioDeTentativas.origem(http));
        freio.conferir(chave);
        Usuario u = repo.findByEmail(req.email() == null ? "" : req.email().trim().toLowerCase())
                .filter(Usuario::getAtivo)
                .orElse(null);
        // Senha nula estourava IllegalArgumentException no BCrypt e virava 500 em vez de 401.
        if (u == null || req.senha() == null || !encoder.matches(req.senha(), u.getSenhaHash())) {
            freio.errou(chave);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciais inválidas");
        }
        freio.acertou(chave);
        // Cliente suspenso ou arquivado pela plataforma não entra no painel. Mensagem separada da
        // de credencial: a senha está certa, quem está bloqueado é a loja — o suporte precisa
        // conseguir distinguir os dois casos. O ADMINISTRADOR_BORA não tem loja e passa direto.
        if (u.getLojaId() != null) {
            br.com.bora.entity.Loja l = lojas.findById(u.getLojaId()).orElse(null);
            if (l != null && l.bloqueadaPelaPlataforma()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Loja desativada pela plataforma. Fale com o suporte do BoraHapp.");
            }
            // Prazo de acesso vencido (sem assinatura paga) é outro caso, com outra saída: aqui o
            // próprio lojista resolve assinando. Mensagem separada para o suporte não confundir.
            if (l != null && !regra.podeUsarOPainel(l)) {
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                        br.com.bora.security.RegraDeAcesso.RECADO_PRAZO);
            }
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
