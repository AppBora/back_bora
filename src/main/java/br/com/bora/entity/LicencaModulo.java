package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Um módulo do ERP liberado (ou não) para uma loja.
 *
 * <p><b>Ausência de linha significa DESLIGADO.</b> É por isso que a tabela pôde nascer vazia na
 * V74 sem mudar o comportamento de nenhuma das lojas que já rodam.</p>
 *
 * <p>Quem responde "este módulo está ligado?" é o {@code LicencaModuloService}, nunca esta
 * entidade direto — porque a resposta de hoje ainda depende dos dois mecanismos antigos
 * ({@code config_plataforma['fiscal.habilitado']} e {@code loja.modulo_ia}), e espalhar essa
 * tradução pelo código seria garantir que um lugar esquecesse dela.</p>
 */
@Entity
@Table(name = "licenca_modulo")
@Getter
@Setter
public class LicencaModulo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loja_id", nullable = false)
    private Long lojaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "TEXT")
    private Modulo modulo;

    @Column(nullable = false)
    private Boolean ativo = false;

    @Column(name = "ativado_em")
    private OffsetDateTime ativadoEm;

    /** Quem ligou. Auditoria mínima: ligar módulo é decisão comercial, não ajuste técnico. */
    @Column(name = "ativado_por")
    private Long ativadoPor;

    @Column(name = "desativado_em")
    private OffsetDateTime desativadoEm;

    /**
     * Por que foi desligado.
     *
     * <p>Existe porque desligar módulo tem motivos que mudam o que se faz depois: falta de
     * pagamento volta sozinho quando o cliente paga; cliente pediu para cancelar não volta.</p>
     */
    @Column(name = "motivo_desativacao", columnDefinition = "TEXT")
    private String motivoDesativacao;
}
