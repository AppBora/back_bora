package br.com.bora.controller;

import br.com.bora.entity.Promocao;
import br.com.bora.security.AuthContext;
import br.com.bora.service.PromocaoService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/promocoes")
public class PromocaoController {

    private final PromocaoService service;
    private final AuthContext ctx;

    public PromocaoController(PromocaoService service, AuthContext ctx) {
        this.service = service;
        this.ctx = ctx;
    }

    @GetMapping
    public List<Promocao> listar() {
        return service.listar(ctx.lojaId());
    }

    @PostMapping
    public Promocao criar(@RequestBody Promocao dados) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        return service.criarManual(ctx.lojaId(), dados);
    }
}
