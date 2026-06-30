package br.com.bora.controller;

import br.com.bora.entity.Entregador;
import br.com.bora.service.EntregadorService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/entregadores")
public class EntregadorController {

    private final EntregadorService service;

    public EntregadorController(EntregadorService service) {
        this.service = service;
    }

    @GetMapping
    public List<Entregador> listar() {
        return service.listar();
    }

    @PostMapping
    public Entregador criar(@RequestBody Entregador e) {
        return service.criar(e);
    }

    @PutMapping("/{id}")
    public Entregador atualizar(@PathVariable Long id, @RequestBody Entregador e) {
        return service.atualizar(id, e);
    }

    @DeleteMapping("/{id}")
    public void excluir(@PathVariable Long id) {
        service.excluir(id);
    }
}
