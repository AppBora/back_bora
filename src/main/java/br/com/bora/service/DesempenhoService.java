package br.com.bora.service;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.StatusPedido;
import br.com.bora.entity.UsuarioLoja;
import br.com.bora.repository.ConfiguracaoLojaRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.security.AuthContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Desempenho por loja: do faturamento até o lucro, descontando o que sai do caixa de verdade.
 *
 * <p>Uma tela só para dois públicos, porque a conta é a mesma e muda apenas o conjunto de lojas:
 * o ADMINISTRADOR_BORA vê todos os clientes; o dono de rede vê as unidades dele.</p>
 *
 * <p>De onde vem cada custo: CMV do custo unitário gravado em cada item vendido (custo real, não
 * estimativa); entrega da taxa repassada ao entregador; cashback do percentual da loja aplicado
 * sobre o faturamento; imposto e custo fixo do cadastro da loja. O custo fixo é rateado pelos dias
 * do período — consultar "esta semana" não pode debitar um mês inteiro de aluguel.</p>
 *
 * <p>Imposto e custo fixo não são obrigatórios: quem não preencheu vem com
 * {@code custosIncompletos = true}, e a tela avisa que ali o número é margem, não lucro.</p>
 */
@Service
public class DesempenhoService {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final BigDecimal DIAS_DO_MES = BigDecimal.valueOf(30);

    private final LojaRepository lojas;
    private final PedidoRepository pedidos;
    private final PedidoItemRepository itens;
    private final ConfiguracaoLojaRepository configs;
    private final UsuarioLojaRepository vinculos;
    private final AuthContext ctx;

    public DesempenhoService(LojaRepository lojas, PedidoRepository pedidos, PedidoItemRepository itens,
                             ConfiguracaoLojaRepository configs, UsuarioLojaRepository vinculos, AuthContext ctx) {
        this.lojas = lojas;
        this.pedidos = pedidos;
        this.itens = itens;
        this.configs = configs;
        this.vinculos = vinculos;
        this.ctx = ctx;
    }

