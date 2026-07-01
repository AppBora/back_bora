package br.com.bora.service;

import br.com.bora.entity.Entregador;
import br.com.bora.repository.EntregadorRepository;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class EntregadorService {

    private final EntregadorRepository repo;
    private final AuthContext ctx;

    public EntregadorService(EntregadorRepository repo, AuthContext ctx) {
        this.repo = repo;
        this.ctx = ctx;
    }

    public List<Entregador> listar() {
        return repo.findByLojaIdOrderByNomeAsc(ctx.lojaId());
    }

    public Entregador criar(Entregador e) {
        if (e.nome == null || e.nome.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do entregador é obrigatório");
        }
        e.lojaId = ctx.lojaId();
        e.id = null;
        if (e.ativo == null) e.ativo = true;
        return repo.save(e);
    }

    public Entregador atualizar(Long id, Entregador dados) {
        if (dados.nome == null || dados.nome.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do entregador é obrigatório");
        }
        Entregador e = buscar(id);
        e.nome = dados.nome;
        e.telefone = dados.telefone;
        e.veiculo = dados.veiculo;
        if (dados.ativo != null) e.ativo = dados.ativo;
        return repo.save(e);
    }

    public void excluir(Long id) {
        repo.delete(buscar(id));
    }

    private Entregador buscar(Long id) {
        return repo.findByIdAndLojaId(id, ctx.lojaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entregador não encontrado"));
    }
}
