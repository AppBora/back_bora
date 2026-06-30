package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

/** RN07 — registro de toda mudança de status de pedido. */
@Entity
@Table(name = "log_status")
@Getter
@Setter
public class LogStatus {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loja_id")
    private Long lojaId;

    @Column(name = "pedido_id")
    private Long pedidoId;

    @Column(name = "status_anterior")
    private String statusAnterior;

    @Column(name = "status_novo")
    private String statusNovo;

    @Column(name = "usuario_id")
    private Long usuarioId;

    @Column(name = "data_hora")
    private OffsetDateTime dataHora = OffsetDateTime.now();
}
