package br.com.bora.service;

import br.com.bora.entity.Cupom;
import br.com.bora.entity.Promocao;
import br.com.bora.repository.CupomRepository;
import br.com.bora.repository.PromocaoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Resolve um codigo de desconto digitado no checkout, seja ele cupom ou promocao.
 *
 * <p><b>O defeito que isto fecha (09/10/2026):</b> o checkout publico so consultava a tabela
 * {@code cupom}. A tela de Promocoes e o botao "promocao relampago" do Dashboard gravavam na tabela
 * {@code promocao}, que NINGUEM lia. O botao dizia, com estas palavras, "Abrir o cupom BORA15 (15% de
 * desconto, 3 horas) no seu cardapio agora?" — o lojista divulgava o codigo e o cliente recebia
 * "Cupom invalido ou vencido". O termometro de vendas ainda abria uma dessas sozinho, todo dia, para
 * toda loja em queda. Era um recurso vendido no site e inerte no sistema.</p>
 *
 * <p><b>Por que promocao nao virou simplesmente um cupom:</b> a promocao tem janela em HORAS
 * ({@code inicio}/{@code fim}) e teto de usos. Cupom tem validade por DIA e nenhum limite. Transformar
 * uma na outra perderia as duas protecoes — justamente as que o botao promete ao lojista.</p>
 *
 * <p>As duas consultas ficam aqui, num lugar so, porque o {@code PublicController} resolvia codigo em
 * dois pontos (a previa do checkout e a criacao do pedido) com o codigo duplicado.</p>
 */
@Slf4j
@Service
public class DescontoService {

    /** De onde o desconto veio, para o chamador saber se ha uso a contabilizar. */
    public record Desconto(String codigo, String tipo, BigDecimal valorConfigurado,
                           BigDecimal valorAbatido, Long promocaoId) {
        public boolean ehPromocao() { return promocaoId != null; }
    }

    private final CupomRepository cupons;
    private final PromocaoRepository promocoes;

    public DescontoService(CupomRepository cupons, PromocaoRepository promocoes) {
        this.cupons = cupons;
        this.promocoes = promocoes;
    }

    /**
     * Acha o desconto que este codigo vale para este total, ou vazio se o codigo nao serve.
     *
     * <p>Cupom primeiro, promocao depois: se a loja tiver os dois com o mesmo codigo, o cupom e o que
     * ela cadastrou a mao e tem precedencia.</p>
     */
    public Optional<Desconto> resolver(Long lojaId, String codigo, BigDecimal total) {
        if (codigo == null || codigo.isBlank() || total == null || total.signum() <= 0) return Optional.empty();
        String cod = codigo.trim();

        Optional<Cupom> cupom = cupons.findByLojaIdAndCodigoIgnoreCase(lojaId, cod).filter(Cupom::valido);
        if (cupom.isPresent()) {
            Cupom c = cupom.get();
            return Optional.of(new Desconto(c.codigo, c.tipo, c.valor, c.desconto(total), null));
        }

        return promocoes.findByLojaIdAndCodigoIgnoreCase(lojaId, cod)
                .filter(pr -> valeAgora(pr, OffsetDateTime.now()))
                .map(pr -> new Desconto(pr.getCodigo(), "PERCENTUAL", pr.getPercentualDesconto(),
                        abatimentoDaPromocao(pr, total), pr.getId()));
    }

    /**
     * A promocao esta valendo neste instante?
     *
     * <p>Confere ativa, a janela de tempo e o teto de usos. Sem o teto, o "ate 20 usos" que o botao do
     * Dashboard promete ao lojista nao existiria.</p>
     */
    public static boolean valeAgora(Promocao pr, OffsetDateTime agora) {
        if (!Boolean.TRUE.equals(pr.getAtiva())) return false;
        if (pr.getPercentualDesconto() == null || pr.getPercentualDesconto().signum() <= 0) return false;
        if (pr.getInicio() != null && agora.isBefore(pr.getInicio())) return false;
        if (pr.getFim() != null && !agora.isBefore(pr.getFim())) return false;
        if (pr.getLimiteUsos() != null && pr.getLimiteUsos() > 0) {
            int usados = pr.getUsos() == null ? 0 : pr.getUsos();
            if (usados >= pr.getLimiteUsos()) return false;
        }
        return true;
    }

    private static BigDecimal abatimentoDaPromocao(Promocao pr, BigDecimal total) {
        return total.multiply(pr.getPercentualDesconto())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .min(total)
                .max(BigDecimal.ZERO);
    }

    /**
     * Marca um uso da promocao, depois que o pedido foi criado de verdade.
     *
     * <p>Incremento condicional no banco, nao "le, soma e grava": com dez pedidos ao mesmo tempo, o
     * teto de 20 usos tem que ser 20, e todos liam o mesmo numero antes de qualquer um gravar. Quem
     * perder a corrida sabe pelo numero de linhas afetadas. Nao faz o pedido falhar: o cliente ja
     * recebeu o desconto, e o estouro do teto por uma unidade e problema do lojista, nao do cliente.</p>
     */
    public void contabilizarUso(Desconto d) {
        if (d == null || !d.ehPromocao()) return;
        int mexidas = promocoes.somarUmUso(d.promocaoId());
        if (mexidas == 0) {
            log.warn("Promocao {} ({}) estourou o limite de usos entre a validacao e a gravacao do pedido; "
                    + "o desconto foi concedido", d.promocaoId(), d.codigo());
        }
    }
}
