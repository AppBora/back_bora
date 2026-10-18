package br.com.bora.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * Alguém esqueceu a senha e pediu ajuda ao administrador da loja.
 *
 * <p>Não há envio de e-mail no sistema, e o envio automático por WhatsApp depende da Cloud API da
 * Meta — que, medido em 18/10/2026, não funciona: a credencial da loja 1 venceu em 08/07 e a da loja
 * 18 é um texto de 9 caracteres. Então o pedido fica gravado e aparece na tela de Usuários, que é
 * onde o administrador redefine a senha. Funciona sem Meta, sem e-mail e sem custo.</p>
 *
 * <p>Não guarda o e-mail digitado de propósito: o endpoint é público, responde sempre igual exista o
 * usuário ou não, e grava linha só quando o usuário existe. Guardar o que foi digitado transformaria
 * a tabela num depósito de tentativas — e a tela de login, num verificador de e-mail.</p>
 */
@Entity
@Table(name = "pedido_de_senha")
public class PedidoDeSenha {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "usuario_id", nullable = false)
    public Long usuarioId;

    @Column(name = "loja_id")
    public Long lojaId;

    @Column(name = "criado_em")
    public OffsetDateTime criadoEm = OffsetDateTime.now();

    /** Quando o administrador redefiniu a senha dessa pessoa. Nulo = ainda esperando. */
    @Column(name = "atendido_em")
    public OffsetDateTime atendidoEm;

    /** Quando o aviso por WhatsApp saiu de fato. Nulo = não saiu. */
    @Column(name = "avisado_em")
    public OffsetDateTime avisadoEm;
}
