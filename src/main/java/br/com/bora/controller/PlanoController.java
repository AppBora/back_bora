package br.com.bora.controller;

import br.com.bora.security.AuthContext;
import br.com.bora.service.PlanoService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/plano")
public class PlanoController {

    private final PlanoService planos;
    private final AuthContext ctx;

    public PlanoController(PlanoService planos, AuthContext ctx) {
        this.planos = planos;
        this.ctx = ctx;
    }

    @GetMapping
    public Map<String, Object> atual() {
        return planos.resumo(ctx.lojaId());
    }
}
