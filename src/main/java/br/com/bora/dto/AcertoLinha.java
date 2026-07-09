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
        BigDecimal valorAPagar
) {}
