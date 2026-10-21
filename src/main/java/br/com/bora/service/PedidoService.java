package br.com.bora.service;

import br.com.bora.dto.ItemPedidoRequest;
import br.com.bora.dto.NovoPedidoRequest;
import br.com.bora.dto.PedidoCard;
import br.com.bora.entity.Cliente;
import br.com.bora.entity.LarguraDeColuna;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Map;

@Service
public class PedidoService {

    private static final org.slf4j.Logger AUDITORIA = org.slf4j.LoggerFactory.getLogger(PedidoService.class);

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
    private final FidelidadeService fidelidade;
    private final ComplementoService complementos;
    private final AuthContext ctx;
    private final EstornoDaVendaService devolucao;
    /** Transacao explicita, para agrupar o que precisa cair junto sem prender chamada externa. */
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public PedidoService(PedidoRepository repo, PedidoItemRepository itemRepo, ProdutoRepository produtos,
                         ClienteRepository clientes, LogStatusRepository logs, PlanoService planos,
                         IntegracaoService integracoes, TaxaEntregaRepository taxasEntrega,
                         InsumoService insumos, FidelidadeService fidelidade,
                         ComplementoService complementos, AuthContext ctx,
                         EstornoDaVendaService devolucao,
                         org.springframework.transaction.PlatformTransactionManager gerenciadorDeTransacao) {
        this.fidelidade = fidelidade;
        this.complementos = complementos;
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
        this.devolucao = devolucao;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(gerenciadorDeTransacao);
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

    static final java.util.Set<StatusPedido> ENCERRADOS =
            java.util.Set.of(StatusPedido.ENTREGUE, StatusPedido.CANCELADO);

    /**
     * Os status em que o pedido ainda esta vivo — o que a cozinha precisa ver.
     *
     * <p>Lista POSITIVA de proposito: a consulta do quadro era {@code status not in (ENCERRADOS)}, e
     * nenhum indice serve uma negacao. O KDS recarrega a cada 6 segundos e cada volta varria todos os
     * pedidos da loja. Ver o javadoc de {@code PedidoRepository.findByLojaIdAndStatusIn} e a V54.</p>
     *
     * <p><b>Acrescentou status no enum? Classifique AQUI ou em ENCERRADOS.</b> Esquecer faz o pedido
     * desaparecer do quadro da cozinha, calado. O {@code AbertosMaisEncerradosCobremTudoTest} reprova
     * se os dois conjuntos deixarem de cobrir o enum inteiro.</p>
     */
    static final java.util.Set<StatusPedido> ABERTOS = java.util.Set.of(
            StatusPedido.RECEBIDO,
            StatusPedido.CONFIRMADO,
            StatusPedido.EM_PREPARO,
            StatusPedido.PRONTO,
            StatusPedido.SAIU_PARA_ENTREGA);

    /**
     * Quadro rico (kanban): pedidos + cliente + itens resolvidos em poucas queries (sem N+1).
     *
     * <p>O quadro é do <b>dia</b>, não da loja inteira. Antes trazia todo pedido já feito e todos os
     * itens da loja a cada atualização: a coluna "Entregue" ia empilhando dia após dia e o balcão
     * abria a manhã com a tela suja do dia anterior — fora o peso, que só cresce.</p>
     *
     * <p>Exceção proposital: pedido ainda em andamento aparece mesmo sendo de outro dia. Quem entrou
     * às 23h50 e está em preparo à meia-noite não pode sumir da vista da cozinha.</p>
     */
    public List<PedidoCard> board(java.time.LocalDate dia, java.time.LocalDate desde) {
        Long lojaId = ctx.lojaId();
        java.time.ZoneId zona = java.time.ZoneId.of("America/Sao_Paulo");
        java.time.LocalDate hoje = java.time.LocalDate.now(zona);
        // "desde" serve às telas que somam período (canais, entregadores); "dia", ao quadro do balcão
        java.time.LocalDate inicio = desde != null ? desde : (dia == null ? hoje : dia);
        java.time.LocalDate ultimo = desde != null ? hoje : inicio;
        OffsetDateTime ini = inicio.atStartOfDay(zona).toOffsetDateTime();
        OffsetDateTime fim = ultimo.plusDays(1).atStartOfDay(zona).toOffsetDateTime();

        Map<Long, Pedido> porId = new LinkedHashMap<>();
        repo.findByLojaIdAndCriadoEmGreaterThanEqualAndCriadoEmLessThanOrderByCriadoEmDesc(lojaId, ini, fim)
                .forEach(p -> porId.put(p.id, p));
        // só faz sentido puxar os abertos de outros dias quando o período alcança hoje
        if (ultimo.equals(hoje)) {
            repo.findByLojaIdAndStatusInOrderByCriadoEmDesc(lojaId, ABERTOS)
                    .forEach(p -> porId.putIfAbsent(p.id, p));
        }
        // criadoEm nulo não deveria existir (o campo nasce preenchido), mas uma linha antiga vinda de
        // carga direta derrubaria o quadro inteiro num NullPointerException. Ordena por último.
        List<Pedido> pedidos = porId.values().stream()
                .sorted(Comparator.comparing((Pedido p) -> p.criadoEm,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();

        // Só os clientes que aparecem nestes cards. Antes vinha a lista inteira da loja a cada
        // atualização — e o painel atualiza a cada 6 segundos, em várias telas abertas ao mesmo tempo.
        // Numa loja com milhares de clientes isso era dezenas de milhares de registros por minuto,
        // para mostrar o nome de uns 30 pedidos.
        Set<Long> idsDeClientes = pedidos.stream()
                .map(p -> p.clienteId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Cliente> clientePorId = new LinkedHashMap<>();
        if (!idsDeClientes.isEmpty()) {
            clientes.findByLojaIdAndIdIn(lojaId, idsDeClientes).forEach(c -> clientePorId.put(c.id, c));
        }

        Map<Long, List<PedidoCard.ItemResumo>> itensPorPedido = new LinkedHashMap<>();
        List<Long> ids = pedidos.stream().map(p -> p.id).toList();
        for (PedidoItem it : ids.isEmpty() ? List.<PedidoItem>of() : itemRepo.findByLojaIdAndPedidoIdIn(lojaId, ids)) {
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
                    c != null && c.telefone != null && !c.telefone.isBlank() ? c.telefone : p.clienteTelefone,
                    c == null ? null : c.endereco,
                    c == null ? null : c.bairro,
                    // O endereco DESTE pedido (V48). A tela ja prefere ele e cai no cadastro quando
                    // vem nulo -- ver o javadoc do PedidoCard.
                    p.enderecoEntrega,
                    p.bairroEntrega,
                    p.tipoEntrega,
                    p.entregador,
                    p.pagamentoPendente(),
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
        // o cliente também: id de outra loja no corpo não pode virar pedido nesta
        p.clienteId = clienteDaLoja(lojaId, req.clienteId());
        p.codigo = req.codigo();
        p.formaPagamento = req.formaPagamento();
        p.origem = req.origem();
        p.observacao = req.observacao();
        // Entrega em coluna, nao deduzida do texto. Quando o operador nao informa, vale o cadastro do
        // cliente — mas gravado NO PEDIDO, para o card de amanha nao mostrar o endereco de depois que
        // o cliente se mudou. Ver V48.
        p.tipoEntrega = "RETIRADA".equalsIgnoreCase(req.tipoEntrega()) ? "RETIRADA" : "ENTREGA";
        if (!"RETIRADA".equals(p.tipoEntrega)) {
            p.enderecoEntrega = primeiroNaoVazio(req.enderecoEntrega(), enderecoDoCadastro(lojaId, p.clienteId));
            p.bairroEntrega = primeiroNaoVazio(req.bairroEntrega(), bairroDoCadastro(lojaId, p.clienteId));
        }
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
            // mesma regra do cardápio público: valida min/máx do grupo e soma o adicional no unitário
            ComplementoService.Escolha escolha = complementos.aplicar(lojaId, prod, i.complementos());
            BigDecimal preco = (prod.preco != null ? prod.preco : BigDecimal.ZERO).add(escolha.acrescimo());
            BigDecimal subtotal = preco.multiply(BigDecimal.valueOf(qtd));
            total = total.add(subtotal);

            PedidoItem it = new PedidoItem();
            it.setLojaId(lojaId);
            it.setProdutoId(prod.id);
            it.setDescricao(escolha.descricao(prod.nome));
            // Nome e preco junto, nao so o id: ver ComplementosDoItem.
            it.setComplementos(ComplementosDoItem.escrever(escolha.escolhidos()));
            it.setQuantidade(qtd);
            it.setPrecoUnitario(preco);
            // se o produto tem ficha técnica, consome insumos e usa o custo da ficha; senão, custo do produto + baixa do próprio
            BigDecimal custoFicha = insumos.consumirFicha(lojaId, prod.id, qtd);
            // O adicional tambem sai do estoque desde a V55, quando tem insumo ligado (item 2.6).
            // Aqui NAO recusa, pelo mesmo motivo do produto: o operador esta com o copo na mao.
            BigDecimal custoAdicional = insumos.consumirAdicionais(lojaId, escolha.escolhidos(), qtd, false);
            // O custo do adicional soma no custo do item -- metade do valor da venda pode estar
            // nele. Mas so quando ha custo de adicional: `custoUnitario` nulo quer dizer "custo nao
            // cadastrado", e nao "de graca". Transformar nulo em zero aqui mudaria o significado de
            // todo item de produto sem custo cadastrado, que nao tem nada a ver com adicional.
            BigDecimal custoBase = custoFicha != null ? custoFicha : prod.custo;
            it.setCustoUnitario(somarCustoDoAdicional(custoBase, custoAdicional));
            it.setConsumiuFicha(custoFicha != null); // ver PedidoItem.consumiuFicha: a devolucao le isto
            it.setSubtotal(subtotal);
            itens.add(it);

            // Baixa de estoque do produto (so sem ficha), no BANCO. Era ler, subtrair e gravar: duas
            // vendas do mesmo item ao mesmo tempo liam o mesmo estoque e uma das baixas desaparecia.
            //
            // AQUI A VENDA PASSA MESMO SEM SALDO, de proposito -- diferente do cardapio publico, que
            // recusa. O operador esta com o produto na mao: travar a venda com o cliente na frente e
            // pior que o saldo negativo, que pelo menos denuncia que a contagem esta errada. Mas nao
            // pode passar CALADO, que era o que acontecia: o numero ia a negativo e ninguem ficava
            // sabendo.
            if (custoFicha == null) {
                avisarSeVaiFicarNegativo(lojaId, prod, qtd);
                produtos.baixarEstoque(prod.id, lojaId, qtd);
            }
        }

        // resgate de cashback como desconto (RN fidelidade)
        BigDecimal resgate = BigDecimal.ZERO;
        if (Boolean.TRUE.equals(req.usarCashback())) {
            resgate = fidelidade.resgatePossivel(lojaId, p.clienteId, total);
        }
        // O balconista pode combinar um frete diferente da tabela (bairro fora da lista, cortesia).
        // Só respeitamos valor não negativo; sem informar nada, vale a tabela do bairro.
        BigDecimal taxa = req.taxaEntrega() != null && req.taxaEntrega().signum() >= 0
                ? req.taxaEntrega()
                : taxaPara(lojaId, p.clienteId, req.origem());
        p.taxaEntrega = taxa;
        p.valorTotal = total.add(taxa).subtract(resgate);
        // Em campo proprio, igual ao cardapio. Sem isto o pedido de balcao descontava o cashback e nao
        // deixava registro de quanto: no cancelamento nao havia o que devolver, e o cliente perdia o
        // saldo que gastou numa venda que nao aconteceu.
        if (resgate.signum() > 0) p.cashbackUsado = resgate;
        Pedido salvo = repo.save(p);
        itens.forEach(it -> it.setPedidoId(salvo.id));
        itemRepo.saveAll(itens);
        acumularFidelidade(lojaId, p.clienteId, p.valorTotal, resgate); // CRM / cashback
        return salvo;
    }

    private static String primeiroNaoVazio(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        return b == null || b.isBlank() ? null : b.trim();
    }

    private String enderecoDoCadastro(Long lojaId, Long clienteId) {
        if (clienteId == null) return null;
        return clientes.findByIdAndLojaId(clienteId, lojaId).map(c -> c.endereco).orElse(null);
    }

    private String bairroDoCadastro(Long lojaId, Long clienteId) {
        if (clienteId == null) return null;
        return clientes.findByIdAndLojaId(clienteId, lojaId).map(c -> c.bairro).orElse(null);
    }

    /** Cliente informado tem que ser desta loja — senão o pedido nasce apontando para outro tenant. */
    private Long clienteDaLoja(Long lojaId, Long clienteId) {
        if (clienteId == null) return null;
        return clientes.findByIdAndLojaId(clienteId, lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cliente não encontrado nesta loja"))
                .id;
    }

    /** CRM e cashback — a regra mora no FidelidadeService, compartilhada com o cardápio online. */
    private void acumularFidelidade(Long lojaId, Long clienteId, BigDecimal totalPago, BigDecimal resgate) {
        fidelidade.registrar(lojaId, clienteId, totalPago, resgate);
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
        String telReal = telefoneDePessoa(in.clienteTelefone());
        p.clienteId = upsertCliente(lojaId, canalCodigo, in, telReal);
        // O telefone do pedido fica no pedido: no iFood é a central + código, que não vai para o cadastro.
        p.clienteTelefone = in.clienteTelefone();
        // O id do Open Delivery e um UUID: inutil para o atendente. Usa o numero que o cliente ve.
        p.codigo = cortar(in.numeroExibicao() != null && !in.numeroExibicao().isBlank() ? in.numeroExibicao()
                : in.externalId() != null ? in.externalId() : ("#" + System.currentTimeMillis() % 1000000),
                LarguraDeColuna.PEDIDO_CODIGO);
        // Texto do marketplace pode crescer (varias formas de pagamento): corta em vez de recusar o pedido.
        p.formaPagamento = cortar(in.pagamento(), LarguraDeColuna.PEDIDO_FORMA_PAGAMENTO);
        p.clienteTelefone = cortar(p.clienteTelefone, LarguraDeColuna.PEDIDO_TELEFONE);
        p.origem = origemLabel;
        p.canalExterno = canalCodigo;
        // O id do marketplace e a chave que acha este pedido depois (aceite, cancelamento, avanco de
        // status). Nenhum canal manda id longo -- os do iFood sao UUID de 36 --, mas gravar sem corte
        // numa coluna com limite perderia o pedido pelo mesmo caminho do endereco.
        p.idExterno = encurtarAvisando(in.externalId(), LarguraDeColuna.PEDIDO_ID_EXTERNO,
                "id do pedido no canal", in.externalId(), canalCodigo, lojaId);
        p.observacao = in.observacao();
        if (in.taxaEntrega() != null) p.taxaEntrega = in.taxaEntrega();
        p.status = StatusPedido.RECEBIDO;
        p.criadoEm = OffsetDateTime.now();
        p.atualizadoEm = OffsetDateTime.now();

        List<PedidoItem> itens = new ArrayList<>();
        BigDecimal soma = BigDecimal.ZERO;
        // Uma consulta por PEDIDO, nao por item: o catalogo da loja tem dezenas de linhas e o pedido
        // tem poucas. Carregar aqui evita um findBy por item.
        List<Produto> catalogo = in.itens() == null || in.itens().isEmpty()
                ? List.of() : produtos.findByLojaId(lojaId);
        int semCasar = 0;
        if (in.itens() != null) for (var it : in.itens()) {
            int qtd = it.quantidade() == null || it.quantidade() < 1 ? 1 : it.quantidade();
            BigDecimal preco = it.precoUnitario() == null ? BigDecimal.ZERO : it.precoUnitario();
            BigDecimal sub = preco.multiply(BigDecimal.valueOf(qtd));
            soma = soma.add(sub);
            PedidoItem pi = new PedidoItem();
            pi.setLojaId(lojaId);
            pi.setDescricao(it.nome() == null ? "Item" : it.nome());
            pi.setQuantidade(qtd);
            // O PRECO continua vindo do marketplace, nao do nosso cadastro: quem cobrou do cliente
            // foi o iFood, com o preco que esta la. Igualar ao nosso preco faria o pedido fechar por
            // um valor que ninguem pagou.
            pi.setPrecoUnitario(preco);
            pi.setSubtotal(sub);

            // Achar o produto e o que faltava: sem ele nao havia como baixar estoque nem saber o
            // custo. Pedido de marketplace entrava como se nada tivesse saido da prateleira.
            Produto prod = resolverProduto(catalogo, it);
            if (prod == null) {
                semCasar++;
            } else {
                pi.setProdutoId(prod.id);
                // Mesma regra do balcao e do cardapio: com ficha tecnica consome os insumos e usa o
                // custo da ficha; sem ficha, baixa o proprio produto e usa o custo dele.
                BigDecimal custoFicha = insumos.consumirFicha(lojaId, prod.id, qtd);
                pi.setCustoUnitario(custoFicha != null ? custoFicha : prod.custo);
                pi.setConsumiuFicha(custoFicha != null);   // o cancelamento le isto para devolver certo
                if (custoFicha == null) {
                    // Nunca recusa: o pedido ja foi aceito no iFood e o cliente vai receber a comida.
                    // Mas o saldo negativo fica escrito.
                    avisarSeVaiFicarNegativo(lojaId, prod, qtd);
                    produtos.baixarEstoque(prod.id, lojaId, qtd);
                }
            }
            itens.add(pi);
        }
        if (semCasar > 0) {
            // Nao derruba o pedido: comida vendida tem que entrar de qualquer jeito. Mas fica escrito,
            // porque estoque errado e caro e silencioso.
            AUDITORIA.warn("Pedido {} de {} na loja {}: {} item(ns) sem produto correspondente no cadastro. "
                            + "Estoque e custo NAO foram lancados para eles. Preencha o 'Codigo no "
                            + "marketplace' do produto em Produtos, ou deixe o nome igual ao de la.",
                    in.externalId(), canalCodigo, lojaId, semCasar);
        }
        p.valorTotal = in.total() != null && in.total().signum() > 0 ? in.total() : soma;
        // O marketplace manda endereco e bairro no payload; eles iam so para o texto da observacao.
        // Em coluna, a tela de Entregas agrupa por bairro e quem entrega le o endereco deste pedido.
        // CORTA. O mesmo endereco ia cortado em 500 para cliente.endereco (varchar(500) desde a V37,
        // largada justamente por causa de endereco de marketplace) e SEM corte para uma coluna de
        // 255: o Postgres recusava o INSERT e o PEDIDO INTEIRO se perdia, com o entregador a caminho.
        // A V53 igualou as larguras, e o corte aqui e a garantia de que nem um endereco absurdo
        // derruba a venda. Ver LarguraDeColuna.
        p.enderecoEntrega = encurtarAvisando(in.endereco(), LarguraDeColuna.PEDIDO_ENDERECO,
                "endereco de entrega", in.externalId(), canalCodigo, lojaId);
        p.bairroEntrega = encurtarAvisando(in.bairro(), LarguraDeColuna.PEDIDO_BAIRRO,
                "bairro", in.externalId(), canalCodigo, lojaId);
        // Retirada vinha so como o texto "RETIRADA NO BALCAO" que o normalizador escreve na observacao.
        p.tipoEntrega = p.ehRetirada() ? "RETIRADA" : "ENTREGA";
        Pedido salvo = repo.save(p);
        itens.forEach(it -> it.setPedidoId(salvo.id));
        itemRepo.saveAll(itens);
        // Sem telefone de verdade o cliente nunca usaria o cashback aqui: registra a compra, sem saldo.
        fidelidade.registrar(lojaId, salvo.clienteId, p.valorTotal, BigDecimal.ZERO, telReal != null);
        return salvo;
    }

    /**
     * Marca que o marketplace aceitou o aceite automatico deste pedido.
     *
     * <p>UPDATE direcionado, nao {@code save} da entidade: o poller pode estar com uma copia lida ha
     * segundos, e gravar a entidade inteira sobrescreveria o que o lojista mexeu nesse meio-tempo.</p>
     */
    @Transactional
    public void marcarAceiteEnviado(Long lojaId, Long pedidoId) {
        repo.marcarAceiteEnviado(lojaId, pedidoId);
    }

    /** Encontra o cliente pelo telefone (ou cria) — base do CRM com pedidos de marketplace. */
    /** Pedido que veio de marketplace, pelo id que o canal deu a ele. Nao depende de login. */
    public java.util.Optional<Pedido> buscarExterno(Long lojaId, String canalCodigo, String idExterno) {
        if (idExterno == null || idExterno.isBlank()) return java.util.Optional.empty();
        return repo.findFirstByLojaIdAndCanalExternoAndIdExterno(lojaId, canalCodigo, idExterno);
    }

    /**
     * Cancelamento que CHEGOU do marketplace — o cliente cancelou no app, ou o canal confirmou um
     * cancelamento nosso. Nao passa pelo alterarStatus por dois motivos: aquele exige usuario logado
     * (o poller roda sem ninguem) e empurra o status de volta ao canal, o que mandaria cancelar de
     * novo um pedido que o proprio canal acabou de cancelar.
     *
     * <p>Devolve false quando nao ha o que fazer (pedido desconhecido ou ja encerrado). E idempotente
     * de proposito: o mesmo cancelamento pode chegar pelo polling e pelo webhook.</p>
     */
    @org.springframework.transaction.annotation.Transactional
    public boolean cancelarPorMarketplace(Long lojaId, String canalCodigo, String idExterno, String motivo) {
        var achado = buscarExterno(lojaId, canalCodigo, idExterno);
        if (achado.isEmpty()) return false;
        Pedido p = achado.get();
        if (p.status == StatusPedido.CANCELADO || p.status == StatusPedido.ENTREGUE) return false;
        StatusPedido anterior = p.status;
        p.status = StatusPedido.CANCELADO;
        p.canceladoEm = OffsetDateTime.now();
        p.atualizadoEm = OffsetDateTime.now();
        p.motivoCancelamento = motivo == null || motivo.isBlank() ? "Cancelado pelo marketplace" : motivo;
        Pedido salvo = repo.save(p);
        // Mesma regra do cancelamento pelo painel. true: pedido de marketplace nunca espera PIX, entao
        // a venda ja tinha entrado no historico do cliente quando nasceu.
        devolucao.devolver(salvo, anterior, true);
        LogStatus registro = new LogStatus();
        registro.setLojaId(salvo.lojaId);
        registro.setPedidoId(salvo.id);
        registro.setStatusAnterior(anterior == null ? null : anterior.name());
        registro.setStatusNovo(StatusPedido.CANCELADO.name());
        registro.setUsuarioId(null); // quem cancelou foi o marketplace, nao um usuario do painel (coluna nula na V2)
        logs.save(registro);
        return true;
    }

    /**
     * O entregador do marketplace andou: o pedido avanca aqui, sem devolver o status para la.
     *
     * <p>Quando a entrega e do marketplace, quem sabe onde o motoboy esta e ele. O Bora so e avisado
     * — e nao pode responder contando a mesma novidade de volta, que e o que {@code alterarStatus}
     * faria. Tambem nao exige usuario logado: quem chama e o webhook, sem ninguem na tela.</p>
     *
     * <p>Nao recua status nem mexe em pedido ja encerrado: o aviso pode chegar fora de ordem ou
     * repetido, e reabrir um pedido entregue seria pior que ignorar.</p>
     *
     * @return true quando o pedido realmente mudou
     */
    @org.springframework.transaction.annotation.Transactional
    public boolean avancarPorMarketplace(Long lojaId, String canalCodigo, String idExterno,
                                         StatusPedido novo) {
        var achado = buscarExterno(lojaId, canalCodigo, idExterno);
        if (achado.isEmpty()) return false;
        Pedido p = achado.get();
        if (p.status == StatusPedido.CANCELADO || p.status == StatusPedido.ENTREGUE) return false;
        if (p.status == novo) return false;
        StatusPedido anterior = p.status;
        p.status = novo;
        p.atualizadoEm = OffsetDateTime.now();
        if (novo == StatusPedido.ENTREGUE) p.entregueEm = OffsetDateTime.now();
        Pedido salvo = repo.save(p);
        LogStatus registro = new LogStatus();
        registro.setLojaId(salvo.lojaId);
        registro.setPedidoId(salvo.id);
        registro.setStatusAnterior(anterior == null ? null : anterior.name());
        registro.setStatusNovo(novo.name());
        registro.setUsuarioId(null); // quem mexeu foi o marketplace, nao um usuario do painel
        logs.save(registro);
        notifCliente.notificarFase(salvo, novo); // o cliente final merece saber que saiu/chegou
        AUDITORIA.info("[{}] loja {}: pedido {} avancou para {} pelo aviso do entregador",
                canalCodigo, lojaId, idExterno, novo);
        return true;
    }

    /**
     * Encontra (ou cria) o cliente do pedido de marketplace. Ordem: o id que o marketplace dá ao cliente;
     * depois o telefone, só se for de uma pessoa. O iFood manda a CENTRAL dele (0800 + localizador) no
     * lugar do celular — casar por ela juntava todos os clientes do iFood da loja num cadastro só.
     */
    private Long upsertCliente(Long lojaId, String canalCodigo, br.com.bora.dto.InboundOrder in, String telReal) {
        String ext = in.clienteIdExterno();
        if (ext != null) {
            var doMarketplace = clientes.findFirstByLojaIdAndCanalExternoAndIdExterno(lojaId, canalCodigo, ext);
            if (doMarketplace.isPresent()) return doMarketplace.get().id;
        }
        if (telReal != null) {
            var existente = clientes.findFirstByLojaIdAndTelefone(lojaId, telReal);
            if (existente.isPresent()) {
                Cliente c = existente.get();
                if (ext != null && c.idExterno == null) { c.canalExterno = canalCodigo; c.idExterno = ext; clientes.save(c); }
                return c.id;
            }
        }
        if (telReal == null && ext == null && (in.clienteNome() == null || in.clienteNome().isBlank())) return null;
        Cliente c = new Cliente();
        c.lojaId = lojaId;
        // Texto do marketplace não pode recusar o pedido por tamanho: corta no limite de cada coluna.
        c.nome = cortar(in.clienteNome() == null || in.clienteNome().isBlank() ? "Cliente marketplace" : in.clienteNome(),
                LarguraDeColuna.CLIENTE_NOME);
        c.telefone = cortar(telReal, LarguraDeColuna.CLIENTE_TELEFONE);
        c.endereco = cortar(in.endereco(), LarguraDeColuna.CLIENTE_ENDERECO);
        c.bairro = cortar(in.bairro(), LarguraDeColuna.CLIENTE_BAIRRO);
        c.canalExterno = ext == null ? null : canalCodigo;
        c.idExterno = cortar(ext, LarguraDeColuna.CLIENTE_ID_EXTERNO);
        return clientes.save(c).id;
    }

    private static String cortar(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }

    /**
     * Corta o que vem de fora para caber na coluna, e AVISA quando cortou.
     *
     * <p>Vazio vira nulo (campo em branco do marketplace nao e endereco em branco: e ausencia).</p>
     *
     * <p><b>Por que cortar e nao recusar:</b> o Postgres recusa o INSERT quando o texto nao cabe, e
     * nessa hora o pedido INTEIRO se perde -- nao o campo. A venda ja aconteceu do outro lado e o
     * entregador esta a caminho. Endereco cortado em 500 caracteres ainda da para entregar; pedido
     * que nao existe no painel, nao.</p>
     *
     * <p>E por que AVISAR: cortar perde informacao. Se o fim de um endereco for cortado algum dia,
     * tem que haver uma linha no log dizendo qual pedido, em vez de o entregador descobrir na rua.</p>
     */
    private String encurtarAvisando(String valor, int max, String campo, String idExterno,
                                    String canal, Long lojaId) {
        if (valor == null || valor.isBlank()) return null;
        String limpo = valor.trim();
        if (limpo.length() <= max) return limpo;
        AUDITORIA.warn("Pedido {} de {} na loja {}: o {} tinha {} caracteres e foi CORTADO em {}. "
                        + "O pedido entrou; confira o endereco com o cliente antes de despachar. "
                        + "Texto completo: {}",
                idExterno, canal, lojaId, campo, limpo.length(), max, limpo);
        return limpo.substring(0, max);
    }

    /** Telefone de pessoa, ou null para vazio e para central (0800/0300/0500/0900 ou curto demais). */
    static String telefoneDePessoa(String tel) {
        if (tel == null || tel.isBlank()) return null;
        String d = tel.replaceAll("\\D", "");
        if (d.length() < 10) return null;
        for (String central : new String[] {"0800", "0300", "0500", "0900"}) {
            if (d.startsWith(central)) return null;
        }
        return tel;
    }

    /**
     * Escolhe ou troca o motoboy do pedido.
     *
     * <h2>Item 6.5 da varredura</h2>
     * <p>Nao havia regra nenhuma: qualquer OPERADOR trocava o motoboy de qualquer pedido, inclusive
     * de um ja entregue, com texto livre e sem deixar rastro. Isso e dinheiro de outra pessoa — o
     * acerto paga por entregador, entao trocar o nome move a taxa de um motoboy para outro.</p>
     *
     * <h2>O que entra, e por que</h2>
     * <p><b>Pedido ja ACERTADO nao muda nunca.</b> Se {@code acertoId} esta preenchido, aquela entrega
     * ja foi paga a alguem, e o recibo esta impresso. Trocar o nome depois faria o historico do acerto
     * mentir sobre quem recebeu — e nao existe conserto para isso depois.</p>
     *
     * <p><b>Pedido ja ENTREGUE exige gerente.</b> Durante a entrega, trocar o motoboy e operacao de
     * balcao (o primeiro nao pode ir, vai o segundo). Depois de entregue, a unica razao de mexer e
     * corrigir para quem vai receber — decisao de quem responde pelo caixa. Antes da entrega qualquer
     * operador continua trocando, que e o uso normal.</p>
     *
     * <p>E fica <b>no log de auditoria</b>, sempre: era o unico campo de dinheiro que mudava sem
     * deixar rastro.</p>
     */
    public Pedido definirEntregador(Long id, String entregador) {
        Pedido p = buscarDaLoja(id);
        if (p.acertoId != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este pedido já entrou num acerto fechado, então o entregador não pode mais "
                            + "mudar — o acerto registrou quem recebeu por esta entrega.");
        }
        if (p.status == StatusPedido.ENTREGUE) {
            ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        }
        String anterior = p.entregador;
        p.entregador = (entregador == null || entregador.isBlank()) ? null : entregador.trim();
        p.atualizadoEm = OffsetDateTime.now();
        AUDITORIA.info("AUDITORIA entrega: usuario {} mudou o entregador do pedido {} da loja {} de "
                        + "\"{}\" para \"{}\" (status {})",
                ctx.atual() == null ? null : ctx.atual().userId(), p.id, p.lojaId,
                anterior, p.entregador, p.status);
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
        exigirQueAvance(p, status);
        if (status == StatusPedido.SAIU_PARA_ENTREGA && p.ehRetirada()) {
            // Item 6.9: o cliente esta no balcao esperando, e recebia "saiu para entrega! Ja ja chega
            // ai" no WhatsApp. A KDS oferecia "Despachar" para retirada porque o card nao sabia o
            // tipo -- agora sabe (item 6.2), e aqui o servidor tambem recusa.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Este pedido é retirada no balcão: não há o que despachar. Marque como ENTREGUE "
                            + "quando o cliente retirar.");
        }
        if (status == StatusPedido.CANCELADO && (motivo == null || motivo.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Motivo do cancelamento é obrigatório"); // RN05
        }
        // Pedido de marketplace: cancela lá primeiro. Se o marketplace recusar, o erro volta para a tela
        // e o pedido NÃO é cancelado aqui.
        if (status == StatusPedido.CANCELADO) integracoes.cancelarNoMarketplace(p, motivo);
        StatusPedido anterior = p.status;
        p.status = status;
        p.atualizadoEm = OffsetDateTime.now();
        if (status == StatusPedido.ENTREGUE) p.entregueEm = OffsetDateTime.now();
        if (status == StatusPedido.CANCELADO) {
            p.canceladoEm = OffsetDateTime.now();
            p.motivoCancelamento = motivo;
        }
        // Gravar o pedido, devolver o estoque e registrar o log sao UMA coisa so. Sem isto eram tres
        // gravacoes soltas: se a devolucao estourasse, o pedido ja estava CANCELADO, o lojista via
        // erro 500, tentar de novo dava "estado final" e o estoque nunca voltava.
        // As chamadas externas (marketplace, WhatsApp) ficam FORA, antes e depois.
        final StatusPedido anteriorFinal = anterior;
        // PIX gerado e nao pago nunca entrou no historico do cliente nem gerou cashback: houve apenas o
        // debito do resgate. Lido ANTES da gravacao, porque e o estado em que a venda nasceu que decide
        // o que existe para desfazer.
        final boolean vendaJaContabilizada = !p.pagamentoPendente();
        Pedido salvo = tx.execute(st -> {
            Pedido gravado = repo.save(p);
            // Venda que nao aconteceu devolve o que consumiu: estoque, insumo e cashback. A regra
            // depende do estagio em que o pedido estava: ver EstornoDaVendaService.
            if (status == StatusPedido.CANCELADO) devolucao.devolver(gravado, anteriorFinal, vendaJaContabilizada);
            registrarLog(gravado, anteriorFinal, status); // RN07
            return gravado;
        });
        integracoes.notificarStatus(salvo, status.name()); // sincroniza status com o marketplace (se conectado)
        notifCliente.notificarFase(salvo, status); // Operação Assistida: avisa o cliente no WhatsApp
        return salvo;
    }

    /**
     * A ordem do fluxo do pedido. Posicao maior = mais adiante.
     *
     * <p>{@code CANCELADO} fica de fora de proposito: cancelar nao e "avancar", e pode acontecer de
     * qualquer estado nao-final.</p>
     */
    private static int posicaoNoFluxo(StatusPedido s) {
        return switch (s) {
            case RECEBIDO -> 0;
            case CONFIRMADO -> 1;
            case EM_PREPARO -> 2;
            case PRONTO -> 3;
            case SAIU_PARA_ENTREGA -> 4;
            case ENTREGUE -> 5;
            case CANCELADO -> -1;
        };
    }

    /**
     * O status novo tem que estar ADIANTE do atual. Cancelar e a excecao.
     *
     * <h2>Item 6.6 da varredura</h2>
     * <p>O {@code alterarStatus} so barrava estado final. Aceitava qualquer pulo e <b>qualquer
     * volta</b>: de SAIU_PARA_ENTREGA para EM_PREPARO, por exemplo. Uma aba antiga do painel, aberta
     * antes de o pedido andar, re-clicando o botao de "preparar" mandava o pedido para tras — e o
     * cliente recebia no WhatsApp <b>"seu pedido esta sendo preparado"</b> depois de o motoboy ter
     * saido com ele.</p>
     *
     * <p><b>Pulo para frente continua valendo</b>, e isso e de proposito: a propria KDS pula
     * CONFIRMADO (vai de RECEBIDO direto para EM_PREPARO), e loja que nao usa confirmacao trabalha
     * assim. O que nao pode e recuar nem repetir o mesmo status, que reenviaria o aviso ao cliente.</p>
     */
    private static void exigirQueAvance(Pedido p, StatusPedido novo) {
        if (novo == StatusPedido.CANCELADO) return;
        int de = posicaoNoFluxo(p.status);
        int para = posicaoNoFluxo(novo);
        if (para > de) return;
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                para == de
                        ? "Este pedido já está em " + novo + "."
                        : "Não dá para voltar de " + p.status + " para " + novo + ". O pedido já "
                                + "passou dessa etapa, e o cliente já foi avisado — se precisar "
                                + "desfazer, cancele o pedido.");
    }

    /**
     * Escreve no log quando a venda vai levar o estoque a negativo.
     *
     * <p>Nos canais em que a venda NAO pode ser recusada -- balcao, porque o operador esta com o
     * produto na mao, e marketplace, porque o pedido ja foi aceito do outro lado -- o saldo pode ir a
     * negativo. Isso nem sempre e defeito: quase sempre quer dizer que a contagem esta
     * desatualizada, e e informacao util. O defeito era ir a negativo <b>calado</b>.</p>
     *
     * <p>O saldo lido aqui e o de antes da baixa, e e so para o aviso -- a conta de verdade e do
     * banco. Entre esta leitura e a baixa alguem pode vender, entao o numero do aviso pode estar um
     * ou dois fora. Nao use este valor para decidir nada.</p>
     *
     * <p>Produto que nao controla estoque (saldo nulo) nao gera aviso: foi o lojista que escolheu nao
     * controlar. Hoje e o caso de todos os 123 produtos da producao.</p>
     */
    private void avisarSeVaiFicarNegativo(Long lojaId, Produto prod, int qtd) {
        if (prod.estoque == null || prod.estoque >= qtd) return;
        AUDITORIA.warn("Loja {}: venda de {} un de \"{}\" com apenas {} em estoque -- o saldo vai "
                        + "ficar negativo. A venda PASSOU de proposito (o produto esta na mao de quem "
                        + "vendeu); confira a contagem em Estoque.",
                lojaId, qtd, prod.nome, prod.estoque);
    }

    /**
     * Qual produto da loja foi vendido neste item do marketplace.
     *
     * <h2>Por que nao da para casar pelo nome que vem pronto</h2>
     * <p>O normalizador monta o campo {@code nome} para a TELA: ele junta complemento e observacao
     * ("Acai 500ml (+ Granola) -- obs: sem cebola"). Esse texto nao casa com {@code produto.nome} em
     * nenhum item com adicional, que numa acaiteria e quase todo item.</p>
     *
     * <h2>A ordem</h2>
     * <ol>
     *   <li><b>Codigo do marketplace</b> ({@code externalCode} do payload, gravado em
     *       {@code produto.codigo_externo}). E o unico jeito confiavel: foi o proprio lojista que
     *       cadastrou o mesmo codigo dos dois lados.</li>
     *   <li><b>Nome limpo</b>, normalizado: sem acento, sem espaco sobrando, caixa ignorada. Casa
     *       quando o lojista escreveu o produto igual la e aqui, que e o caso comum.</li>
     * </ol>
     *
     * <p>Nao achou: devolve {@code null} e o item entra como entrava antes -- sem baixa e sem custo.
     * <b>Nunca</b> derruba o pedido por isso, e <b>nunca</b> chuta por aproximacao: baixar o estoque
     * do produto errado e pior que nao baixar nenhum, porque ninguem percebe.</p>
     *
     * <p>Nome repetido no cadastro tambem devolve {@code null} -- com dois candidatos, escolher um e
     * adivinhar.</p>
     */
    private Produto resolverProduto(List<Produto> catalogo, br.com.bora.dto.InboundOrder.InboundItem it) {
        if (catalogo.isEmpty()) return null;

        String codigo = it.codigoExterno() == null ? null : it.codigoExterno().trim();
        if (codigo != null && !codigo.isEmpty()) {
            for (Produto p : catalogo) {
                if (p.codigoExterno != null && codigo.equalsIgnoreCase(p.codigoExterno.trim())) return p;
            }
        }

        String alvo = chaveDoNome(it.nomeLimpo());
        if (alvo == null) return null;
        Produto achado = null;
        for (Produto p : catalogo) {
            if (alvo.equals(chaveDoNome(p.nome))) {
                if (achado != null) return null;   // dois com o mesmo nome: nao da para escolher
                achado = p;
            }
        }
        return achado;
    }

    /** Nome comparavel: sem acento, sem espaco duplicado, em minusculas. */
    private static String chaveDoNome(String nome) {
        if (nome == null || nome.isBlank()) return null;
        String semAcento = java.text.Normalizer.normalize(nome.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return semAcento.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Exclusao definitiva — para pedido de teste ou lancado errado; pedido de verdade que nao vai sair
     * se cancela. Leva junto itens e historico (nao ha chave estrangeira: antes ficavam orfaos) e
     * desconta o contador do card do marketplace, que so subia.
     *
     * <h2>O defeito que isto fecha (medido em 20/10/2026)</h2>
     * <p>Excluir apagava pedido, itens e historico e <b>nao devolvia nada</b>: nem estoque, nem
     * insumo, nem cashback. E este e justamente o botao oferecido para "pedido de teste ou lancado
     * errado", o caso em que tudo tem que voltar. O cliente perdia o saldo que gastou numa venda que
     * o lojista acabou de declarar que nunca existiu, e o estoque ficava baixado para sempre.</p>
     *
     * <p>Pior: os itens eram apagados <b>primeiro</b>. O estorno le os itens para saber o que
     * devolver ({@code PedidoItem.consumiuFicha} diz se foi produto ou insumo), entao depois daquela
     * linha nao havia mais como recalcular nem a mao.</p>
     *
     * <h2>As duas regras que nao sao obvias</h2>
     * <p><b>Devolver ANTES de apagar.</b> O {@link EstornoDaVendaService#devolver} consulta os itens
     * no banco; depois do {@code delete} eles nao existem.</p>
     *
     * <p><b>Pedido JA CANCELADO nao devolve de novo.</b> O estorno rodou no cancelamento. Excluir um
     * pedido cancelado — que e o caminho natural: cancela o teste, depois apaga — devolveria estoque e
     * cashback <b>em dobro</b>. O conserto ingenuo (chamar o estorno sempre) troca um defeito que
     * tira do cliente por um que da de graca, e esse ninguem reclama, entao ninguem descobre.</p>
     *
     * <p>O estagio decide o que volta, igual ao cancelamento: pedido ENTREGUE devolve so o cashback,
     * porque a comida foi embora e o ingrediente acabou. Quem sabe disso e o
     * {@code EstornoDaVendaService}; aqui so passamos o status que o pedido tinha.</p>
     */
    @Transactional
    public void excluir(Long id) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA"); // RN06
        Pedido p = buscarDaLoja(id);

        // Lido ANTES de qualquer coisa: e o estado em que a venda nasceu que decide o que existe para
        // desfazer. PIX gerado e nao pago nunca entrou no historico do cliente nem gerou cashback --
        // houve apenas o debito do resgate.
        boolean vendaJaContabilizada = !p.pagamentoPendente();
        boolean jaEstornado = p.status == StatusPedido.CANCELADO;

        int devolvidos = jaEstornado ? 0 : devolucao.devolver(p, p.status, vendaJaContabilizada);

        itemRepo.deleteByLojaIdAndPedidoId(p.lojaId, p.id);
        logs.deleteByLojaIdAndPedidoId(p.lojaId, p.id);
        if (p.canalExterno != null) integracoes.descontarRecebido(p.lojaId, p.canalExterno);
        // Pelo id, nao pela entidade: o estorno acima roda consultas que limpam o contexto do
        // Hibernate e o objeto fica desanexado. Ver PedidoRepository.apagarDaLoja.
        repo.apagarDaLoja(p.id, p.lojaId);

        AUDITORIA.warn("AUDITORIA pedido: usuario {} EXCLUIU o pedido {} (codigo {}, origem {}, "
                        + "status {}, R$ {}) da loja {}. Devolucao: {}",
                ctx.atual().userId(), p.id, p.codigo, p.origem, p.status, p.valorTotal, p.lojaId,
                jaEstornado
                        ? "nada, o pedido ja estava CANCELADO e o estorno rodou la"
                        : devolvidos + " item(ns) com estoque devolvido" + (vendaJaContabilizada
                                ? ", e o cashback da venda foi desfeito"
                                : "; a venda nunca foi paga, so o resgate de cashback voltou"));
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

    /**
     * Soma o custo dos adicionais ao custo do item, preservando o "nao sei".
     *
     * <p>{@code custoUnitario} nulo significa <b>custo nao cadastrado</b>, nao zero -- e o relatorio
     * de desempenho trata nulo como zero na hora de somar, o que e outra coisa. Entao: sem custo de
     * adicional, o custo do item fica exatamente como estava, inclusive nulo. Com custo de adicional,
     * ha um numero de verdade para somar, e a base desconhecida entra como zero porque e o melhor
     * que existe -- e o custo do adicional e informacao real que nao pode ser jogada fora.</p>
     */
    public static BigDecimal somarCustoDoAdicional(BigDecimal custoBase, BigDecimal custoAdicional) {
        if (custoAdicional == null || custoAdicional.signum() == 0) return custoBase;
        return (custoBase == null ? BigDecimal.ZERO : custoBase).add(custoAdicional);
    }
}
