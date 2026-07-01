package br.com.bora.dto;

import br.com.bora.entity.Assinatura;

import java.math.BigDecimal;

/** Assinatura exposta na API — sem os IDs internos do gateway (Asaas). */
public record AssinaturaView(Long id, String plano, String status, BigDecimal valor) {
    public static AssinaturaView de(Assinatura a) {
        if (a == null) return null;
        return new AssinaturaView(
                a.getId(),
                a.getPlano() == null ? null : a.getPlano().name(),
                a.getStatus() == null ? null : a.getStatus().name(),
                a.getValor());
    }
}
