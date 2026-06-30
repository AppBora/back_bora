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
}
