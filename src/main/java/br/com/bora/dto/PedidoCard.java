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
        /**
         * Endereco informado NESTE pedido, e o tipo de entrega. Item 6.2 da varredura.
         *
         * <p>O card mandava so {@code clienteEndereco} e {@code clienteBairro}, que sao os do
         * CADASTRO. Em cliente recorrente que se mudou, o motoboy lia o endereco antigo -- e e
         * exatamente o que a V48 foi criada para resolver: ela gravou as colunas no pedido, mas elas
         * nunca chegaram na tela.</p>
         *
         * <p><b>As telas ja esperavam por isto</b> ({@code pedidos.js:110},
         * {@code entregas.js:30,70}): elas escrevem {@code p.enderecoEntrega || p.clienteEndereco}, e
         * como o campo nunca vinha, o segundo sempre ganhava. Faltava o dado, nao a tela.</p>
         *
         * <p>Nulo em pedido anterior a V48 — ai a tela cai no cadastro, que e a melhor informacao que
         * existe para o passado.</p>
         */
        String enderecoEntrega,
        String bairroEntrega,
        /** ENTREGA ou RETIRADA. Sem isto a tela de Entregas oferece "Despachar" para quem vem buscar. */
        String tipoEntrega,
        String entregador,
        /** PIX gerado e ainda não pago. O card precisa disto: a cozinha não deve preparar venda que
         *  não aconteceu, e o fechamento de caixa não pode contá-la como faturamento. A tela usava o
         *  TEXTO da forma de pagamento para adivinhar — contrato frágil que a V39 queria eliminar. */
        Boolean aguardandoPagamento,
        List<ItemResumo> itens) {

    public record ItemResumo(Integer quantidade, String descricao) {}
}
