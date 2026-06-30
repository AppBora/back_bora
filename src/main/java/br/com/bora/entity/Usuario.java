package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

@Entity
@Table(name = "usuario")
@Getter
@Setter
public class Usuario {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Loja (tenant) dona do usuário. Nulo apenas para ADMINISTRADOR_BORA (global). */
    @Column(name = "loja_id")
    private Long lojaId;

    private String nome;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "senha_hash", nullable = false)
    private String senhaHash;

    @Enumerated(EnumType.STRING)
    private Papel papel = Papel.OPERADOR;

    private Boolean ativo = true;

    @Column(name = "criado_em")
    private OffsetDateTime criadoEm = OffsetDateTime.now();
}
