package br.com.bora.controller;

import br.com.bora.service.IntegracaoService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Gestão das conexões com marketplaces (iFood, 99Food, Rappi, Uber Eats…). */
@RestController
@RequestMapping("/api/integracoes")
public class IntegracaoController {

    private final IntegracaoService service;

    public IntegracaoController(IntegracaoService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> listar() {
        return service.listar();
    }

    @PutMapping("/{canal}")
    public Map<String, Object> salvar(@PathVariable String canal, @RequestBody Map<String, Object> body) {
        return service.salvar(canal, body);
    }
}
