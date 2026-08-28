package br.com.bora.service;

import br.com.bora.entity.Insumo;
import br.com.bora.entity.ProdutoInsumo;
import br.com.bora.repository.InsumoRepository;
import br.com.bora.repository.ProdutoInsumoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

/** Insumos (ingredientes) e ficha técnica dos produtos; consome insumos a cada venda. */
@Service
public class InsumoService {

    private final InsumoRepository insumos;
    private final ProdutoInsumoRepository fichas;
    private final ProdutoRepository produtos;
    private final AuthContext ctx;

    public InsumoService(InsumoRepository insumos, ProdutoInsumoRepository fichas, ProdutoRepository produtos, AuthContext ctx) {
        this.insumos = insumos;
        this.fichas = fichas;
        this.produtos = produtos;
        this.ctx = ctx;
    }

    // ---- CRUD insumos ----
    public List<Insumo> listar() { return insumos.findByLojaIdOrderByNomeAsc(ctx.lojaId()); }

    public Insumo salvar(Insumo i) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        Long loja = ctx.lojaId();
        Insumo alvo = i.id == null ? new Insumo()
                : insumos.findByIdAndLojaId(i.id, loja).orElseThrow(() -> nf("Insumo"));
        alvo.lojaId = loja; alvo.nome = i.nome; alvo.unidade = i.unidade == null ? "un" : i.unidade;
        alvo.custo = i.custo == null ? BigDecimal.ZERO : i.custo; alvo.estoque = i.estoque; alvo.estoqueMinimo = i.estoqueMinimo;
        if (alvo.nome == null || alvo.nome.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do insumo é obrigatório");
        return insumos.save(alvo);
    }

    public void excluir(Long id) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        insumos.delete(insumos.findByIdAndLojaId(id, ctx.lojaId()).orElseThrow(() -> nf("Insumo"))); }

    // ---- Ficha técnica ----
    public List<Map<String, Object>> ficha(Long produtoId) {
        Long loja = ctx.lojaId();
        Map<Long, Insumo> map = new HashMap<>();
        insumos.findByLojaIdOrderByNomeAsc(loja).forEach(i -> map.put(i.id, i));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProdutoInsumo pi : fichas.findByLojaIdAndProdutoId(loja, produtoId)) {
            Insumo in = map.get(pi.insumoId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("insumoId", pi.insumoId);
            m.put("nome", in == null ? "?" : in.nome);
            m.put("unidade", in == null ? "un" : in.unidade);
            m.put("custo", in == null ? BigDecimal.ZERO : in.custo);
            m.put("quantidade", pi.quantidade);
            out.add(m);
        }
        return out;
    }

    @Transactional
    public List<Map<String, Object>> salvarFicha(Long produtoId, List<Map<String, Object>> itens) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        Long loja = ctx.lojaId();
        produtos.findByIdAndLojaId(produtoId, loja).orElseThrow(() -> nf("Produto")); // impede ficha em produto de outra loja
        fichas.deleteByLojaIdAndProdutoId(loja, produtoId);
        if (itens != null) for (Map<String, Object> it : itens) {
            if (it.get("insumoId") == null) continue;
            Long insumoId = Long.valueOf(String.valueOf(it.get("insumoId")));
            if (insumos.findByIdAndLojaId(insumoId, loja).isEmpty()) continue; // ignora insumo de outra loja
            ProdutoInsumo pi = new ProdutoInsumo();
            pi.lojaId = loja; pi.produtoId = produtoId;
            pi.insumoId = insumoId;
            pi.quantidade = new BigDecimal(String.valueOf(it.getOrDefault("quantidade", "0")));
            fichas.save(pi);
        }
        return ficha(produtoId);
    }

    /**
     * Consome a ficha técnica de um produto numa venda: baixa o estoque dos insumos
     * e devolve o custo unitário (soma insumo.custo × quantidade). null se o produto não tem ficha.
     */
    @Transactional
    public BigDecimal consumirFicha(Long lojaId, Long produtoId, int qtdPedido) {
        if (produtoId == null) return null;
        List<ProdutoInsumo> ficha = fichas.findByLojaIdAndProdutoId(lojaId, produtoId);
        if (ficha.isEmpty()) return null;
        BigDecimal custoUnit = BigDecimal.ZERO;
        for (ProdutoInsumo pi : ficha) {
            Insumo in = insumos.findByIdAndLojaId(pi.insumoId, lojaId).orElse(null);
            if (in == null) continue;
            BigDecimal qtdUnit = pi.quantidade == null ? BigDecimal.ZERO : pi.quantidade;
            custoUnit = custoUnit.add((in.custo == null ? BigDecimal.ZERO : in.custo).multiply(qtdUnit));
            if (in.estoque != null) {
                in.estoque = in.estoque.subtract(qtdUnit.multiply(BigDecimal.valueOf(qtdPedido)));
                insumos.save(in);
            }
        }
        return custoUnit;
    }

    private ResponseStatusException nf(String o) { return new ResponseStatusException(HttpStatus.NOT_FOUND, o + " não encontrado"); }
}
