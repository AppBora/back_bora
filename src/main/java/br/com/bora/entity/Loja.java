package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class Loja {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String nome;
    public String documento;
    public Boolean ativo = true;

    @Enumerated(EnumType.STRING)
    public Plano plano = Plano.UNICO;

    /** Add-on pago "Módulo IA" — liberado somente pelo ADMINISTRADOR_BORA quando contratado. */
    @Column(name = "modulo_ia")
    public Boolean moduloIa = false;

    /** WhatsApp do dono — destino do resumo diário do Gerente Virtual. */
    @Column(name = "whatsapp_dono")
    public String whatsappDono;
}
