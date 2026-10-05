package br.com.bora.controller;

import br.com.bora.security.AuthContext;
import br.com.bora.security.FreioDeTentativas;
import br.com.bora.service.TermosService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Aceite dos Termos pelo lojista que ja usa o sistema. Ver {@link TermosService}. */
@RestController
@RequestMapping("/api/termos")
public class TermosController {

    private final TermosService termos;
    private final AuthContext ctx;

    public TermosController(TermosService termos, AuthContext ctx) {
        this.termos = termos;
        this.ctx = ctx;
    }

    @GetMapping
    public Map<String, Object> situacao() {
        return termos.situacao(ctx.lojaId(), ctx.papel());
    }

    @PostMapping("/aceitar")
    public Map<String, Object> aceitar(jakarta.servlet.http.HttpServletRequest http) {
        return termos.aceitar(ctx.lojaId(), ctx.papel(), FreioDeTentativas.origem(http));
    }
}
