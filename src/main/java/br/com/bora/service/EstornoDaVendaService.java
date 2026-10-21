package br.com.bora.service;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.Produto;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.ProdutoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Pedido cancelado devolve o que consumiu: estoque, insumo e cashback.
 *
 * <p>Este e o ponto unico por onde passam os QUATRO caminhos de cancelamento (PIX que falhou, PIX
 * expirado, cancelamento do marketplace, cancelamento do lojista no painel). O cashback entrou aqui
 * em 09/10/2026 exatamente por isso: ele estava sendo devolvido em dois dos quatro caminhos, na mao,
 * e os outros dois esqueciam. Regra nova de cancelamento entra aqui, nao num quinto lugar.</p>
 *
 * <p>Nascer um pedido baixa estoque por um de dois caminhos: produto COM ficha tecnica consome os
 * insumos da ficha; produto SEM ficha baixa o estoque dele mesmo. Cancelar nao desfazia nem um nem
 * outro — em nenhum dos quatro caminhos de cancelamento (PIX que falhou, PIX expirado, cancelamento
 * do marketplace, cancelamento do lojista no painel). Com o PIX expirando sozinho em 30 minutos isso
 * virou rotina: todo checkout abandonado comia estoque e CMV de uma venda que nunca existiu.</p>
 *
 * <h2>A regra depende do estagio, porque a realidade depende</h2>
 * <p><b>Insumos</b> voltam so se o pedido ainda nao entrou em preparo ({@code RECEBIDO} ou
 * {@code CONFIRMADO}). Se a cozinha ja comecou, o ingrediente acabou de verdade: devolver inflaria o
 * estoque com comida que foi para o lixo.</p>
 *
 * <p><b>Estoque de produto pronto</b> (refrigerante, pote fechado) volta sempre, exceto se o pedido
 * ja saiu para entrega — ai a unidade nao esta mais na loja. Esse item nao foi preparado, so tirado
 * da prateleira, e cancelamento antes de sair significa que ele volta para a prateleira.</p>
 *
 * <p>Qual dos dois caminhos cada item usou vem gravado no proprio item
 * ({@link PedidoItem#getConsumiuFicha()}), nao deduzido da ficha de hoje: quem mexeu na ficha depois
 * do pedido nao faz o cancelamento devolver a coisa errada. Item anterior a V45 nao tem a marca, e
 * so nesse caso caimos na ficha atual, que e a melhor informacao que existe para o passado.</p>
 */
@Slf4j
@Service
public class EstornoDaVendaService {

    private final PedidoItemRepository itens;
    private final ProdutoRepository produtos;
    private final InsumoService insumos;
    private final FidelidadeService fidelidade;

    public EstornoDaVendaService(PedidoItemRepository itens, ProdutoRepository produtos,
                                     InsumoService insumos, FidelidadeService fidelidade) {
        this.itens = itens;
        this.produtos = produtos;
        this.insumos = insumos;
        this.fidelidade = fidelidade;
    }

    /** O pedido ja tinha entrado em producao? Depois disso o ingrediente nao volta. */
    private static boolean entrouEmPreparo(StatusPedido statusAntes) {
        return statusAntes == StatusPedido.EM_PREPARO || statusAntes == StatusPedido.PRONTO
                || statusAntes == StatusPedido.SAIU_PARA_ENTREGA || statusAntes == StatusPedido.ENTREGUE;
    }

    /** O pedido ja tinha saido da loja? Depois disso a unidade nao esta mais na prateleira. */
    private static boolean jaSaiuDaLoja(StatusPedido statusAntes) {
        return statusAntes == StatusPedido.SAIU_PARA_ENTREGA || statusAntes == StatusPedido.ENTREGUE;
    }

    /**
     * Devolve o estoque e o cashback do pedido cancelado.
     *
     * @param statusAntes o status que o pedido tinha ANTES de virar CANCELADO — e ele que diz o que
     *                    ainda existe fisicamente. Passar o status ja cancelado devolveria tudo
     *                    sempre, inclusive comida que a cozinha fez.
     * @param vendaJaContabilizada esta venda ja tinha entrado no historico do cliente e gerado
     *                    cashback? E {@code false} so para PIX que nunca foi pago (expirou ou nem
     *                    gerou cobranca): ali houve apenas o debito do resgate. Nao use
     *                    {@code p.pagamentoPendente()} para decidir isto — dois dos chamadores zeram
     *                    {@code aguardandoPagamento} antes de chegar aqui, e a resposta sairia errada.
     * @return quantos itens tiveram algo devolvido (para o log de quem chamou)
     */
    @Transactional
    public int devolver(Pedido p, StatusPedido statusAntes, boolean vendaJaContabilizada) {
        if (p == null || p.id == null) return 0;
        // O cashback volta sempre, em qualquer estagio: ele nao e comida que estragou, e dinheiro que
        // o cliente pos num pedido que nao aconteceu.
        fidelidade.estornar(p.lojaId, p.clienteId, p.valorTotal, p.cashbackUsado, vendaJaContabilizada);
        boolean podeDevolverInsumo = !entrouEmPreparo(statusAntes);
        boolean podeDevolverProduto = !jaSaiuDaLoja(statusAntes);
        if (!podeDevolverInsumo && !podeDevolverProduto) return 0;

        List<PedidoItem> lista = itens.findByLojaIdAndPedidoIdOrderById(p.lojaId, p.id);
        int devolvidos = 0;
        int naoDevolvidosPorFaltaDeMarca = 0;
        for (PedidoItem item : lista) {
            int qtd = item.getQuantidade() == null ? 1 : item.getQuantidade();
            if (qtd <= 0 || item.getProdutoId() == null) continue;
            Boolean porFicha = item.getConsumiuFicha();

            if (Boolean.TRUE.equals(porFicha)) {
                if (podeDevolverInsumo && insumos.devolverFicha(p.lojaId, item.getProdutoId(), qtd)) devolvidos++;
                continue;
            }
            if (Boolean.FALSE.equals(porFicha)) {
                if (podeDevolverProduto && devolverProduto(p.lojaId, item.getProdutoId(), qtd)) devolvidos++;
                continue;
            }
            // Item sem a marca (anterior a V45): NAO devolve nada.
            //
            // A versao anterior caia na ficha de hoje, e isso inventava estoque. O cardapio publico so
            // passou a baixar estoque em 01/10/2026 (commit e9a8bd7): item criado ali antes disso tem
            // produto_id preenchido e NUNCA tirou nada do estoque. Devolver somaria unidade que jamais
            // saiu, calado. E para item de balcao antigo cuja ficha mudou depois, a ficha de hoje
            // devolve o insumo errado.
            //
            // Entre errar somando e nao mexer, nao mexer e melhor: estoque inventado e silencioso e o
            // lojista nunca descobre de onde veio. O aviso no log diz o que conferir a mao. Isso some
            // sozinho conforme os pedidos antigos saem do ar — todo item novo ja nasce com a marca.
            naoDevolvidosPorFaltaDeMarca++;
        }
        if (devolvidos > 0) {
            log.info("Pedido {} da loja {} cancelado em {}: estoque devolvido em {} item(ns)",
                    p.id, p.lojaId, statusAntes, devolvidos);
        }
        if (naoDevolvidosPorFaltaDeMarca > 0) {
            log.warn("Pedido {} da loja {} cancelado: {} item(ns) sao anteriores a V45 e nao registram "
                            + "como baixaram estoque; nada foi devolvido neles, confira o estoque a mao",
                    p.id, p.lojaId, naoDevolvidosPorFaltaDeMarca);
        }
        return devolvidos;
    }

    /**
     * Baixa o estoque de novo, para o pedido que VOLTOU A VALER.
     *
     * <h2>Quando isto acontece</h2>
     * <p>Um unico caso: o PIX do cardapio expirou, o pedido foi cancelado (e o cancelamento devolveu
     * o estoque), e DEPOIS o dinheiro entrou. O pedido e reaberto, porque o cliente pagou e esta
     * esperando comida -- e aí o estoque tem que sair de novo, senao a comida sai e o estoque segue
     * contando. Ver {@code PublicController}, no trecho do {@code pixWebhook}.</p>
     *
     * <p>E o espelho exato do {@link #devolver}, e mora aqui pelo mesmo motivo: a regra de "produto
     * com ficha consome insumo, produto sem ficha baixa o proprio estoque" existe num lugar so.</p>
     *
     * <h2>Duas decisoes que nao sao obvias</h2>
     * <p><b>Nao recusa por falta de estoque.</b> A venda esta paga; recusar aqui nao desfaz o
     * pagamento, so deixaria o estoque errado E o pedido sem baixa. Saldo negativo e informacao de
     * que a contagem esta errada -- ver {@code PedidoService.avisarSeVaiFicarNegativo}.</p>
     *
     * <p><b>Item sem a marca {@code consumiuFicha} (anterior a V45) nao baixa nada</b>, igual ao
     * {@link #devolver}: se nao se sabe como saiu, nao se sabe como tirar de novo, e inventar baixa e
     * tao ruim quanto inventar devolucao.</p>
     *
     * @return quantos itens tiveram estoque baixado (para o log de quem chamou)
     */
    @Transactional
    public int rebaixarDeNovo(Pedido p) {
        if (p == null || p.id == null) return 0;
        List<PedidoItem> lista = itens.findByLojaIdAndPedidoIdOrderById(p.lojaId, p.id);
        int baixados = 0;
        int semMarca = 0;
        for (PedidoItem item : lista) {
            int qtd = item.getQuantidade() == null ? 1 : item.getQuantidade();
            if (qtd <= 0 || item.getProdutoId() == null) continue;
            Boolean porFicha = item.getConsumiuFicha();
            if (Boolean.TRUE.equals(porFicha)) {
                // Modo que NAO recusa: a venda esta paga. Consome a ficha de HOJE -- mesma ressalva do
                // devolver, e a melhor informacao que existe.
                insumos.consumirFicha(p.lojaId, item.getProdutoId(), qtd);
                baixados++;
            } else if (Boolean.FALSE.equals(porFicha)) {
                if (produtos.baixarEstoque(item.getProdutoId(), p.lojaId, qtd) > 0) baixados++;
            } else {
                semMarca++;
            }
        }
        if (baixados > 0) {
            log.info("Pedido {} da loja {} reaberto: estoque baixado de novo em {} item(ns)",
                    p.id, p.lojaId, baixados);
        }
        if (semMarca > 0) {
            log.warn("Pedido {} da loja {} reaberto: {} item(ns) sao anteriores a V45 e nao registram "
                            + "como baixaram estoque; nada foi baixado neles, confira o estoque a mao",
                    p.id, p.lojaId, semMarca);
        }
        return baixados;
    }

    /**
     * Devolve o estoque do proprio produto. So mexe em produto que controla estoque
     * ({@code estoque != null}), igual a baixa — quem nao controla continua sem numero, em vez de
     * nascer com um saldo inventado no primeiro cancelamento.
     */
    private boolean devolverProduto(Long lojaId, Long produtoId, int qtd) {
        // No banco, pelo mesmo motivo da baixa: dois cancelamentos simultaneos do mesmo pedido liam o
        // mesmo estoque e devolviam em dobro. O `where estoque is not null` mantem a regra antiga de
        // nao inventar saldo para produto que nao controla estoque.
        return produtos.devolverEstoque(produtoId, lojaId, qtd) > 0;
    }
}
