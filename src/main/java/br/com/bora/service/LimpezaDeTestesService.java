package br.com.bora.service;

import br.com.bora.entity.Cliente;
import br.com.bora.entity.Loja;
import br.com.bora.repository.AcertoEntregadorRepository;
import br.com.bora.repository.ClienteRepository;
import br.com.bora.repository.LogStatusRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apaga a movimentação de teste de uma loja — e <b>só</b> a movimentação.
 *
 * <h2>Por que isto existe</h2>
 * <p>Quem vende o sistema testa dentro da conta do cliente: lança pedido de mentira, muda status,
 * fecha caixa. Esse lixo fica no Relatório da loja para sempre, misturado com a venda de verdade, e
 * o lojista passa a olhar um faturamento que nunca existiu. Antes disto, a única saída era alguém
 * entrar no banco por ssh e escrever {@code delete} à mão — e escrever {@code delete} à mão na
 * produção de um cliente pagante é como esse tipo de coisa acaba mal.</p>
 *
 * <h2>O que NUNCA é apagado</h2>
 * <p>Produto, insumo, ficha técnica, complemento, cupom, promoção, entregador, configuração da loja,
 * integração com marketplace, subconta de PIX, assinatura, pagamento de mensalidade e usuário.
 * Nenhum deles é movimentação: são o cadastro que faz a loja funcionar. Apagar o cardápio junto
 * deixaria a loja sem nada para vender — e é o erro que esta classe foi escrita para não permitir.</p>
 *
 * <h2>O estoque VOLTA (26/10/2026)</h2>
 * <p>Pedido de teste baixou estoque: produto sem ficha tirou do proprio saldo, produto com ficha
 * consumiu insumo. Apagar o pedido nao desfazia nada disso — o lojista ficava com o estoque curto
 * <b>e sem o historico que explicaria</b>, porque a contagem some junto com a prova. Agora a limpeza
 * devolve, pelo mesmo caminho do cancelamento ({@link EstornoDaVendaService}).</p>
 *
 * <p>Pedido que ja estava CANCELADO fica de fora: o cancelamento dele ja devolveu o que havia. A
 * previa e o resultado dizem quantos ficaram de fora, para quem limpa saber o que conferir.</p>
 *
 * <h2>Quem pode</h2>
 * <p>Só o ADMINISTRADOR_BORA. O administrador da loja NÃO tem este botão de propósito: é um gatilho
 * sem volta, e o estrago de um clique errado é o histórico de vendas do próprio negócio dele.</p>
 *
 * <h2>A trava do nome</h2>
 * <p>Quem chama precisa digitar o nome exato da loja. Parece burocracia e não é: a tela da plataforma
 * lista todas as lojas lado a lado, e a diferença entre limpar a "Zirá Centro" e a "Zirá Zona Norte"
 * é uma linha de distância no mouse.</p>
 */
@Service
public class LimpezaDeTestesService {

    /** Vai para o log com nível WARN de propósito: isto tem que aparecer em qualquer busca. */
    private static final Logger AUDITORIA = LoggerFactory.getLogger("AUDITORIA");

    private final LojaRepository lojas;
    private final PedidoRepository pedidos;
    private final PedidoItemRepository itens;
    private final LogStatusRepository logs;
    private final ClienteRepository clientes;
    private final AcertoEntregadorRepository acertos;
    private final ProdutoRepository produtos;
    private final EstornoDaVendaService estorno;
    private final AuthContext ctx;

    public LimpezaDeTestesService(LojaRepository lojas, PedidoRepository pedidos, PedidoItemRepository itens,
                                  LogStatusRepository logs, ClienteRepository clientes,
                                  AcertoEntregadorRepository acertos, ProdutoRepository produtos,
                                  EstornoDaVendaService estorno, AuthContext ctx) {
        this.lojas = lojas;
        this.pedidos = pedidos;
        this.itens = itens;
        this.logs = logs;
        this.clientes = clientes;
        this.acertos = acertos;
        this.produtos = produtos;
        this.estorno = estorno;
        this.ctx = ctx;
    }

