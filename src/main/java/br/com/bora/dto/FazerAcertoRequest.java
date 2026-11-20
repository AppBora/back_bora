package br.com.bora.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Pedido de fechamento (acerto) de um entregador num período. */
public record FazerAcertoRequest(
        String entregador,
        /**
         * O cadastro do motoboy (item 3 da secao 4). Quando vem preenchido, e ELE que define quais
         * entregas entram no acerto -- o nome passa a ser so o que vai no recibo. Nulo = acerto da
         * linha legada, por texto, como antes da V80.
         */
        Long entregadorId,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate inicio,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate fim,
        BigDecimal valorPago,
        BigDecimal descontos,
        String observacao
) {}
