package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Entity
@Table(name = "pedido_item")
@Getter
@Setter
public class PedidoItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loja_id")
    private Long lojaId;

    @Column(name = "pedido_id")
    private Long pedidoId;

    @Column(name = "produto_id")
    private Long produtoId;

    private String descricao;
    private Integer quantidade = 1;

    @Column(name = "preco_unitario")
    private BigDecimal precoUnitario;

    @Column(name = "custo_unitario")
    private BigDecimal custoUnitario;

    private BigDecimal subtotal;

    /**
     * Como este item baixou estoque: {@code true} pela ficha tecnica (insumos), {@code false} pelo
     * estoque do proprio produto, {@code null} para item criado antes da V45. Quem cancela o pedido
     * le isto para devolver exatamente o que foi consumido, em vez de deduzir pela ficha de hoje.
     */
    @Column(name = "consumiu_ficha")
    private Boolean consumiuFicha;

    /**
     * Ids dos complementos escolhidos, separados por virgula, na ordem em que vieram
     * ({@code "12,15"}). A {@code descricao} guarda os nomes, que servem para ler e nao para
     * refazer: nome muda e se repete entre grupos. {@code null} para item criado antes da V46 —
     * nesse caso o "repetir pedido" remonta o produto e pede os complementos de novo, em vez de
     * adivinhar.
     */
    @Column(name = "complementos")
    private String complementos;
}