    /** Quanto existe hoje. A tela mostra estes números ANTES de pedir a confirmação. */
    @Transactional(readOnly = true)
    public Map<String, Object> previa(Long lojaId) {
        ctx.requireAdminBora();
        Loja loja = buscar(lojaId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lojaId", lojaId);
        m.put("loja", loja.getNome());
        m.put("pedidos", pedidos.countByLojaId(lojaId));
        m.put("itens", itens.countByLojaId(lojaId));
        m.put("historico", logs.countByLojaId(lojaId));
        m.put("acertos", acertos.countByLojaId(lojaId));
        m.put("clientes", clientes.countByLojaId(lojaId));
        m.put("clientesComCashback", clientes.countByLojaIdAndCashbackGreaterThan(lojaId, BigDecimal.ZERO));
        // O que fica. Está aqui para a tela poder MOSTRAR o que sobrevive, em vez de só prometer.
        m.put("produtosQueFicam", produtosDaLoja(lojaId));
        // Quantos pedidos terao o estoque devolvido, e quantos ficam de fora por ja estarem
        // cancelados. A tela mostra isto ANTES da confirmacao: estoque e contagem fisica, e quem
        // limpa precisa saber o que vai mudar na prateleira.
        long cancelados = pedidos.findByLojaIdOrderByCriadoEmDesc(lojaId).stream()
                .filter(x -> x.status == br.com.bora.entity.StatusPedido.CANCELADO).count();
        m.put("pedidosQueDevolvemEstoque", pedidos.countByLojaId(lojaId) - cancelados);
        m.put("pedidosJaCanceladosQueNaoDevolvem", cancelados);
        return m;
    }

    /**
     * Apaga a movimentação. Devolve quantas linhas saíram de cada lugar.
     *
     * @param zerarClientes zera cashback, total gasto e contagem de pedidos de cada cliente. Fica
     *                      DESLIGADO por padrão porque cashback é dinheiro que o cliente final já
     *                      tem a receber — apagar o pedido não devolve o açaí que ele comprou.
     */
    @Transactional
    public Map<String, Object> limpar(Long lojaId, String confirmacao, boolean zerarClientes) {
        ctx.requireAdminBora();
        Loja loja = buscar(lojaId);
        String nome = loja.getNome() == null ? "" : loja.getNome().trim();
        // Loja sem nome casaria com confirmacao vazia (""=="") e seria apagada sem ninguem confirmar
        // nada. Foi o teste `lojaSEMnomeNaoViraPassePorNomeVAZIO` que achou isto.
        if (nome.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Esta loja está sem nome no cadastro, e é o nome que serve de confirmação. "
                            + "Dê um nome a ela antes de limpar.");
        }
        if (confirmacao == null || !nome.equalsIgnoreCase(confirmacao.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Para limpar, digite o nome exato da loja: " + nome);
        }

        long qtdPedidos = pedidos.countByLojaId(lojaId);
        long qtdItens = itens.countByLojaId(lojaId);
        long qtdLogs = logs.countByLojaId(lojaId);
        long qtdAcertos = acertos.countByLojaId(lojaId);

        // O ESTOQUE VOLTA ANTES DE APAGAR, porque depois nao ha mais item para saber o que devolver.
        //
        // Pedido ja CANCELADO fica de fora: o cancelamento dele ja devolveu o que havia para
        // devolver. Devolver de novo somaria estoque que nunca saiu -- e estoque inventado e
        // silencioso, o lojista nunca descobre de onde veio.
        int itensDevolvidos = 0;
        int pedidosDevolvidos = 0;
        int canceladosDeFora = 0;
        for (br.com.bora.entity.Pedido pedidoDeTeste : pedidos.findByLojaIdOrderByCriadoEmDesc(lojaId)) {
            if (pedidoDeTeste.status == br.com.bora.entity.StatusPedido.CANCELADO) {
                canceladosDeFora++;
                continue;
            }
            int devolvidos = estorno.devolverSoOestoque(pedidoDeTeste);
            if (devolvidos > 0) {
                itensDevolvidos += devolvidos;
                pedidosDevolvidos++;
            }
        }

        // Ordem importa: filho antes do pai. O pedido é o pai de item e de histórico, e o acerto
        // referencia pedido pelo `acerto_id` que está DENTRO do pedido -- some junto.
        itens.deleteByLojaId(lojaId);
        logs.deleteByLojaId(lojaId);
        acertos.deleteByLojaId(lojaId);
        pedidos.deleteByLojaId(lojaId);

        int clientesZerados = 0;
        if (zerarClientes) {
            List<Cliente> todos = clientes.findByLojaId(lojaId);
            for (Cliente c : todos) {
                c.totalGasto = BigDecimal.ZERO;
                c.qtdPedidos = 0;
                c.cashback = BigDecimal.ZERO;
                c.ultimoPedido = null;
            }
            clientes.saveAll(todos);
            clientesZerados = todos.size();
        }

        AUDITORIA.warn("AUDITORIA limpeza: {} ({}) apagou a movimentacao da loja {} \"{}\" -- "
                        + "{} pedidos, {} itens, {} linhas de historico, {} acertos; clientes zerados: {}. "
                        + "Estoque devolvido em {} item(ns) de {} pedido(s); {} pedido(s) ja cancelado(s) "
                        + "ficaram de fora porque o cancelamento deles ja devolveu. "
                        + "Cardapio, integracoes, assinatura e usuarios NAO foram tocados.",
                ctx.atual().userId(), ctx.atual().email(), lojaId, nome,
                qtdPedidos, qtdItens, qtdLogs, qtdAcertos, clientesZerados,
                itensDevolvidos, pedidosDevolvidos, canceladosDeFora);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("loja", nome);
        r.put("pedidos", qtdPedidos);
        r.put("itens", qtdItens);
        r.put("historico", qtdLogs);
        r.put("acertos", qtdAcertos);
        r.put("clientesZerados", clientesZerados);
        r.put("estoqueDevolvidoEmItens", itensDevolvidos);
        r.put("estoqueDevolvidoEmPedidos", pedidosDevolvidos);
        r.put("pedidosJaCanceladosSemDevolucao", canceladosDeFora);
        r.put("produtosQueFicaram", produtosDaLoja(lojaId));
        return r;
    }

    /** Quantos produtos a loja tem. A tela mostra isto para provar que o cardápio ficou de pé. */
    private long produtosDaLoja(Long lojaId) {
        return produtos.countByLojaId(lojaId);
    }

    private Loja buscar(Long lojaId) {
        return lojas.findById(lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
    }
}
