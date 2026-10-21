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

    /**
     * Quanto voltou para o cliente, numa devolução PARCIAL (item 3.6 da varredura, V57).
     *
     * <p>Nulo com {@link #estornadoEm} preenchido significa <b>estorno integral</b>: é a leitura
     * honesta para toda linha anterior à V57, porque era o único estado que o sistema sabia gravar.
     * Nulo com {@code estornadoEm} nulo é o normal: não houve devolução nenhuma.</p>
     */
    @Column(name = "estornado_valor")
    public java.math.BigDecimal estornadoValor;

    @Column(name = "criado_em")
    public java.time.OffsetDateTime criadoEm = java.time.OffsetDateTime.now();

    /** Dinheiro que voltou para o cliente não é faturamento e não pede nota. */
    public boolean estornado() {
        return estornadoEm != null;
    }

    /**
     * Quanto deste pagamento ainda é faturamento.
     *
     * <p>Integral devolvido: zero. Parcial: o valor menos o que voltou. Nada devolvido: o valor
     * cheio. Nunca negativo — devolução maior que a cobrança não vira receita negativa.</p>
     */
    public java.math.BigDecimal valorQueFicou() {
        java.math.BigDecimal cheio = valor == null ? java.math.BigDecimal.ZERO : valor;
        if (estornado()) return java.math.BigDecimal.ZERO;
        if (estornadoValor == null) return cheio;
        return cheio.subtract(estornadoValor).max(java.math.BigDecimal.ZERO);
    }

    /** Houve devolução parcial (o pagamento continua valendo, mas por menos)? */
    public boolean parcialmenteEstornado() {
        return !estornado() && estornadoValor != null && estornadoValor.signum() > 0;
    }

    public boolean temNota() {
        return notaNumero != null && !notaNumero.isBlank();
    }
}
