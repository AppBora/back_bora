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
 * Pedido cancelado devolve o que consumiu.
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
public class DevolucaoDeEstoqueService {

    private final PedidoItemRepository itens;
    private final ProdutoRepository produtos;
    private final InsumoService insumos;

    public DevolucaoDeEstoqueService(PedidoItemRepository itens, ProdutoRepository produtos,
                                     InsumoService insumos) {
        this.itens = itens;
        this.produtos = produtos;
        this.insumos = insumos;
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
     * Devolve o estoque do pedido cancelado.
     *
     * @param statusAntes o status que o pedido tinha ANTES de virar CANCELADO — e ele que diz o que
     *                    ainda existe fisicamente. Passar o status ja cancelado devolveria tudo
     *                    sempre, inclusive comida que a cozinha fez.
     * @return quantos itens tiveram algo devolvido (para o log de quem chamou)
     */
    @Transactional
    public int devolver(Pedido p, StatusPedido statusAntes) {
        if (p == null || p.id == null) return 0;
        boolean podeDevolverInsumo = !entrouEmPreparo(statusAntes);
        boolean podeDevolverProduto = !jaSaiuDaLoja(statusAntes);
        if (!podeDevolverInsumo && !podeDevolverProduto) return 0;

        List<PedidoItem> lista = itens.findByLojaIdAndPedidoIdOrderById(p.lojaId, p.id);
        int devolvidos = 0;
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
            // Item sem a marca (anterior a V45): tenta a ficha de hoje; sem ficha, e estoque de produto.
            if (podeDevolverInsumo && insumos.devolverFicha(p.lojaId, item.getProdutoId(), qtd)) {
                devolvidos++;
            } else if (podeDevolverProduto && devolverProduto(p.lojaId, item.getProdutoId(), qtd)) {
                devolvidos++;
            }
        }
        if (devolvidos > 0) {
            log.info("Pedido {} da loja {} cancelado em {}: estoque devolvido em {} item(ns)",
                    p.id, p.lojaId, statusAntes, devolvidos);
        }
        return devolvidos;
    }

    /**
     * Devolve o estoque do proprio produto. So mexe em produto que controla estoque
     * ({@code estoque != null}), igual a baixa — quem nao controla continua sem numero, em vez de
     * nascer com um saldo inventado no primeiro cancelamento.
     */
    private boolean devolverProduto(Long lojaId, Long produtoId, int qtd) {
        Produto prod = produtos.findByIdAndLojaId(produtoId, lojaId).orElse(null);
        if (prod == null || prod.estoque == null) return false;
        prod.estoque = prod.estoque + qtd;
        produtos.save(prod);
        return true;
    }
}
