package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Uma mensalidade do BoraHapp efetivamente recebida.
 *
 * <p>Até aqui o pagamento não deixava rastro: o webhook do Asaas virava um status "ATIVA" e pronto.
 * Não dava para responder "quanto entrou em outubro e de quem", nem havia onde guardar o número da
 * nota fiscal de cada mensalidade — que é o que o cliente com CNPJ pede.</p>
 */
@Entity
@Table(name = "pagamento_assinatura")
@Getter
@Setter
public class PagamentoAssinatura {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "loja_id")
    public Long lojaId;

    @Column(name = "assinatura_id")
    public Long assinaturaId;

    /** Id da cobrança no Asaas. Único: o webhook é reenviado e não pode contar duas vezes. */
    @Column(name = "asaas_payment_id")
    public String asaasPaymentId;

    @Column(precision = 12, scale = 2)
    public java.math.BigDecimal valor;

    @Column(name = "pago_em")
    public java.time.OffsetDateTime pagoEm;

    public String descricao;

    // ---- Nota fiscal desta mensalidade ----
    @Column(name = "nota_numero")
    public String notaNumero;

    @Column(name = "nota_url")
    public String notaUrl;

    @Column(name = "nota_emitida_em")
    public java.time.OffsetDateTime notaEmitidaEm;

    /** Quando o valor foi devolvido ao cliente (garantia, chargeback). NULL = o dinheiro ficou. */
    @Column(name = "estornado_em")
    public java.time.OffsetDateTime estornadoEm;

    @Column(name = "criado_em")
    public java.time.OffsetDateTime criadoEm = java.time.OffsetDateTime.now();

    /** Dinheiro que voltou para o cliente não é faturamento e não pede nota. */
    public boolean estornado() {
        return estornadoEm != null;
    }

    public boolean temNota() {
        return notaNumero != null && !notaNumero.isBlank();
    }
}
