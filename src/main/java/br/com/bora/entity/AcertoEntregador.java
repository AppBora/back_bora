package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Fechamento financeiro de um entregador num período (taxas de entrega a pagar ao motoboy). */
@Entity
@Table(name = "acerto_entregador")
@Getter
@Setter
public class AcertoEntregador {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long lojaId;
    public String entregador;
    @Column(name = "periodo_inicio") public LocalDate periodoInicio;
    @Column(name = "periodo_fim")    public LocalDate periodoFim;
    @Column(name = "qtde_entregas")  public Integer qtdeEntregas = 0;
    @Column(name = "valor_taxas")    public BigDecimal valorTaxas = BigDecimal.ZERO;
    @Column(name = "valor_dinheiro") public BigDecimal valorDinheiro = BigDecimal.ZERO;
    @Column(name = "valor_outras")   public BigDecimal valorOutras = BigDecimal.ZERO;
    @Column(name = "valor_total")    public BigDecimal valorTotal = BigDecimal.ZERO;
    @Column(name = "valor_a_pagar")  public BigDecimal valorAPagar = BigDecimal.ZERO;
    @Column(name = "valor_pago")     public BigDecimal valorPago = BigDecimal.ZERO;
    public BigDecimal descontos = BigDecimal.ZERO;
    public BigDecimal saldo = BigDecimal.ZERO;
    public String observacao;
    @Column(name = "criado_em")  public OffsetDateTime criadoEm = OffsetDateTime.now();
    @Column(name = "criado_por") public Long criadoPor;
}
