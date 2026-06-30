package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "promocao")
@Getter
@Setter
public class Promocao {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loja_id")
    private Long lojaId;

    private String tipo;        // RELAMPAGO, COMBO, FRETE_GRATIS, ULTIMA_HORA
    private String descricao;
    private String codigo;

    @Column(name = "percentual_desconto")
    private BigDecimal percentualDesconto;

    private OffsetDateTime inicio = OffsetDateTime.now();
    private OffsetDateTime fim;

    @Column(name = "limite_usos")
    private Integer limiteUsos;

    private Integer usos = 0;
    private Boolean ativa = true;
    private String origem;       // MANUAL ou AUTO

    @Column(name = "criado_em")
    private OffsetDateTime criadoEm = OffsetDateTime.now();
}
