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

    public boolean valido() {
        return Boolean.TRUE.equals(ativo) && (validade == null || !validade.isBefore(LocalDate.now()));
    }

    public BigDecimal desconto(BigDecimal total) {
        BigDecimal d = "VALOR".equals(tipo) ? valor
                : total.multiply(valor).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        return d.min(total).max(BigDecimal.ZERO);
    }
}
