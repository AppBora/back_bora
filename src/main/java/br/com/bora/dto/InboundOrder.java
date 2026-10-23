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
        String clienteIdExterno,
        /**
         * Item 5.8: quem entrega. {@code TRUE} = o canal (e fica com a taxa), {@code FALSE} = a
         * loja, {@code null} = o canal nao informou. Nulo nao e false de proposito: ver
         * {@code Pedido.entregaPeloCanal}.
         */
        Boolean entregaPeloCanal) {

    /** Sem o id do cliente no marketplace (canais que ainda nao o leem). */
    public InboundOrder(String externalId, String clienteNome, String clienteTelefone, String endereco, String bairro,
                        String pagamento, String observacao, BigDecimal total, List<InboundItem> itens,
                        BigDecimal taxaEntrega, String numeroExibicao) {
        this(externalId, clienteNome, clienteTelefone, endereco, bairro, pagamento, observacao, total, itens,
                taxaEntrega, numeroExibicao, null, null);
    }

    /**
     * O mesmo pedido com o id que o marketplace da ao cliente. E por ele que o cadastro reconhece o
     * cliente que volta: o telefone do iFood e a central do iFood, igual para todos.
     */
    public InboundOrder comClienteExterno(String id) {
        return new InboundOrder(externalId, clienteNome, clienteTelefone, endereco, bairro, pagamento, observacao,
                total, itens, taxaEntrega, numeroExibicao, id == null || id.isBlank() ? null : id.trim(),
                entregaPeloCanal);
    }

    /**
     * O mesmo pedido dizendo quem entrega (item 5.8).
     *
     * <p>O dado ja vinha no payload e era usado so para escrever "Entrega pelo iFood" na observacao —
     * texto, que nenhuma consulta alcanca. Com ele gravado da para somar quanto do faturamento e taxa
     * que o canal ficou.</p>
     */
    public InboundOrder comEntregaDoCanal(Boolean doCanal) {
        return new InboundOrder(externalId, clienteNome, clienteTelefone, endereco, bairro, pagamento, observacao,
                total, itens, taxaEntrega, numeroExibicao, clienteIdExterno, doCanal);
    }

    /**
     * Formato sem taxa de entrega nem numero de exibicao — os canais que ainda nao os leem.
     * O numero de exibicao existe porque o id do Open Delivery e um UUID: ele nao serve para o
     * atendente falar com o cliente ("pedido #4201").
     */
    public InboundOrder(String externalId, String clienteNome, String clienteTelefone, String endereco, String bairro,
                        String pagamento, String observacao, BigDecimal total, List<InboundItem> itens) {
        this(externalId, clienteNome, clienteTelefone, endereco, bairro, pagamento, observacao, total, itens,
                null, null, null, null);
    }

    /**
     * Um item do pedido que veio de fora.
     *
     * @param nome          o texto que vai para a TELA: ja traz complemento e observacao juntos
     *                      ("Acai 500ml (+ Granola) -- obs: sem cebola"). Nao serve para casar com
     *                      o cadastro.
     * @param codigoExterno o {@code externalCode} do payload -- o codigo que o lojista cadastrou no
     *                      marketplace. E o jeito confiavel de saber QUAL produto foi vendido.
     * @param nomeLimpo     o nome do produto sozinho, sem complemento nem observacao. Usado para
     *                      achar o produto quando o codigo nao foi preenchido no cadastro.
     */
    public record InboundItem(String nome, Integer quantidade, BigDecimal precoUnitario,
                              String codigoExterno, String nomeLimpo) {

        /** Para os canais que ainda nao trazem codigo (Rappi, Uber Eats e os testes antigos). */
        public InboundItem(String nome, Integer quantidade, BigDecimal precoUnitario) {
            this(nome, quantidade, precoUnitario, null, null);
        }
    }
}
