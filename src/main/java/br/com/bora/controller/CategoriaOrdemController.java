package br.com.bora.controller;

import br.com.bora.service.CategoriaOrdemService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * A ordem das secoes do cardapio.
 *
 * <p><b>O GET nao exige papel de gerente de proposito.</b> Quem mais precisa dele e a Frente de
 * Caixa, que e aberta ao balconista: sem a ordem, os chips dela voltariam para o alfabeto e a tela
 * do balcao ficaria diferente do cardapio que o cliente ve. O GET nao devolve numero de dono --
 * so nome, quantos produtos e a posicao.</p>
 */
@RestController
@RequestMapping("/api/categorias")
public class CategoriaOrdemController {

    private final CategoriaOrdemService service;

    public CategoriaOrdemController(CategoriaOrdemService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> listar() {
        return service.listar();
    }

    /** Recebe a lista inteira, na ordem. Ver o porque em {@code CategoriaOrdemService.salvar}. */
    @PutMapping("/ordem")
    public List<Map<String, Object>> salvarOrdem(@RequestBody List<String> nomes) {
        return service.salvar(nomes);
    }
}
