package br.com.bora.dto;

import br.com.bora.entity.PedidoItem;

import java.math.BigDecimal;

/** Item de pedido exposto na API — sem custo (margem) nem campos internos de tenant. */
public record PedidoItemView(Long id, Integer quantidade, String descricao,
                             BigDecimal precoUnitario, BigDecimal subtotal) {
    public static PedidoItemView de(PedidoItem it) {
        return new PedidoItemView(it.getId(), it.getQuantidade(), it.getDescricao(),
                it.getPrecoUnitario(), it.getSubtotal());
    }
}
