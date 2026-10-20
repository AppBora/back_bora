package br.com.bora.entity; import jakarta.persistence.*; import lombok.*; import java.math.BigDecimal; @Entity @Getter @Setter public class Produto{@Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public Long lojaId; public String nome; public String categoria; public BigDecimal preco; public BigDecimal custo; public Integer estoque; @Column(name="estoque_minimo") public Integer estoqueMinimo;
    @Column(name = "imagem_url", columnDefinition = "TEXT")
    public String imagemUrl; public Boolean ativo=true;

    /**
     * Codigo deste produto no iFood/99 -- o {@code externalCode} que vem no payload do pedido.
     *
     * <p>Opcional. Preenchido, e por ele que o pedido de marketplace acha o produto e baixa estoque.
     * Vazio, o sistema tenta pelo nome limpo. Ver {@code PedidoService.resolverProduto}.</p>
     */
    @Column(name = "codigo_externo")
    public String codigoExterno;
}
