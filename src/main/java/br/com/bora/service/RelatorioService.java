package br.com.bora.service;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.security.AuthContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/** Relatórios gerenciais agregados (faturamento, CMV/margem, por dia/canal/produto/entregador). */
@Service
public class RelatorioService {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private final PedidoRepository pedidos;
    private final PedidoItemRepository itens;
    private final AuthContext ctx;

    public RelatorioService(PedidoRepository pedidos, PedidoItemRepository itens, AuthContext ctx) {
        this.pedidos = pedidos;
        this.itens = itens;
        this.ctx = ctx;
    }

    public Map<String, Object> gerar(int dias) {
        Long lojaId = ctx.lojaId();
        int janela = dias <= 0 ? 30 : Math.min(dias, 365);
        OffsetDateTime corte = OffsetDateTime.now().minusDays(janela);

        List<Pedido> todos = pedidos.findByLojaIdOrderByCriadoEmDesc(lojaId).stream()
                .filter(p -> p.criadoEm != null && p.criadoEm.isAfter(corte)).toList();
        List<Pedido> vendas = todos.stream().filter(p -> p.status != StatusPedido.CANCELADO).toList();
        Set<Long> idsVenda = new HashSet<>(); vendas.forEach(p -> idsVenda.add(p.id));
        Map<Long, Pedido> porId = new HashMap<>(); todos.forEach(p -> porId.put(p.id, p));

        BigDecimal fat = soma(vendas.stream().map(p -> p.valorTotal));
        int qtdPed = vendas.size();
        int cancelados = todos.size() - vendas.size();
        BigDecimal ticket = qtdPed == 0 ? BigDecimal.ZERO : fat.divide(BigDecimal.valueOf(qtdPed), 2, RoundingMode.HALF_UP);

        // por dia (faturamento) — série dos últimos `janela` dias
        Map<String, BigDecimal> porDia = new LinkedHashMap<>();
        LocalDate hoje = LocalDate.now(ZONE);
        int serie = Math.min(janela, 30);
        for (int i = serie - 1; i >= 0; i--) porDia.put(hoje.minusDays(i).toString(), BigDecimal.ZERO);
        for (Pedido p : vendas) {
            String d = p.criadoEm.atZoneSameInstant(ZONE).toLocalDate().toString();
            if (porDia.containsKey(d)) porDia.merge(d, p.valorTotal == null ? BigDecimal.ZERO : p.valorTotal, BigDecimal::add);
        }

        // por canal
        Map<String, Map<String, Object>> porCanal = new LinkedHashMap<>();
        for (Pedido p : vendas) {
            String k = p.origem == null ? "Outros" : p.origem;
            var m = porCanal.computeIfAbsent(k, x -> novo());
            inc(m, "pedidos", 1); add(m, "faturamento", p.valorTotal);
        }

        // CMV + por produto (via itens dos pedidos de venda)
        BigDecimal cmv = BigDecimal.ZERO;
        Map<String, Map<String, Object>> porProduto = new LinkedHashMap<>();
        for (PedidoItem it : itens.findByLojaId(lojaId)) {
            if (!idsVenda.contains(it.getPedidoId())) continue;
            int q = it.getQuantidade() == null ? 1 : it.getQuantidade();
            BigDecimal custo = it.getCustoUnitario() == null ? BigDecimal.ZERO : it.getCustoUnitario().multiply(BigDecimal.valueOf(q));
            cmv = cmv.add(custo);
            var m = porProduto.computeIfAbsent(it.getDescricao() == null ? "—" : it.getDescricao(), x -> novo());
            inc(m, "quantidade", q);
            add(m, "faturamento", it.getSubtotal());
            add(m, "cmv", custo);
        }
        BigDecimal margem = fat.subtract(cmv);
        BigDecimal margemPct = fat.signum() == 0 ? BigDecimal.ZERO
                : margem.multiply(BigDecimal.valueOf(100)).divide(fat, 1, RoundingMode.HALF_UP);

        // por entregador (entregas concluídas)
        Map<String, Map<String, Object>> porEntregador = new LinkedHashMap<>();
        for (Pedido p : vendas) {
            if (p.entregador == null || p.entregador.isBlank()) continue;
            var m = porEntregador.computeIfAbsent(p.entregador, x -> novo());
            inc(m, "entregas", p.status == StatusPedido.ENTREGUE ? 1 : 0);
            add(m, "valor", p.valorTotal);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dias", janela);
        out.put("faturamento", fat);
        out.put("pedidos", qtdPed);
        out.put("ticketMedio", ticket);
        out.put("cancelados", cancelados);
        out.put("cmv", cmv);
        out.put("margem", margem);
        out.put("margemPct", margemPct);
        out.put("porDia", porDia);
        out.put("porCanal", porCanal);
        out.put("porProduto", porProduto);
        out.put("porEntregador", porEntregador);
        return out;
    }

    private Map<String, Object> novo() { Map<String, Object> m = new LinkedHashMap<>();
        m.put("pedidos", 0); m.put("quantidade", 0); m.put("entregas", 0);
        m.put("faturamento", BigDecimal.ZERO); m.put("cmv", BigDecimal.ZERO); m.put("valor", BigDecimal.ZERO); return m; }
    private void inc(Map<String, Object> m, String k, int v) { m.put(k, ((Number) m.getOrDefault(k, 0)).intValue() + v); }
    private void add(Map<String, Object> m, String k, BigDecimal v) { m.put(k, ((BigDecimal) m.getOrDefault(k, BigDecimal.ZERO)).add(v == null ? BigDecimal.ZERO : v)); }
    private BigDecimal soma(java.util.stream.Stream<BigDecimal> s) { return s.map(v -> v == null ? BigDecimal.ZERO : v).reduce(BigDecimal.ZERO, BigDecimal::add); }
}
