package br.com.bora.entity; import jakarta.persistence.*; import lombok.*; @Entity @Getter @Setter public class ConfiguracaoLoja{@Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public Long lojaId; public String nomeExibicao; public String nomeSistema; public String logoUrl; public String corPrimaria; public String corSecundaria; public String bannerUrl; public String subdominio; public Boolean mostrarMarcaBora=true;
 /** Cashback devolvido ao cliente, em % do valor pago. 0 desliga. Padrao 5. */
 @Column(name="cashback_percentual") public java.math.BigDecimal cashbackPercentual;
 /** Aliquota de imposto sobre o faturamento, em %. Sem ela o lucro sai sem imposto e engana. */
 @Column(name="aliquota_imposto") public java.math.BigDecimal aliquotaImposto;
 /** Custo fixo mensal da loja (aluguel, folha, energia). Rateado pelos dias do periodo. */
 @Column(name="custo_fixo_mensal") public java.math.BigDecimal custoFixoMensal;}
