package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** Disparo do Recuperador de clientes (Módulo IA) — base da medição de ROI. */
@Entity
@Table(name = "ia_recuperacao")
@Getter
@Setter
public class IaRecuperacao {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loja_id")
    private Long lojaId;

    @Column(name = "cliente_id")
    private Long clienteId;

    private String telefone;

    @Column(name = "enviado_em")
    private OffsetDateTime enviadoEm = OffsetDateTime.now();
}
