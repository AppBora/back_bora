package br.com.bora.entity; import jakarta.persistence.*; import lombok.*; import java.math.BigDecimal; @Entity @Getter @Setter public class Produto{@Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public Long lojaId; public String nome; public String categoria; public BigDecimal preco; public BigDecimal custo; public Integer estoque; @Column(name="estoque_minimo") public Integer estoqueMinimo;
    @Column(name = "imagem_url", columnDefinition = "TEXT")
    public String imagemUrl; public Boolean ativo=true;}
