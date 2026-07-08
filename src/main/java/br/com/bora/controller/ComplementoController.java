package br.com.bora.controller;

import br.com.bora.entity.ComplementoGrupo;
import br.com.bora.entity.ComplementoItem;
import br.com.bora.repository.ComplementoGrupoRepository;
import br.com.bora.repository.ComplementoItemRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

/** Gestão dos complementos/adicionais de um produto (tamanho, borda, extras). */
@RestController
@RequestMapping("/api/produtos/{produtoId}/complementos")
public class ComplementoController {

    private final ComplementoGrupoRepository grupos;
    private final ComplementoItemRepository itens;
    private final ProdutoRepository produtos;
    private final AuthContext ctx;

    public ComplementoController(ComplementoGrupoRepository grupos, ComplementoItemRepository itens,
                                 ProdutoRepository produtos, AuthContext ctx) {
        this.grupos = grupos;
        this.itens = itens;
        this.produtos = produtos;
        this.ctx = ctx;
    }

    @GetMapping
    public List<Map<String, Object>> listar(@PathVariable Long produtoId) {
        Long lojaId = ctx.lojaId();
        validarProduto(lojaId, produtoId);
        return montar(lojaId, produtoId);
    }

    /** Substitui TODOS os grupos/itens do produto (a tela salva o conjunto inteiro). */
    @PutMapping
    @Transactional
    public List<Map<String, Object>> salvar(@PathVariable Long produtoId,
                                            @RequestBody List<Map<String, Object>> corpo) {
        Long lojaId = ctx.lojaId();
        validarProduto(lojaId, produtoId);
        List<Long> antigos = grupos.findByLojaIdAndProdutoIdOrderById(lojaId, produtoId)
                .stream().map(g -> g.id).toList();
        if (!antigos.isEmpty()) itens.deleteByLojaIdAndGrupoIdIn(lojaId, antigos);
        grupos.deleteByLojaIdAndProdutoId(lojaId, produtoId);
        if (corpo != null) {
            for (Map<String, Object> g : corpo) {
                String nome = str(g.get("nome"));
                if (nome == null || nome.isBlank()) continue;
                ComplementoGrupo cg = new ComplementoGrupo();
                cg.lojaId = lojaId;
                cg.produtoId = produtoId;
                cg.nome = nome.trim();
                cg.minimo = intVal(g.get("minimo"), 0);
                cg.maximo = Math.max(1, intVal(g.get("maximo"), 1));
                cg = grupos.save(cg);
                Object lista = g.get("itens");
                if (lista instanceof List<?> ls) {
                    for (Object o : ls) {
                        if (!(o instanceof Map<?, ?> m)) continue;
                        String ni = str(m.get("nome"));
                        if (ni == null || ni.isBlank()) continue;
                        ComplementoItem ci = new ComplementoItem();
                        ci.lojaId = lojaId;
                        ci.grupoId = cg.id;
                        ci.nome = ni.trim();
                        try { ci.preco = new BigDecimal(str(m.get("preco"))); } catch (Exception e) { ci.preco = BigDecimal.ZERO; }
                        itens.save(ci);
                    }
                }
            }
        }
        return montar(lojaId, produtoId);
    }

    private List<Map<String, Object>> montar(Long lojaId, Long produtoId) {
        List<ComplementoGrupo> gs = grupos.findByLojaIdAndProdutoIdOrderById(lojaId, produtoId);
        if (gs.isEmpty()) return List.of();
        Map<Long, List<ComplementoItem>> porGrupo = new HashMap<>();
        itens.findByLojaIdAndGrupoIdInOrderById(lojaId, gs.stream().map(g -> g.id).toList())
                .forEach(i -> porGrupo.computeIfAbsent(i.grupoId, k -> new ArrayList<>()).add(i));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ComplementoGrupo g : gs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.id);
            m.put("nome", g.nome);
            m.put("minimo", g.minimo);
            m.put("maximo", g.maximo);
            m.put("itens", porGrupo.getOrDefault(g.id, List.of()).stream().map(i -> {
                Map<String, Object> mi = new LinkedHashMap<>();
                mi.put("id", i.id);
                mi.put("nome", i.nome);
                mi.put("preco", i.preco);
                return mi;
            }).toList());
            out.add(m);
        }
        return out;
    }

    private void validarProduto(Long lojaId, Long produtoId) {
        produtos.findById(produtoId).filter(p -> lojaId.equals(p.lojaId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não encontrado"));
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
    private int intVal(Object o, int def) { try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return def; } }
}
