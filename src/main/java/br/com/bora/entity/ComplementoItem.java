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
}
