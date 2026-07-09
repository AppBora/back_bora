package br.com.bora.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Pedido de fechamento (acerto) de um entregador num período. */
public record FazerAcertoRequest(
        String entregador,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate inicio,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate fim,
        BigDecimal valorPago,
        BigDecimal descontos,
        String observacao
) {}
