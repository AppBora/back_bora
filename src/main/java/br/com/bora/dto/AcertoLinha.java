package br.com.bora.dto;

import java.math.BigDecimal;

/** Linha da prévia de acerto: totais de um entregador no período (entregas ainda em aberto). */
public record AcertoLinha(
        String entregador,
        long qtdeEntregas,
        BigDecimal valorTaxas,
        BigDecimal valorDinheiro,
        BigDecimal valorOutras,
        BigDecimal valorTotal,
        BigDecimal valorAPagar,
        /**
         * O CADASTRO do motoboy desta linha, quando ela e por cadastro (item 3 da secao 4).
         *
         * <p>Nulo na linha legada (entrega gravada antes da V80, ou nome que nao casou com um unico
         * cadastro) e na linha "(entregas sem motoboy escolhido)". A tela manda este id de volta no
         * "fazer acerto" quando ele existe; sem ele, manda o nome, como antes.</p>
         */
        Long entregadorId
) {}
