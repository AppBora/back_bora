package br.com.bora.dto;

import java.util.List;

/** Item do pedido lançado pelo painel. {@code complementos} são ids de ComplementoItem escolhidos. */
public record ItemPedidoRequest(Long produtoId, Integer quantidade, List<Long> complementos) {}
