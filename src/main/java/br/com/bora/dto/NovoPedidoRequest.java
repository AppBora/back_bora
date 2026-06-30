package br.com.bora.dto;

import java.util.List;

public record NovoPedidoRequest(
        Long clienteId,
        String codigo,
        String formaPagamento,
        String origem,
        String observacao,
        Boolean usarCashback,
        List<ItemPedidoRequest> itens) {}
