package br.com.bora.entity;
import jakarta.persistence.*; import lombok.*; import java.math.BigDecimal;
@Entity @Table(name="taxa_entrega") @Getter @Setter
public class TaxaEntrega {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="loja_id") public Long lojaId;
    public String bairro;
    public BigDecimal taxa = BigDecimal.ZERO;
    @Column(name="tempo_min") public Integer tempoMin;
    public Boolean ativo = true;
}
