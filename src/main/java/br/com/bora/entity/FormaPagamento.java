package br.com.bora.entity;
import jakarta.persistence.*; import lombok.*;
@Entity @Table(name="forma_pagamento") @Getter @Setter
public class FormaPagamento {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="loja_id") public Long lojaId;
    public String descricao;
    @Column(name="com_troco") public Boolean comTroco = false;
    public Boolean online = false;
    public Boolean ativo = true;
    public Integer ordem = 0;
}
