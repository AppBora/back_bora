package br.com.bora.service;

import br.com.bora.dto.ItemPedidoRequest;
import br.com.bora.dto.NovoPedidoRequest;
import br.com.bora.dto.PedidoCard;
import br.com.bora.entity.Cliente;
import br.com.bora.entity.LogStatus;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.Produto;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.ClienteRepository;
import br.com.bora.repository.LogStatusRepository;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.repository.TaxaEntregaRepository;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PedidoService {

    private final PedidoRepository repo;
    private final PedidoItemRepository itemRepo;
    private final ProdutoRepository produtos;
    private final ClienteRepository clientes;
    private final LogStatusRepository logs;
    private final PlanoService planos;
    private final IntegracaoService integracoes;
    @org.springframework.beans.factory.annotation.Autowired
    private NotificacaoClienteService notifCliente; // Operação Assistida (Módulo IA)
    private final TaxaEntregaRepository taxasEntrega;
    private final InsumoService insumos;
    private final AuthContext ctx;

    public PedidoService(PedidoRepository repo, PedidoItemRepository itemRepo, ProdutoRepository produtos,
                         ClienteRepository clientes, LogStatusRepository logs, PlanoService planos,
                         IntegracaoService integracoes, TaxaEntregaRepository taxasEntrega,
                         InsumoService insumos, AuthContext ctx) {
        this.repo = repo;
        this.itemRepo = itemRepo;
        this.produtos = produtos;
        this.clientes = clientes;
        this.logs = logs;
        this.planos = planos;
        this.integracoes = integracoes;
        this.taxasEntrega = taxasEntrega;
        this.insumos = insumos;
        this.ctx = ctx;
    }

    /** Taxa de entrega ativa para o bairro do cliente (0 se não houver). */
    private BigDecimal taxaPara(Long lojaId, Long clienteId, String origem) {
        if (clienteId == null) return BigDecimal.ZERO;
        if (origem != null && origem.toUpperCase().contains("BALC")) return BigDecimal.ZERO; // balcão não tem frete
        var cli = clientes.findByIdAndLojaId(clienteId, lojaId).orElse(null);
        if (cli == null || cli.bairro == null || cli.bairro.isBlank()) return BigDecimal.ZERO;
        return taxasEntrega.findFirstByLojaIdAndBairroIgnoreCaseAndAtivoTrue(lojaId, cli.bairro.trim())
                .map(t -> t.taxa == null ? BigDecimal.ZERO : t.taxa).orElse(BigDecimal.ZERO);
    }

    public List<Pedido> listar() {
        return repo.findByLojaIdOrderByCriadoEmDesc(ctx.lojaId());
    }

    /** Quadro rico (kanban): pedidos + cliente + itens resolvidos em poucas queries (sem N+1). */
    public List<PedidoCard> board() {
        Long lojaId = ctx.lojaId();
        List<Pedido> pedidos = repo.findByLojaIdOrderByCriadoEmDesc(lojaId);

        Map<Long, Cliente> clientePorId = new LinkedHashMap<>();
        clientes.findByLojaIdOrderByNomeAsc(lojaId).forEach(c -> clientePorId.put(c.id, c));

        Map<Long, List<PedidoCard.ItemResumo>> itensPorPedido = new LinkedHashMap<>();
        for (PedidoItem it : itemRepo.findByLojaId(lojaId)) {
            itensPorPedido.computeIfAbsent(it.getPedidoId(), k -> new ArrayList<>())
                    .add(new PedidoCard.ItemResumo(it.getQuantidade(), it.getDescricao()));
        }

        List<PedidoCard> cards = new ArrayList<>(pedidos.size());
        for (Pedido p : pedidos) {
            Cliente c = p.clienteId == null ? null : clientePorId.get(p.clienteId);
            cards.add(new PedidoCard(
                    p.id, p.codigo, p.status == null ? null : p.status.name(), p.valorTotal,
                    p.formaPagamento, p.origem, p.observacao, p.criadoEm, p.atualizadoEm,
                    c == null ? null : c.nome,
                    c == null ? null : c.telefone,
                    c == null ? null : c.endereco,
                    c == null ? null : c.bairro,
                    p.entregador,
                    itensPorPedido.getOrDefault(p.id, List.of())));
        }
        return cards;
    }

    /** Cria o pedido a partir dos itens; o total é calculado no servidor (autoritativo). */
    @Transactional
    public Pedido criar(NovoPedidoRequest req) {
        Long lojaId = ctx.lojaId();
        planos.checarLimitePedidosMes(lojaId); // RN09
        if (req.itens() == null || req.itens().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O pedido precisa de ao menos um item");
        }

        Pedido p = new Pedido();
        p.lojaId = lojaId; // tenant do token, nunca do corpo
        p.clienteId = req.clienteId();
        p.codigo = req.codigo();
        p.formaPagamento = req.formaPagamento();
        p.origem = req.origem();
        p.observacao = req.observacao();
        p.status = StatusPedido.RECEBIDO;
        p.criadoEm = OffsetDateTime.now();
        p.atualizadoEm = OffsetDateTime.now();

        List<PedidoItem> itens = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ItemPedidoRequest i : req.itens()) {
            Produto prod = produtos.findByIdAndLojaId(i.produtoId(), lojaId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Produto inválido no pedido"));
            if (i.quantidade() != null && i.quantidade() < 1)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantidade deve ser maior que zero");
            int qtd = i.quantidade() == null ? 1 : i.quantidade();
            BigDecimal preco = prod.preco != null ? prod.preco : BigDecimal.ZERO;
            BigDecimal subtotal = preco.multiply(BigDecimal.valueOf(qtd));
            total = total.add(subtotal);

            PedidoItem it = new PedidoItem();
            it.setLojaId(lojaId);
            it.setProdutoId(prod.id);
            it.setDescricao(prod.nome);
            it.setQuantidade(qtd);
            it.setPrecoUnitario(preco);
            // se o produto tem ficha técnica, consome insumos e usa o custo da ficha; senão, custo do produto + baixa do próprio
            BigDecimal custoFicha = insumos.consumirFicha(lojaId, prod.id, qtd);
            it.setCustoUnitario(custoFicha != null ? custoFicha : prod.custo);
            it.setSubtotal(subtotal);
            itens.add(it);

            if (custoFicha == null && prod.estoque != null) { // baixa de estoque do produto (só sem ficha)
                prod.estoque = prod.estoque - qtd;
                produtos.save(prod);
            }
        }

        // resgate de cashback como desconto (RN fidelidade)
        BigDecimal resgate = BigDecimal.ZERO;
        if (Boolean.TRUE.equals(req.usarCashback()) && req.clienteId() != null) {
            var cli = clientes.findByIdAndLojaId(req.clienteId(), lojaId).orElse(null);
            if (cli != null && cli.cashback != null && cli.cashback.signum() > 0) {
                resgate = cli.cashback.min(total);
            }
        }
        BigDecimal taxa = taxaPara(lojaId, req.clienteId(), req.origem());
        p.taxaEntrega = taxa;
        p.valorTotal = total.add(taxa).subtract(resgate);
        Pedido salvo = repo.save(p);
        itens.forEach(it -> it.setPedidoId(salvo.id));
        itemRepo.saveAll(itens);
        acumularFidelidade(lojaId, req.clienteId(), p.valorTotal, resgate); // CRM / cashback
        return salvo;
    }

    /** CRM: atualiza gasto/quantidade, debita o cashback resgatado e credita 5% sobre o pago. */
    private void acumularFidelidade(Long lojaId, Long clienteId, BigDecimal totalPago, BigDecimal resgate) {
        if (clienteId == null) return;
        BigDecimal usado = resgate == null ? BigDecimal.ZERO : resgate;
        clientes.findByIdAndLojaId(clienteId, lojaId).ifPresent(c -> {
            BigDecimal gasto = c.totalGasto == null ? BigDecimal.ZERO : c.totalGasto;
            BigDecimal saldo = c.cashback == null ? BigDecimal.ZERO : c.cashback;
            c.totalGasto = gasto.add(totalPago);
            c.qtdPedidos = (c.qtdPedidos == null ? 0 : c.qtdPedidos) + 1;
            c.cashback = saldo.subtract(usado)
                    .add(totalPago.multiply(new BigDecimal("0.05")))
                    .max(BigDecimal.ZERO)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            c.ultimoPedido = OffsetDateTime.now();
            clientes.save(c);
        });
    }

    /** Cria pedido recebido de um marketplace (sem JWT) — usado pelos webhooks. */
    @Transactional
    public Pedido criarExterno(Long lojaId, String canalCodigo, String origemLabel, br.com.bora.dto.InboundOrder in) {
        // idempotência: se o marketplace reenviar o mesmo pedido, devolve o existente (não duplica)
        if (in.externalId() != null && !in.externalId().isBlank()) {
            var dup = repo.findFirstByLojaIdAndCanalExternoAndIdExterno(lojaId, canalCodigo, in.externalId());
            if (dup.isPresent()) return dup.get();
        }
        Pedido p = new Pedido();
        p.lojaId = lojaId;
        p.clienteId = upsertCliente(lojaId, in);
        p.codigo = in.externalId() != null ? in.externalId() : ("#" + System.currentTimeMillis() % 1000000);
        p.formaPagamento = in.pagamento();
        p.origem = origemLabel;
        p.canalExterno = canalCodigo;
        p.idExterno = in.externalId();
        p.observacao = in.observacao();
        p.status = StatusPedido.RECEBIDO;
        p.criadoEm = OffsetDateTime.now();
        p.atualizadoEm = OffsetDateTime.now();

        List<PedidoItem> itens = new ArrayList<>();
        BigDecimal soma = BigDecimal.ZERO;
        if (in.itens() != null) for (var it : in.itens()) {
            int qtd = it.quantidade() == null || it.quantidade() < 1 ? 1 : it.quantidade();
            BigDecimal preco = it.precoUnitario() == null ? BigDecimal.ZERO : it.precoUnitario();
            BigDecimal sub = preco.multiply(BigDecimal.valueOf(qtd));
            soma = soma.add(sub);
            PedidoItem pi = new PedidoItem();
            pi.setLojaId(lojaId);
            pi.setDescricao(it.nome() == null ? "Item" : it.nome());
            pi.setQuantidade(qtd);
            pi.setPrecoUnitario(preco);
            pi.setSubtotal(sub);
            itens.add(pi);
        }
        p.valorTotal = in.total() != null && in.total().signum() > 0 ? in.total() : soma;
        Pedido salvo = repo.save(p);
        itens.forEach(it -> it.setPedidoId(salvo.id));
        itemRepo.saveAll(itens);
        acumularFidelidade(lojaId, salvo.clienteId, p.valorTotal, BigDecimal.ZERO);
        return salvo;
    }

    /** Encontra o cliente pelo telefone (ou cria) — base do CRM com pedidos de marketplace. */
    private Long upsertCliente(Long lojaId, br.com.bora.dto.InboundOrder in) {
        String tel = in.clienteTelefone();
        if (tel != null && !tel.isBlank()) {
            var existente = clientes.findFirstByLojaIdAndTelefone(lojaId, tel);
            if (existente.isPresent()) return existente.get().id;
        }
        if ((tel == null || tel.isBlank()) && (in.clienteNome() == null || in.clienteNome().isBlank())) return null;
        Cliente c = new Cliente();
        c.lojaId = lojaId;
        c.nome = in.clienteNome() == null || in.clienteNome().isBlank() ? "Cliente marketplace" : in.clienteNome();
        c.telefone = tel;
        c.endereco = in.endereco();
        c.bairro = in.bairro();
        return clientes.save(c).id;
    }

    public Pedido definirEntregador(Long id, String entregador) {
        Pedido p = buscarDaLoja(id);
        p.entregador = (entregador == null || entregador.isBlank()) ? null : entregador.trim();
        p.atualizadoEm = OffsetDateTime.now();
        return repo.save(p);
    }

    public List<PedidoItem> itens(Long pedidoId) {
        buscarDaLoja(pedidoId); // garante tenant
        return itemRepo.findByLojaIdAndPedidoIdOrderById(ctx.lojaId(), pedidoId);
    }

    public Pedido alterarStatus(Long id, StatusPedido status, String motivo) {
        Pedido p = buscarDaLoja(id);
        if (p.status == StatusPedido.ENTREGUE || p.status == StatusPedido.CANCELADO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pedido " + p.status + " é um estado final e não pode mudar de status");
        }
        if (status == StatusPedido.CANCELADO && (motivo == null || motivo.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Motivo do cancelamento é obrigatório"); // RN05
        }
        StatusPedido anterior = p.status;
        p.status = status;
        p.atualizadoEm = OffsetDateTime.now();
        if (status == StatusPedido.ENTREGUE) p.entregueEm = OffsetDateTime.now();
        if (status == StatusPedido.CANCELADO) {
            p.canceladoEm = OffsetDateTime.now();
            p.motivoCancelamento = motivo;
        }
        Pedido salvo = repo.save(p);
        registrarLog(salvo, anterior, status); // RN07
        integracoes.notificarStatus(salvo, status.name()); // sincroniza status com o marketplace (se conectado)
        notifCliente.notificarFase(salvo, status); // Operação Assistida: avisa o cliente no WhatsApp
        return salvo;
    }

    public void excluir(Long id) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA"); // RN06
        repo.delete(buscarDaLoja(id));
    }

    public List<LogStatus> historico(Long pedidoId) {
        buscarDaLoja(pedidoId); // garante que o pedido é da loja do usuário
        return logs.findByLojaIdAndPedidoIdOrderByDataHoraAsc(ctx.lojaId(), pedidoId);
    }

    public Map<String, Object> resumo() {
        Long lojaId = ctx.lojaId();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("emPreparo", repo.countByLojaIdAndStatus(lojaId, StatusPedido.EM_PREPARO));
        m.put("saiuParaEntrega", repo.countByLojaIdAndStatus(lojaId, StatusPedido.SAIU_PARA_ENTREGA));
        m.put("entreguesUltimaHora", repo.countByLojaIdAndEntregueEmAfter(lojaId, OffsetDateTime.now().minusHours(1)));
        return m;
    }

    private void registrarLog(Pedido p, StatusPedido anterior, StatusPedido novo) {
        LogStatus log = new LogStatus();
        log.setLojaId(p.lojaId);
        log.setPedidoId(p.id);
        log.setStatusAnterior(anterior == null ? null : anterior.name());
        log.setStatusNovo(novo.name());
        log.setUsuarioId(ctx.atual().userId());
        logs.save(log);
    }

    private Pedido buscarDaLoja(Long id) {
        return repo.findByIdAndLojaId(id, ctx.lojaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado"));
    }
}
