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
        java.math.BigDecimal taxaEntrega,
        /**
         * ENTREGA ou RETIRADA. Null vale como ENTREGA, que e o comportamento de antes.
         *
         * <p>Sem isto, retirada no balcao existia apenas como texto na observacao e a tela de Entregas
         * oferecia "Despachar" para quem ia buscar na loja.</p>
         */
        String tipoEntrega,
        /** Endereco DESTE pedido. Vazio = usa o do cadastro do cliente, como antes. */
        String enderecoEntrega,
        /** Bairro DESTE pedido, que define o frete. Vazio = usa o do cadastro do cliente. */
        String bairroEntrega) {}