    public Map<String, Object> consolidado(LocalDate inicio, LocalDate fim) {
        if (inicio == null) inicio = LocalDate.now(ZONE).withDayOfMonth(1);
        if (fim == null) fim = LocalDate.now(ZONE);
        OffsetDateTime ini = inicio.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime fimExc = fim.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        long dias = Math.max(1, ChronoUnit.DAYS.between(inicio, fim) + 1);

        List<Map<String, Object>> linhas = new ArrayList<>();
        for (Loja l : lojasDoUsuario()) {
            linhas.add(daLoja(l, ini, fimExc, dias));
        }
        linhas.sort((a, b) -> ((BigDecimal) b.get("faturamento")).compareTo((BigDecimal) a.get("faturamento")));

        Map<String, Object> total = new LinkedHashMap<>();
        for (String chave : List.of("faturamento", "cmv", "entrega", "cashback", "imposto", "custoFixo",
                "mensalidade", "custoTotal", "lucro")) {
            total.put(chave, linhas.stream().map(m -> (BigDecimal) m.get(chave))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        long qtdPedidos = linhas.stream().mapToLong(m -> (Long) m.get("pedidos")).sum();
        total.put("pedidos", qtdPedidos);
        total.put("ticketMedio", qtdPedidos == 0 ? BigDecimal.ZERO
                : ((BigDecimal) total.get("faturamento")).divide(BigDecimal.valueOf(qtdPedidos), 2, RoundingMode.HALF_UP));
        total.put("margemPct", pct((BigDecimal) total.get("lucro"), (BigDecimal) total.get("faturamento")));

        Map<String, Object> saida = new LinkedHashMap<>();
        saida.put("inicio", inicio.toString());
        saida.put("fim", fim.toString());
        saida.put("dias", dias);
        saida.put("escopo", ctx.isAdminBora() ? "PLATAFORMA" : "MINHAS_LOJAS");
        saida.put("lojas", linhas);
        saida.put("total", total);
        saida.put("custosIncompletos", linhas.stream().anyMatch(m -> Boolean.TRUE.equals(m.get("custosIncompletos"))));
        return saida;
    }

    /** Plataforma vê todos os clientes (menos arquivados); lojista vê as lojas a que tem vínculo. */
    private List<Loja> lojasDoUsuario() {
        if (ctx.isAdminBora()) {
            return lojas.findAll().stream().filter(l -> !l.arquivada()).toList();
        }
        ctx.requirePapel("ADMINISTRADOR_LOJA", "GERENTE");
        List<Loja> out = new ArrayList<>();
        for (UsuarioLoja v : vinculos.findByUsuarioId(ctx.atual().userId())) {
            lojas.findById(v.getLojaId()).ifPresent(out::add);
        }
        return out;
    }

    private Map<String, Object> daLoja(Loja l, OffsetDateTime ini, OffsetDateTime fim, long dias) {
        BigDecimal faturamento = zero(pedidos.somaReceita(l.id, ini, fim));
        long qtd = pedidos.contaPedidosValidos(l.id, ini, fim);

        List<Pedido> vendas = pedidos.findByLojaIdAndCriadoEmAfterOrderByCriadoEmDesc(l.id, ini).stream()
                .filter(p -> p.criadoEm != null && p.criadoEm.isBefore(fim) && p.status != StatusPedido.CANCELADO)
                .toList();

        BigDecimal entrega = vendas.stream().map(p -> zero(p.taxaEntrega)).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Long> ids = vendas.stream().map(p -> p.id).toList();
        BigDecimal cmv = BigDecimal.ZERO;
        if (!ids.isEmpty()) {
            for (PedidoItem it : itens.findByLojaIdAndPedidoIdIn(l.id, ids)) {
                int q = it.getQuantidade() == null ? 1 : it.getQuantidade();
                cmv = cmv.add(zero(it.getCustoUnitario()).multiply(BigDecimal.valueOf(q)));
            }
        }

        var cfg = configs.findByLojaId(l.id).orElse(null);
        BigDecimal pctCashback = cfg == null ? BigDecimal.ZERO : zero(cfg.cashbackPercentual);
        BigDecimal cashback = faturamento.multiply(pctCashback)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        BigDecimal aliquota = cfg == null ? null : cfg.aliquotaImposto;
        BigDecimal fixoMes = cfg == null ? null : cfg.custoFixoMensal;
        BigDecimal imposto = aliquota == null ? BigDecimal.ZERO
                : faturamento.multiply(aliquota).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal custoFixo = fixoMes == null ? BigDecimal.ZERO : rateio(fixoMes, dias);
        BigDecimal mensalidade = rateio(l.precoEfetivo(), dias);

        BigDecimal custoTotal = cmv.add(entrega).add(cashback).add(imposto).add(custoFixo).add(mensalidade);
        BigDecimal lucro = faturamento.subtract(custoTotal);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lojaId", l.id);
        m.put("loja", l.nome == null ? "Loja" : l.nome);
        m.put("pedidos", qtd);
        m.put("faturamento", faturamento);
        m.put("ticketMedio", qtd == 0 ? BigDecimal.ZERO
                : faturamento.divide(BigDecimal.valueOf(qtd), 2, RoundingMode.HALF_UP));
        m.put("cmv", cmv);
        m.put("entrega", entrega);
        m.put("cashback", cashback);
        m.put("imposto", imposto);
        m.put("custoFixo", custoFixo);
        m.put("mensalidade", mensalidade);
        m.put("custoTotal", custoTotal);
        m.put("lucro", lucro);
        m.put("margemPct", pct(lucro, faturamento));
        // Sem alíquota ou sem custo fixo, o número é margem e não lucro. A tela precisa dizer isso.
        m.put("custosIncompletos", aliquota == null || fixoMes == null);
        // Loja sem venda no período aparece com "prejuízo" só por causa do rateio de mensalidade e
        // custo fixo. É verdade contábil, mas numa lista longa engana — a tela separa esses casos.
        m.put("semMovimento", qtd == 0 && faturamento.signum() == 0);
        return m;
    }

    /** Custo mensal proporcional aos dias consultados. */
    private static BigDecimal rateio(BigDecimal mensal, long dias) {
        return zero(mensal).divide(DIAS_DO_MES, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(dias)).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal pct(BigDecimal parte, BigDecimal total) {
        return total.signum() == 0 ? BigDecimal.ZERO
                : parte.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }
}
