package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class Entregador {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long lojaId;
    public String nome;
    public String telefone;
    public String veiculo;     // ex.: Moto, Bike, Carro
    public Boolean ativo = true;
}
