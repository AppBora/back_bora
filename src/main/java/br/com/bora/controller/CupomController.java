package br.com.bora.controller;

import br.com.bora.entity.Cupom;
import br.com.bora.repository.CupomRepository;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Gestão de cupons de desconto do cardápio digital. */
@RestController
@RequestMapping("/api/cupons")
public class CupomController {

    private final CupomRepository repo;
    private final AuthContext ctx;

    public CupomController(CupomRepository repo, AuthContext ctx) {
        this.repo = repo;
        this.ctx = ctx;
    }

    @GetMapping
    public List<Cupom> listar() {
        return repo.findByLojaIdOrderByCodigoAsc(ctx.lojaId());
    }

    @PostMapping
    public Cupom criar(@RequestBody Map<String, String> body) {
        ctx.requirePapel("ADMINISTRADOR_LOJA", "GERENTE");
        Long lojaId = ctx.lojaId();
        String codigo = body.get("codigo") == null ? "" : body.get("codigo").trim().toUpperCase();
        if (codigo.isBlank() || codigo.length() > 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Código inválido");
        }
        repo.findByLojaIdAndCodigoIgnoreCase(lojaId, codigo).ifPresent(c -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um cupom com esse código");
        });
        Cupom c = new Cupom();
        c.lojaId = lojaId;
        c.codigo = codigo;
        c.tipo = "VALOR".equalsIgnoreCase(body.get("tipo")) ? "VALOR" : "PERCENTUAL";
        try { c.valor = new BigDecimal(body.get("valor")); } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valor inválido");
        }
        if (c.valor.compareTo(BigDecimal.ZERO) <= 0
                || ("PERCENTUAL".equals(c.tipo) && c.valor.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valor do desconto inválido");
        }
        if (body.get("validade") != null && !body.get("validade").isBlank()) {
            c.validade = LocalDate.parse(body.get("validade"));
        }
        return repo.save(c);
    }

    @DeleteMapping("/{id}")
    public void excluir(@PathVariable Long id) {
        ctx.requirePapel("ADMINISTRADOR_LOJA", "GERENTE");
        Cupom c = repo.findByIdAndLojaId(id, ctx.lojaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cupom não encontrado"));
        repo.delete(c);
    }
}
