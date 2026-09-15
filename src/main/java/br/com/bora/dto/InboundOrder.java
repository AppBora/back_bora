package br.com.bora.dto;

import java.math.BigDecimal;
import java.util.List;

/** Pedido normalizado vindo de qualquer marketplace (formato canonico interno). */
public record InboundOrder(
        String externalId,
        String clienteNome,
        String clienteTelefone,
        String endereco,
        String bairro,
        String pagamento,
        String observacao,
        BigDecimal total,
        List<InboundItem> itens,
        BigDecimal taxaEntrega,
        String numeroExibicao) {

    /**
     * Formato sem taxa de entrega nem numero de exibicao — os canais que ainda nao os leem.
     * O numero de exibicao existe porque o id do Open Delivery e um UUID: ele nao serve para o
     * atendente falar com o cliente ("pedido #4201").
     */
    public InboundOrder(String externalId, String clienteNome, String clienteTelefone, String endereco, String bairro,
                        String pagamento, String observacao, BigDecimal total, List<InboundItem> itens) {
        this(externalId, clienteNome, clienteTelefone, endereco, bairro, pagamento, observacao, total, itens, null, null);
    }

    public record InboundItem(String nome, Integer quantidade, BigDecimal precoUnitario) {}
}
