package br.com.bora.entity;
import jakarta.persistence.*; import lombok.*; import java.math.BigDecimal;
@Entity @Table(name="insumo") @Getter @Setter
public class Insumo {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="loja_id") public Long lojaId;
    public String nome;
    public String unidade = "un";
    public BigDecimal custo = BigDecimal.ZERO;
    public BigDecimal estoque;
    @Column(name="estoque_minimo") public BigDecimal estoqueMinimo;
}
