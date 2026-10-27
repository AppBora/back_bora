package br.com.bora.service;

import br.com.bora.repository.PedidoRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Termômetro de vendas: compara a receita das últimas 24h com a média das
 * mesmas janelas (mesmo dia da semana) nas últimas 4 semanas.
 *
 * <h2>A média é das semanas que EXISTIRAM (achado pelo QA em 26/10/2026)</h2>
 * <p>A soma das janelas era dividida por 4 sempre, mesmo quando a loja só tinha 2 semanas de vida. A
 * base saía pela <b>metade</b>, o índice (hoje ÷ base) saía pelo <b>dobro</b>, e o termômetro dizia
 * "vendas saudáveis" para uma loja em queda de verdade. Loja nova é exatamente a que mais precisa do
 * aviso.</p>
 *
 * <p>O divisor passou a ser o número de semanas que <b>já existiam</b> — contadas do primeiro pedido
 * da loja, não das que tiveram venda. A diferença importa nos dois sentidos: dividir pelas que
 * tiveram venda inflaria a base de quem legitimamente vendeu zero numa quarta-feira, o índice sairia
 * baixo, e o sistema abriria sozinho uma promoção relâmpago de 15% — desconto de verdade, em cima de
 * um número errado.</p>
 */
@Service
public class VendasSaudeService {

    private static final int SEMANAS_BASE = 4;
    private static final int MIN_SEMANAS_COM_DADOS = 2;
    private static final double LIMIAR_SAUDAVEL = 0.90;
    private static final double LIMIAR_ATENCAO = 0.70;

    private final PedidoRepository pedidos;

    public VendasSaudeService(PedidoRepository pedidos) {
        this.pedidos = pedidos;
    }

    public Map<String, Object> termometro(Long lojaId) {
        OffsetDateTime fim = OffsetDateTime.now();
        OffsetDateTime inicio = fim.minusHours(24);
        BigDecimal atual = pedidos.somaReceita(lojaId, inicio, fim);

        // Quando a loja comecou a vender. Semana anterior a isso nao e "semana sem venda": e semana
        // em que a loja nao existia, e nao pode entrar no divisor.
        OffsetDateTime primeiroPedido = pedidos.primeiroPedidoEm(lojaId);

        BigDecimal somaBase = BigDecimal.ZERO;
        int semanasComDados = 0;
        int semanasQueExistiram = 0;
        for (int s = 1; s <= SEMANAS_BASE; s++) {
            OffsetDateTime f = fim.minusDays(7L * s);
            OffsetDateTime i = f.minusHours(24);
            if (primeiroPedido == null || primeiroPedido.isAfter(f)) continue;
            semanasQueExistiram++;
            BigDecimal r = pedidos.somaReceita(lojaId, i, f);
            somaBase = somaBase.add(r);
            if (r.compareTo(BigDecimal.ZERO) > 0) semanasComDados++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("atual", atual);
        // O `semanasQueExistiram == 0` ja estaria coberto pelo `semanasComDados`, que nunca e maior
        // que ele -- mas escrito, porque e ele que vira divisor logo abaixo.
        boolean temDados = semanasComDados >= MIN_SEMANAS_COM_DADOS && semanasQueExistiram > 0;

        if (!temDados) {
            out.put("faixa", "SEM_DADOS");
            out.put("baseline", null);
            out.put("indice", null);
            out.put("recomendacao", "Histórico insuficiente para avaliar (precisa de ~2 semanas).");
            return out;
        }

        // Divide pelas semanas que existiram, nao por 4 fixo.
        BigDecimal baseline = somaBase.divide(BigDecimal.valueOf(semanasQueExistiram), 2, RoundingMode.HALF_UP);
        out.put("semanasNaBase", semanasQueExistiram);
        out.put("baseline", baseline);

        double indice = baseline.compareTo(BigDecimal.ZERO) > 0
                ? atual.doubleValue() / baseline.doubleValue() : 1.0;
        out.put("indice", BigDecimal.valueOf(indice).setScale(2, RoundingMode.HALF_UP));

        String faixa;
        String rec;
        if (indice >= LIMIAR_SAUDAVEL) { faixa = "SAUDAVEL"; rec = "Vendas saudáveis. Nada a fazer."; }
        else if (indice >= LIMIAR_ATENCAO) { faixa = "ATENCAO"; rec = "Vendas em queda leve. Considere uma promoção rápida."; }
        else { faixa = "QUEDA"; rec = "Queda relevante de vendas. Recomenda-se abrir uma promoção relâmpago agora."; }
        out.put("faixa", faixa);
        out.put("recomendacao", rec);
        return out;
    }

    public boolean emQueda(Long lojaId) {
        return "QUEDA".equals(termometro(lojaId).get("faixa"));
    }
}
