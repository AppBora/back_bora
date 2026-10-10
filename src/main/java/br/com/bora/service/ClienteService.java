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

    private static final org.slf4j.Logger AUDITORIA =
            org.slf4j.LoggerFactory.getLogger("AUDITORIA");

    private final ClienteRepository repo;
    private final AuthContext ctx;

    public ClienteService(ClienteRepository repo, AuthContext ctx) {
        this.repo = repo;
        this.ctx = ctx;
    }

    public List<Cliente> listar() {
        return repo.findByLojaIdOrderByNomeAsc(ctx.lojaId());
    }

    /**
     * Cria cliente a partir do que o painel manda — copiando SO o que e cadastro.
     *
     * <p><b>O furo que isto fecha (09/10/2026):</b> o corpo da requisicao era a entidade {@code Cliente}
     * inteira e o {@code save} gravava tudo. Zerava-se {@code id} e {@code lojaId} e mais nada, entao
     * {@code cashback}, {@code totalGasto}, {@code qtdPedidos}, {@code canalExterno} e {@code idExterno}
     * vinham de fora. Um POST com {@code "cashback": 9999} criava cliente com saldo, e o saldo e
     * dinheiro do lojista: bastava gastar no pedido seguinte. O endpoint nao exige papel nenhum, entao
     * qualquer operador conseguia.</p>
     *
     * <p>Em vez de confiar na lista de campos que eu lembrei de zerar, montamos um cliente NOVO e
     * copiamos campo por campo. Campo novo na entidade nasce fora do alcance de quem chama, que e o
     * lado certo para errar.</p>
     */
    public Cliente criar(Cliente dados) {
        if (dados == null || dados.nome == null || dados.nome.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do cliente é obrigatório");
        }
        Cliente c = new Cliente();
        c.lojaId = ctx.lojaId();
        c.nome = dados.nome;
        c.telefone = FidelidadeService.normalizar(dados.telefone);
        c.endereco = dados.endereco;
        c.bairro = dados.bairro;
        c.referencia = dados.referencia;
        // cashback, totalGasto, qtdPedidos e ultimoPedido comecam do zero: quem mexe neles e a
        // fidelidade, a partir de pedido que aconteceu. canalExterno/idExterno sao de marketplace.
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

    /**
     * Apagar cliente e de gerente para cima.
     *
     * <p>Nao havia checagem de papel nenhuma: um OPERADOR — o balconista — podia apagar a base de
     * clientes da loja, com telefone, endereco e saldo de cashback, um por um. Listar e criar continuam
     * liberados porque a Frente de Caixa e o Novo Pedido precisam disso para vender; o que o balconista
     * nao precisa e de apagar. Mesma regra que o pedido ja seguia (RN06).</p>
     *
     * <p>O cliente e apagado de verdade, e {@code pedido.cliente_id} nao tem chave estrangeira: o
     * historico fica apontando para o nada. Trocar por arquivamento e tarefa separada; por ora o acesso
     * a acao fica restrito.</p>
     */
    public void excluir(Long id) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        Cliente c = buscar(id);
        AUDITORIA.warn("AUDITORIA cliente: usuario {} EXCLUIU o cliente {} ({}) da loja {}",
                ctx.atual().userId(), c.id, c.nome, c.lojaId);
        repo.delete(c);
    }

    private Cliente buscar(Long id) {
        return repo.findByIdAndLojaId(id, ctx.lojaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado"));
    }
}
