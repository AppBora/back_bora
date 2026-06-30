package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

/** Conexão de uma loja com um marketplace (iFood, 99Food, Rappi, Uber Eats…). */
@Entity
@Table(name = "integracao_canal", uniqueConstraints = @UniqueConstraint(columnNames = {"loja_id", "canal"}))
@Getter
@Setter
public class IntegracaoCanal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "loja_id")
    public Long lojaId;

    public String canal;          // código: IFOOD, NOVE_NOVE, RAPPI, UBER_EATS, AIQFOME, GOOMER...
    public Boolean ativo = false;

    @Column(name = "merchant_id")
    public String merchantId;     // id do estabelecimento no marketplace
    @Column(name = "client_id")
    public String clientId;
    @Column(name = "client_secret")
    public String clientSecret;   // credencial (token/secret) — preenchida pelo lojista

    @Column(name = "webhook_token")
    public String webhookToken;   // segredo que valida os webhooks de entrada

    @Column(name = "auto_aceitar")
    public Boolean autoAceitar = true;

    public String status = "DESCONECTADO";   // DESCONECTADO | PRONTO | CONECTADO | ERRO
    @Column(name = "ultima_sync")
    public OffsetDateTime ultimaSync;
    @Column(name = "pedidos_recebidos")
    public Integer pedidosRecebidos = 0;
}
