package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Opção dentro de um grupo de complementos (ex.: "Borda catupiry +R$ 8"). */
@Entity
@Table(name = "complemento_item")
@Getter
@Setter
public class ComplementoItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "loja_id")
    public Long lojaId;
    @Column(name = "grupo_id")
    public Long grupoId;
    public String nome;
    public BigDecimal preco = BigDecimal.ZERO;

    /**
     * Insumo que este adicional consome, ou {@code null} quando ele nao baixa estoque.
     *
     * <p>Nulo e o padrao, e e o comportamento de tudo que foi cadastrado antes da V55: adicional sem
     * insumo ligado nao sai do estoque nem entra no custo, exatamente como era antes.</p>
     */
    @Column(name = "insumo_id")
    public Long insumoId;

    /** Quanto do insumo sai por unidade vendida, na unidade do insumo (ex.: 0,0300 kg). */
    public BigDecimal quantidade;
}
