package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Assinatura SaaS de uma loja, espelhando a cobrança no Asaas. */
@Entity
@Getter
@Setter
public class Assinatura {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long lojaId;

    @Enumerated(EnumType.STRING)
    private Plano plano;

    @Enumerated(EnumType.STRING)
    private StatusAssinatura status = StatusAssinatura.PENDENTE;

    @Column(precision = 12, scale = 2)
    private BigDecimal valor;

    private String asaasCustomerId;
    private String asaasSubscriptionId;

    private OffsetDateTime criadoEm = OffsetDateTime.now();
    private OffsetDateTime atualizadoEm = OffsetDateTime.now();
}
