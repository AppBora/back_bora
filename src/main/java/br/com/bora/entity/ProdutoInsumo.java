package br.com.bora.entity;
import jakarta.persistence.*; import lombok.*; import java.math.BigDecimal;
@Entity @Table(name="produto_insumo") @Getter @Setter
public class ProdutoInsumo {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="loja_id") public Long lojaId;
    @Column(name="produto_id") public Long produtoId;
    @Column(name="insumo_id") public Long insumoId;
    public BigDecimal quantidade = BigDecimal.ZERO;
}
