package br.com.bora.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Visão rica de um pedido para o quadro (kanban) — tudo que o card precisa em 1 chamada. */
public record PedidoCard(
        Long id,
        String codigo,
        String status,
        BigDecimal valorTotal,
        String formaPagamento,
        String origem,
        String observacao,
        OffsetDateTime criadoEm,
        OffsetDateTime atualizadoEm,
        String clienteNome,
        String clienteTelefone,
        String clienteEndereco,
        String clienteBairro,
        String entregador,
        /** PIX gerado e ainda não pago. O card precisa disto: a cozinha não deve preparar venda que
         *  não aconteceu, e o fechamento de caixa não pode contá-la como faturamento. A tela usava o
         *  TEXTO da forma de pagamento para adivinhar — contrato frágil que a V39 queria eliminar. */
        Boolean aguardandoPagamento,
        List<ItemResumo> itens) {

    public record ItemResumo(Integer quantidade, String descricao) {}
}
