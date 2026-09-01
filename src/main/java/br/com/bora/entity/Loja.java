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

    /** Empresa dona da loja (mesmo CNPJ = mesma empresa). Fato societário, não controle de acesso. */
    @Column(name = "empresa_id")
    public Long empresaId;

    /** Quando a loja entrou na plataforma. NULL nas lojas anteriores à V31 sem rastro conhecido. */
    @Column(name = "criado_em")
    public java.time.OffsetDateTime criadoEm = java.time.OffsetDateTime.now();
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

    /** Taxa da plataforma retida por split no PIX online, em %. NULL = usa o padrão global
     *  (ASAAS_TAXA_PERCENTUAL). Fundadores ficam em 0 — foram vendidos sem taxa por pedido. */
    @Column(name = "split_percentual")
    public java.math.BigDecimal splitPercentual;

    /** Token que autentica o webhook de PIX registrado NA SUBCONTA do lojista.
     *  Sem ele o Asaas não consegue confirmar o pagamento. NUNCA serializar. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "asaas_webhook_token")
    public String asaasWebhookToken;

    // ---- Decisão do ADMINISTRADOR_BORA sobre o cliente (separada de "ativo", que é do webhook) ----
    /** Suspensão administrativa. Fica em campo próprio porque o webhook do Asaas escreve em
     *  "ativo": sem isso, o pagamento seguinte reativaria a loja e desfaria a decisão do admin. */
    @Column(name = "suspensa_pela_plataforma")
    public Boolean suspensaPelaPlataforma = false;

    @Column(name = "suspensa_em")
    public java.time.OffsetDateTime suspensaEm;

    @Column(name = "motivo_suspensao")
    public String motivoSuspensao;

    /** Arquivamento ("excluir cliente"): a loja some das listagens e do acesso, os dados ficam. */
    @Column(name = "excluida_em")
    public java.time.OffsetDateTime excluidaEm;

    /** Quem arquivou (usuarioId do administrador da plataforma) — auditoria mínima. */
    @Column(name = "excluida_por")
    public Long excluidaPor;

    @Column(name = "motivo_exclusao")
    public String motivoExclusao;

    /** Arquivada = "excluída" pelo administrador da plataforma (soft delete). */
    public boolean arquivada() {
        return excluidaEm != null;
    }

    /** Bloqueia login e cada request da equipe desta loja. Não confundir com "ativo": aquele é o
     *  status de pagamento (fecha só o cardápio público); este é a decisão da plataforma. */
    public boolean bloqueadaPelaPlataforma() {
        return Boolean.TRUE.equals(suspensaPelaPlataforma) || arquivada();
    }

    /** Preço efetivo da assinatura desta loja. */
    public java.math.BigDecimal precoEfetivo() {
        return precoMensal != null ? precoMensal
                : java.math.BigDecimal.valueOf((plano == null ? Plano.UNICO : plano).precoMensal);
    }
}
