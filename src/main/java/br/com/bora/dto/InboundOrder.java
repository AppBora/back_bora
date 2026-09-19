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
        String numeroExibicao,
        String clienteIdExterno) {

    /** Sem o id do cliente no marketplace (canais que ainda nao o leem). */
    public InboundOrder(String externalId, String clienteNome, String clienteTelefone, String endereco, String bairro,
                        String pagamento, String observacao, BigDecimal total, List<InboundItem> itens,
                        BigDecimal taxaEntrega, String numeroExibicao) {
        this(externalId, clienteNome, clienteTelefone, endereco, bairro, pagamento, observacao, total, itens,
                taxaEntrega, numeroExibicao, null);
    }

    /**
     * O mesmo pedido com o id que o marketplace da ao cliente. E por ele que o cadastro reconhece o
     * cliente que volta: o telefone do iFood e a central do iFood, igual para todos.
     */
    public InboundOrder comClienteExterno(String id) {
        return new InboundOrder(externalId, clienteNome, clienteTelefone, endereco, bairro, pagamento, observacao,
                total, itens, taxaEntrega, numeroExibicao, id == null || id.isBlank() ? null : id.trim());
    }

    /**
     * Formato sem taxa de entrega nem numero de exibicao — os canais que ainda nao os leem.
     * O numero de exibicao existe porque o id do Open Delivery e um UUID: ele nao serve para o
     * atendente falar com o cliente ("pedido #4201").
     */
    public InboundOrder(String externalId, String clienteNome, String clienteTelefone, String endereco, String bairro,
                        String pagamento, String observacao, BigDecimal total, List<InboundItem> itens) {
        this(externalId, clienteNome, clienteTelefone, endereco, bairro, pagamento, observacao, total, itens, null, null, null);
    }

    public record InboundItem(String nome, Integer quantidade, BigDecimal precoUnitario) {}
}
