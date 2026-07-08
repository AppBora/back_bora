package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Grupo de complementos de um produto (ex.: "Tamanho" 1/1, "Adicionais" 0/5). */
@Entity
@Table(name = "complemento_grupo")
@Getter
@Setter
public class ComplementoGrupo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "loja_id")
    public Long lojaId;
    @Column(name = "produto_id")
    public Long produtoId;
    public String nome;
    public Integer minimo = 0;
    public Integer maximo = 1;
}
