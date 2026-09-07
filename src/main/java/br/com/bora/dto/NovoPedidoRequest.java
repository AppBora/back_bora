package br.com.bora.dto;

import java.util.List;

public record NovoPedidoRequest(
        Long clienteId,
        String codigo,
        String formaPagamento,
        String origem,
        String observacao,
        Boolean usarCashback,
        List<ItemPedidoRequest> itens,
        /** Taxa de entrega informada pelo operador. Null = calcula pelo bairro do cliente. */
        java.math.BigDecimal taxaEntrega) {}
