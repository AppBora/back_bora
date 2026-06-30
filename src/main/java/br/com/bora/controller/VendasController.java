package br.com.bora.controller;

import br.com.bora.security.AuthContext;
import br.com.bora.service.VendasSaudeService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/vendas")
public class VendasController {

    private final VendasSaudeService saude;
    private final AuthContext ctx;

    public VendasController(VendasSaudeService saude, AuthContext ctx) {
        this.saude = saude;
        this.ctx = ctx;
    }

    @GetMapping("/termometro")
    public Map<String, Object> termometro() {
        return saude.termometro(ctx.lojaId());
    }
}
