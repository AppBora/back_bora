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

    /** Preço mensal negociado (fundador etc.); NULL = tabela do plano. Só o ADMINISTRADOR_BORA define. */
    @Column(name = "preco_mensal")
    public java.math.BigDecimal precoMensal;

    // ---- Recebimento via subconta Asaas (PIX do cliente cai direto na conta do lojista) ----
    /** Id da subconta do lojista no Asaas (acc_...). */
    @Column(name = "asaas_subconta_id")
    public String asaasSubcontaId;

    /** walletId da subconta — usado no split para direcionar o recebimento ao lojista. */
    @Column(name = "asaas_wallet_id")
    public String asaasWalletId;

    /** API key da subconta (retornada uma única vez na criação) — usada para gerar as cobranças.
     *  NUNCA serializar: é credencial de pagamento viva. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "asaas_api_key")
    public String asaasApiKey;

    /** Situação do recebimento: null/PENDENTE (falta KYC) · ATIVO · ERRO. */
    @Column(name = "asaas_status")
    public String asaasStatus;

    /** Link de onboarding/KYC do Asaas (documentos + selfie) para o lojista concluir. */
    @Column(name = "asaas_onboarding_url")
    public String asaasOnboardingUrl;

    /** Token que autentica o webhook de PIX registrado NA SUBCONTA do lojista.
     *  Sem ele o Asaas não consegue confirmar o pagamento. NUNCA serializar. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "asaas_webhook_token")
    public String asaasWebhookToken;

    /** Preço efetivo da assinatura desta loja. */
    public java.math.BigDecimal precoEfetivo() {
        return precoMensal != null ? precoMensal
                : java.math.BigDecimal.valueOf((plano == null ? Plano.UNICO : plano).precoMensal);
    }
}
