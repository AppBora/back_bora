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

    /**
     * Id da assinatura do Asaas que esta substituiu, quando o cancelamento dela FALHOU.
     *
     * <p>Preenchida = ha duas assinaturas vivas no Asaas e o lojista esta sendo cobrado em dobro;
     * alguem precisa cancelar esta a mao. Nula no caminho normal. Ver {@code AssinaturaService.assinar}
     * e a V52 para o porque de a ordem ser "cria primeiro, cancela depois".</p>
     */
    @Column(name = "asaas_subscription_anterior")
    private String asaasSubscriptionAnterior;

    private OffsetDateTime criadoEm = OffsetDateTime.now();
    private OffsetDateTime atualizadoEm = OffsetDateTime.now();
}
