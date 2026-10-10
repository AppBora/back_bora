package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Cupom de desconto do cardápio digital (PERCENTUAL sobre o total ou VALOR fixo). */
@Entity
@Getter
@Setter
public class Cupom {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "loja_id")
    public Long lojaId;
    public String codigo;
    public String tipo = "PERCENTUAL";
    public BigDecimal valor;
    public Boolean ativo = true;
    public LocalDate validade;

    /**
     * Fuso de Brasilia, explicito.
     *
     * <p>O container roda em UTC. Com {@code LocalDate.now()} sem fuso, o cupom com validade "hoje"
     * deixava de valer as 21h de Brasilia — dentro do horario de jantar, que e o pico da loja. O
     * lojista divulgava o cupom valido ate hoje e o cliente levava "cupom vencido" na hora do pedido.</p>
     */
    private static final java.time.ZoneId ZONE = java.time.ZoneId.of("America/Sao_Paulo");

    public boolean valido() {
        return Boolean.TRUE.equals(ativo) && (validade == null || !validade.isBefore(LocalDate.now(ZONE)));
    }

    public BigDecimal desconto(BigDecimal total) {
        BigDecimal d = "VALOR".equals(tipo) ? valor
                : total.multiply(valor).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        return d.min(total).max(BigDecimal.ZERO);
    }
}
