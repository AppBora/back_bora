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

    /**
     * Passo 1 do vínculo oficial. No iFood devolve o código que o lojista digita no Portal do
     * Parceiro; na 99Food (Open Delivery) já valida a credencial e conecta de uma vez.
     */
    @PostMapping("/{canal}/vincular")
    public Map<String, Object> vincular(@PathVariable String canal) {
        return service.iniciarVinculo(canal);
    }

    /** Passo 2 do vínculo do iFood: troca o código autorizado pelo lojista por tokens de acesso. */
    @PostMapping("/{canal}/confirmar")
    public Map<String, Object> confirmar(@PathVariable String canal, @RequestBody(required = false) Map<String, Object> body) {
        Object codigo = body == null ? null : body.get("authorizationCode");
        return service.concluirVinculo(canal, codigo == null ? null : String.valueOf(codigo));
    }

    /** Diagnóstico da conexão: último polling, último erro e se os pedidos estão entrando. */
    @GetMapping("/{canal}/diagnostico")
    public Map<String, Object> diagnostico(@PathVariable String canal) {
        return service.diagnostico(canal);
    }
}
