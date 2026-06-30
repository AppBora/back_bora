package br.com.bora.entity;
import jakarta.persistence.*; import lombok.*;
@Entity @Table(name="motivo_cancelamento") @Getter @Setter
public class MotivoCancelamento {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="loja_id") public Long lojaId;
    public String descricao;
    public Boolean ativo = true;
}
