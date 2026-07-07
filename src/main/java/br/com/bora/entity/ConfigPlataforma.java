package br.com.bora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** Configuração global da plataforma (chave/valor) — só o ADMINISTRADOR_BORA lê e altera. */
@Entity
@Table(name = "config_plataforma")
@Getter
@Setter
public class ConfigPlataforma {

    @Id
    private String chave;

    @Column(nullable = false)
    private String valor;

    @Column(name = "atualizado_em")
    private OffsetDateTime atualizadoEm = OffsetDateTime.now();
}
