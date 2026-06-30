package br.com.bora.controller;

import br.com.bora.service.RelatorioService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/relatorios")
public class RelatorioController {

    private final RelatorioService service;

    public RelatorioController(RelatorioService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> gerar(@RequestParam(defaultValue = "30") int dias) {
        return service.gerar(dias);
    }
}
