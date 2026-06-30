package br.com.bora.controller;

import br.com.bora.entity.Insumo;
import br.com.bora.service.InsumoService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class InsumoController {

    private final InsumoService service;

    public InsumoController(InsumoService service) {
        this.service = service;
    }

    @GetMapping("/insumos") public List<Insumo> listar() { return service.listar(); }
    @PostMapping("/insumos") public Insumo criar(@RequestBody Insumo i) { i.id = null; return service.salvar(i); }
    @PutMapping("/insumos/{id}") public Insumo editar(@PathVariable Long id, @RequestBody Insumo i) { i.id = id; return service.salvar(i); }
    @DeleteMapping("/insumos/{id}") public void excluir(@PathVariable Long id) { service.excluir(id); }

    @GetMapping("/produtos/{id}/ficha") public List<Map<String, Object>> ficha(@PathVariable Long id) { return service.ficha(id); }
    @PutMapping("/produtos/{id}/ficha") public List<Map<String, Object>> salvarFicha(@PathVariable Long id, @RequestBody List<Map<String, Object>> itens) { return service.salvarFicha(id, itens); }
}
