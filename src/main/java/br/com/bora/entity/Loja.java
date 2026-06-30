package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class Loja {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String nome;
    public String documento;
    public Boolean ativo = true;

    @Enumerated(EnumType.STRING)
    public Plano plano = Plano.START;
}
