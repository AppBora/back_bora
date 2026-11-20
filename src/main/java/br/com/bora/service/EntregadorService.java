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
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        if (e.nome == null || e.nome.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do entregador é obrigatório");
        }
        e.lojaId = ctx.lojaId();
        e.id = null;
        if (e.ativo == null) e.ativo = true;
        return repo.save(e);
    }

    public Entregador atualizar(Long id, Entregador dados) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
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
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        repo.delete(buscar(id));
    }

    /**
     * Qual CADASTRO de motoboy este pedido esta apontando? (item 3 da secao 4)
     *
     * <p>Aceita as duas formas, de proposito:</p>
     * <ul>
     *   <li><b>id</b> -- o que a tela de Entregas manda desde 20/11/2026. Id de outra loja e
     *       recusado (404), nunca aceito em silencio;</li>
     *   <li><b>nome</b> -- o que a tela mandava antes, e o que qualquer chamada antiga ainda manda.
     *       Casa pelo nome normalizado (sem caixa, sem espaco nas pontas), a MESMA normalizacao que
     *       as consultas do acerto usam. Se o nome nao casar com EXATAMENTE UM cadastro, devolve
     *       null: o pedido fica com o texto e sem id, que e o comportamento de antes. Chutar entre
     *       dois cadastros de mesmo nome moveria a taxa de um motoboy para outro.</li>
     * </ul>
     *
     * @return o cadastro, ou {@code null} quando nao ha nome nem id (apagar o motoboy do pedido) ou
     *         quando o nome nao casa com um unico cadastro
     */
    public Entregador resolver(Long id, String nome) {
        if (id != null) return buscar(id);
        String procurado = normalizar(nome);
        if (procurado == null) return null;
        List<Entregador> casam = repo.findByLojaIdOrderByNomeAsc(ctx.lojaId()).stream()
                .filter(e -> procurado.equals(normalizar(e.nome)))
                .toList();
        return casam.size() == 1 ? casam.get(0) : null;
    }

    /** `lower(trim(...))`, a mesma chave que o acerto usa no banco. */
    private static String normalizar(String nome) {
        if (nome == null) return null;
        String limpo = nome.trim().toLowerCase();
        return limpo.isEmpty() ? null : limpo;
    }

    private Entregador buscar(Long id) {
        return repo.findByIdAndLojaId(id, ctx.lojaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entregador não encontrado"));
    }
}
