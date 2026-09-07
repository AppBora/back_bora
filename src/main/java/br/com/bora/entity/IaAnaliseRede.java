package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Análise de IA já paga no dia. Existir a linha é o que impede a segunda chamada. */
@Entity
@Table(name = "ia_analise_rede")
@Getter
@Setter
public class IaAnaliseRede {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "usuario_id")
    public Long usuarioId;
    @Column(name = "loja_id")
    public Long lojaId;
    public LocalDate dia;
    public LocalDate inicio;
    public LocalDate fim;
    @Column(columnDefinition = "TEXT")
    public String plano;
    @Column(name = "criado_em")
    public OffsetDateTime criadoEm = OffsetDateTime.now();
}
