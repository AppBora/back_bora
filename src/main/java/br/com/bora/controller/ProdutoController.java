package br.com.bora.controller;

import br.com.bora.entity.Produto;
import br.com.bora.service.ProdutoService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/produtos")
public class ProdutoController {

    private final ProdutoService service;

    public ProdutoController(ProdutoService service) {
        this.service = service;
    }

    @GetMapping
    public List<br.com.bora.dto.ProdutoResumo> listar() {
        return service.listar();
    }

    @PostMapping
    public Produto criar(@RequestBody Produto p) {
        return service.criar(p);
    }

    @PutMapping("/{id}")
    public Produto atualizar(@PathVariable Long id, @RequestBody Produto p) {
        return service.atualizar(id, p);
    }

    /**
     * Mexer no estoque, separado do cadastro.
     *
     * <p>Antes, as telas de Estoque e de Contagem ajustavam estoque mandando o CADASTRO INTEIRO de
     * volta pelo {@code PUT /api/produtos/{id}} -- e o cadastro que elas conhecem nao tem todos os
     * campos, entao cada ajuste apagava o que elas nao sabem que existe (o
     * {@code codigo_externo} do marketplace, por exemplo). E o formulario de Produtos, no caminho
     * contrario, apagava o estoque com o numero velho da tela.</p>
     *
     * <p>Ver {@link br.com.bora.dto.MexerNoEstoqueRequest}.</p>
     */
    @PutMapping("/{id}/estoque")
    public Produto mexerNoEstoque(@PathVariable Long id,
                                  @RequestBody br.com.bora.dto.MexerNoEstoqueRequest req) {
        return service.mexerNoEstoque(id, req);
    }

    @DeleteMapping("/{id}")
    public void excluir(@PathVariable Long id) {
        service.excluir(id);
    }
}
