package br.com.bora.service;

import br.com.bora.entity.Cliente;
import br.com.bora.repository.ClienteRepository;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class ClienteService {

    private final ClienteRepository repo;
    private final AuthContext ctx;

    public ClienteService(ClienteRepository repo, AuthContext ctx) {
        this.repo = repo;
        this.ctx = ctx;
    }

    public List<Cliente> listar() {
        return repo.findByLojaIdOrderByNomeAsc(ctx.lojaId());
    }

    public Cliente criar(Cliente c) {
        if (c.nome == null || c.nome.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do cliente é obrigatório");
        }
        c.lojaId = ctx.lojaId();
        c.id = null;
        c.telefone = FidelidadeService.normalizar(c.telefone);
        return repo.save(c);
    }

    public Cliente atualizar(Long id, Cliente dados) {
        Cliente c = buscar(id);
        c.nome = dados.nome;
        // Só dígitos, igual ao que o cardápio e os marketplaces gravam. Guardar "(15) 99999-0001" aqui
        // fazia o cliente cadastrado pelo lojista NÃO ser encontrado quando ele pedisse pelo cardápio:
        // nascia um segundo cadastro e o cashback acumulado ficava preso no primeiro.
        c.telefone = FidelidadeService.normalizar(dados.telefone);
        c.endereco = dados.endereco;
        c.bairro = dados.bairro;
        c.referencia = dados.referencia;
        return repo.save(c);
    }

    public void excluir(Long id) {
        repo.delete(buscar(id));
    }

    private Cliente buscar(Long id) {
        return repo.findByIdAndLojaId(id, ctx.lojaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado"));
    }
}
