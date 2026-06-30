package br.com.bora.controller;

import br.com.bora.entity.ConfiguracaoLoja;
import br.com.bora.service.ConfiguracaoLojaService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/configuracao")
public class ConfiguracaoLojaController {

    private final ConfiguracaoLojaService service;

    public ConfiguracaoLojaController(ConfiguracaoLojaService service) {
        this.service = service;
    }

    @GetMapping
    public ConfiguracaoLoja atual() {
        return service.atual();
    }

    @PutMapping
    public ConfiguracaoLoja atualizar(@RequestBody ConfiguracaoLoja dados) {
        return service.atualizar(dados);
    }
}
