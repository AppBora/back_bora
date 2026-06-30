package br.com.bora.controller;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Produto;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.ProdutoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Endpoints públicos (sem autenticação) — cardápio digital acessível por QR Code. */
@RestController
@RequestMapping("/public")
public class PublicController {

    private final LojaRepository lojas;
    private final ProdutoRepository produtos;

    public PublicController(LojaRepository lojas, ProdutoRepository produtos) {
        this.lojas = lojas;
        this.produtos = produtos;
    }

    /** Cardápio público de uma loja: nome + produtos ativos (somente leitura). */
    @GetMapping("/loja/{lojaId}/cardapio")
    public Map<String, Object> cardapio(@PathVariable Long lojaId) {
        Loja loja = lojas.findById(lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
        if (Boolean.FALSE.equals(loja.ativo)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja indisponível");
        }
        List<Produto> itens = produtos.findByLojaIdAndAtivoTrueOrderByCategoriaAscNomeAsc(lojaId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("loja", Map.of("id", loja.id, "nome", loja.nome == null ? "Cardápio" : loja.nome));
        resp.put("produtos", itens.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id);
            m.put("nome", p.nome);
            m.put("categoria", p.categoria == null || p.categoria.isBlank() ? "Outros" : p.categoria);
            m.put("preco", p.preco);
            return m;
        }).toList());
        return resp;
    }
}
