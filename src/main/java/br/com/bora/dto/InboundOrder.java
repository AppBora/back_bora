package br.com.bora.dto;

import java.math.BigDecimal;
import java.util.List;

/** Pedido normalizado vindo de qualquer marketplace (formato canônico interno). */
public record InboundOrder(
        String externalId,
        String clienteNome,
        String clienteTelefone,
        String endereco,
        String bairro,
        String pagamento,
        String observacao,
        BigDecimal total,
        List<InboundItem> itens) {

    public record InboundItem(String nome, Integer quantidade, BigDecimal precoUnitario) {}
}
