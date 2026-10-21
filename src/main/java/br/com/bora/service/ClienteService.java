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
    private final br.com.bora.repository.PedidoRepository pedidos;
    private final AuthContext ctx;

    public ClienteService(ClienteRepository repo, br.com.bora.repository.PedidoRepository pedidos,
                          AuthContext ctx) {
        this.repo = repo;
        this.pedidos = pedidos;
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
        recusarTelefoneRepetido(c.telefone, null);
        c.endereco = dados.endereco;
        c.bairro = dados.bairro;
        c.referencia = dados.referencia;
        // cashback, totalGasto, qtdPedidos e ultimoPedido comecam do zero: quem mexe neles e a
        // fidelidade, a partir de pedido que aconteceu. canalExterno/idExterno sao de marketplace.
        return repo.save(c);
    }

    public Cliente atualizar(Long id, Cliente dados) {
        Cliente c = buscar(id);
        recusarTelefoneRepetido(FidelidadeService.normalizar(dados.telefone), id);
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
    /**
     * Apaga o cliente e desamarra os pedidos dele, em vez de deixar ponteiro morto.
     *
     * <h2>Item 4.7 da varredura: o pedido ficava orfao</h2>
     * <p>{@code pedido.cliente_id} nao tem chave estrangeira, entao o DELETE passava e os pedidos
     * continuavam apontando para uma linha que nao existe mais.</p>
     *
     * <p><b>O historico fica</b>, e tem que ficar: a venda aconteceu, entrou no faturamento, e o
     * pedido guarda telefone e endereco proprios (ver V48). O que sai e so o ponteiro.</p>
     *
     * <p>Medido em 21/10/2026, o ponteiro morto deixou de ser inofensivo no mesmo dia: com o saldo de
     * cashback virando SQL (item 1.5), {@code FidelidadeService.consumir} passou a estourar 409 quando
     * a consulta nao mexe em linha nenhuma -- e cliente apagado e exatamente esse caso. Um PIX pago
     * DEPOIS do cancelamento, num pedido que usou cashback, de um cliente apagado, devolvia 409 ao
     * webhook do Asaas -- que reenvia enquanto nao recebe 200, entao o pagamento nunca seria
     * registrado. O servico tambem foi consertado para distinguir os dois casos; esta limpeza e a
     * outra ponta, e as duas sao necessarias: o servico cobre o pedido que JA esta orfao na base.</p>
     */
    @org.springframework.transaction.annotation.Transactional
    public void excluir(Long id) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        Cliente c = buscar(id);
        int desamarrados = pedidos.desamarrarCliente(c.id, c.lojaId);
        AUDITORIA.warn("AUDITORIA cliente: usuario {} EXCLUIU o cliente {} ({}) da loja {}; "
                        + "{} pedido(s) dele continuam no historico, agora sem vinculo",
                ctx.atual().userId(), c.id, c.nome, c.lojaId, desamarrados);
        repo.delete(c);
    }

    /**
     * Recusa cadastrar (ou renomear para) um telefone que ja e de outro cliente da loja.
     *
     * <h2>Item 6.10 da varredura</h2>
     * <p>O cadastro nao procurava pelo telefone. Entao o balconista cadastrava "Maria" de novo no dia
     * seguinte, e a loja ficava com dois cadastros da mesma pessoa — <b>e o cashback racha</b>: parte
     * do saldo preso num, parte no outro, e o cliente reclamando de um saldo que a tela nao mostra.
     * O mesmo telefone e a chave que o cardapio e os marketplaces usam para reencontrar a pessoa
     * ({@code findFirstByLojaIdAndTelefone}), entao dois cadastros com o mesmo numero fazem o pedido
     * cair sempre no primeiro que o banco devolver.</p>
     *
     * <h2>Por que RECUSAR e nao juntar os dois</h2>
     * <p>Juntar automaticamente sobrescreveria o nome e o endereco de alguem com o que o operador
     * acabou de digitar — e se o telefone foi digitado errado, o estrago cai no cadastro de outra
     * pessoa, com o saldo dela. Recusar devolve a decisao a quem esta na frente do cliente, e a
     * mensagem diz <b>o nome de quem ja tem aquele numero</b>, que e a informacao que falta para
     * decidir.</p>
     *
     * <p>Telefone em branco nao e disputado: cliente de balcao que nao quis dar o numero continua
     * entrando, e e assim que a maioria entra.</p>
     *
     * @param id o cliente que esta sendo EDITADO, para ele nao brigar com o proprio telefone;
     *           {@code null} na criacao
     */
    private void recusarTelefoneRepetido(String telefone, Long id) {
        if (telefone == null || telefone.isBlank()) return;
        repo.findFirstByLojaIdAndTelefone(ctx.lojaId(), telefone)
                .filter(outro -> !outro.id.equals(id))
                .ifPresent(outro -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "O telefone " + telefone + " ja e do cliente \"" + outro.nome
                                    + "\". Edite o cadastro dele em vez de criar outro, senao o "
                                    + "cashback fica dividido entre os dois.");
                });
    }

    private Cliente buscar(Long id) {
        return repo.findByIdAndLojaId(id, ctx.lojaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado"));
    }
}
