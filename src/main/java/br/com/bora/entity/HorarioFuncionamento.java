package br.com.bora.entity;
import jakarta.persistence.*; import lombok.*;
@Entity @Table(name="horario_funcionamento") @Getter @Setter
public class HorarioFuncionamento {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="loja_id") public Long lojaId;
    public Integer dia;       // 0=domingo ... 6=sábado
    public String abre;       // "18:00"
    public String fecha;      // "23:30"
    public Boolean aberto = true;
}
