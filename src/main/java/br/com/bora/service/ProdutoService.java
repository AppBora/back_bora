package br.com.bora.service;

import br.com.bora.entity.Produto;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class ProdutoService {

    private final ProdutoRepository repo;
    private final AuthContext ctx;

    public ProdutoService(ProdutoRepository repo, AuthContext ctx) {
        this.repo = repo;
        this.ctx = ctx;
    }

    public List<Produto> listar() {
        return repo.findByLojaIdOrderByNomeAsc(ctx.lojaId());
    }

    public Produto criar(Produto p) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        validarNome(p.nome);
        p.lojaId = ctx.lojaId();
        p.id = null;
        return repo.save(p);
    }

    public Produto atualizar(Long id, Produto dados) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        validarNome(dados.nome);
        Produto p = buscar(id);
        p.nome = dados.nome;
        p.categoria = dados.categoria;
        p.preco = dados.preco;
        p.custo = dados.custo;
        p.estoque = dados.estoque;
        p.estoqueMinimo = dados.estoqueMinimo;
        if (dados.imagemUrl != null) p.imagemUrl = dados.imagemUrl.isBlank() ? null : dados.imagemUrl;
        if (dados.ativo != null) p.ativo = dados.ativo;
        return repo.save(p);
    }

    public void excluir(Long id) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        repo.delete(buscar(id));
    }

    private void validarNome(String nome) {
        if (nome == null || nome.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do produto é obrigatório");
        }
    }

    private Produto buscar(Long id) {
        return repo.findByIdAndLojaId(id, ctx.lojaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não encontrado"));
    }
}
