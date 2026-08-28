package br.com.bora.service;

import br.com.bora.entity.LogStatus;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.StatusPedido;
import br.com.bora.entity.UsuarioLoja;
import br.com.bora.repository.LogStatusRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Análise da rede (multi-loja) para os painéis de gestão:
 * canais + comissão estimada + ranking de produtos, mapa de horário de pico,
 * e tempo médio por status + cancelamentos. Sempre escopado às lojas do usuário.
 */
@Service
public class AnaliseRedeService {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    /** Comissão média estimada por canal (marketplaces). Canais próprios = 0. */
    private static final Map<String, BigDecimal> COMISSAO = Map.of(
            "iFood",     new BigDecimal("0.27"),
            "99Food",    new BigDecimal("0.23"),
            "Rappi",     new BigDecimal("0.25"),
            "Uber Eats", new BigDecimal("0.30"),
            "Goomer",    new BigDecimal("0.12"),
            "aiqfome",   new BigDecimal("0.18")
    );

    private final UsuarioLojaRepository vinculos;
    private final LojaRepository lojas;
    private final PedidoRepository pedidos;
    private final PedidoItemRepository itens;
    private final LogStatusRepository logs;
    private final AuthContext ctx;

    public AnaliseRedeService(UsuarioLojaRepository vinculos, LojaRepository lojas, PedidoRepository pedidos,
                              PedidoItemRepository itens, LogStatusRepository logs, AuthContext ctx) {
        this.vinculos = vinculos;
        this.lojas = lojas;
        this.pedidos = pedidos;
        this.itens = itens;
        this.logs = logs;
        this.ctx = ctx;
    }

    // ----------------------------------------------------------------- helpers

    private record Janela(LocalDate inicio, LocalDate fim, OffsetDateTime ini, OffsetDateTime fimExc) {}

    private Janela janela(LocalDate inicio, LocalDate fim) {
        ctx.requirePapel("ADMINISTRADOR_LOJA", "GERENTE");
        if (inicio == null) inicio = LocalDate.now(ZONE).withDayOfMonth(1);
        if (fim == null) fim = LocalDate.now(ZONE);
        if (fim.isBefore(inicio)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data final anterior à inicial");
        }
        return new Janela(inicio, fim,
                inicio.atStartOfDay(ZONE).toOffsetDateTime(),
                fim.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
    }

    /** Ids das lojas vinculadas ao usuário logado (a rede dele). */
    private List<Loja> minhasLojas() {
        List<Loja> out = new ArrayList<>();
        for (UsuarioLoja v : vinculos.findByUsuarioId(ctx.atual().userId())) {
            lojas.findById(v.getLojaId()).ifPresent(out::add);
        }
        return out;
    }

    /** Normaliza a origem/canal do pedido para um rótulo amigável (mesma lógica do front). */
    private String canalDe(Pedido p) {
        String o = ((p.origem != null ? p.origem : "") + " " + (p.canalExterno != null ? p.canalExterno : "")).toUpperCase();
        if (o.contains("IFOOD") || o.contains("I-FOOD")) return "iFood";
        if (o.contains("99")) return "99Food";
        if (o.contains("RAPPI")) return "Rappi";
        if (o.contains("UBER")) return "Uber Eats";
        if (o.contains("GOOMER")) return "Goomer";
        if (o.contains("AIQ")) return "aiqfome";
        if (o.contains("WHATS") || o.contains("ZAP")) return "WhatsApp";
        if (o.contains("INSTA") || o.contains(" IG")) return "Instagram";
        if (o.contains("FONE") || o.contains("TELEF")) return "Telefone";
        if (o.contains("SITE") || o.contains("CARDAP") || o.contains("WEB")) return "Cardápio próprio";
        if (o.contains("BALC") || o.contains("LOJA") || o.contains("CAIXA") || o.contains("PDV")) return "Balcão";
        if (o.isBlank()) return "Não informado";
        return "Delivery";
    }

    private boolean marketplace(String canal) {
        return COMISSAO.containsKey(canal);
    }

    /** Pedidos válidos (não cancelados) da rede na janela. */
    private List<Pedido> pedidosValidos(List<Loja> rede, Janela j) {
        List<Pedido> out = new ArrayList<>();
        for (Loja l : rede) {
            for (Pedido p : pedidos.findByLojaIdAndCriadoEmAfterOrderByCriadoEmDesc(l.getId(), j.ini())) {
                if (p.criadoEm.isBefore(j.fimExc()) && p.status != StatusPedido.CANCELADO) out.add(p);
            }
        }
        return out;
    }

    private BigDecimal val(Pedido p) { return p.valorTotal == null ? BigDecimal.ZERO : p.valorTotal; }

    // ------------------------------------------------------------- 1) canais

    public Map<String, Object> canais(LocalDate inicio, LocalDate fim) {
        List<Loja> rede = minhasLojas();
        Janela j = janela(inicio, fim);
        List<Pedido> vendas = pedidosValidos(rede, j);

        Map<String, long[]> pedPorCanal = new LinkedHashMap<>();   // canal -> [qtd]
        Map<String, BigDecimal> fatPorCanal = new LinkedHashMap<>();
        BigDecimal fatTotal = BigDecimal.ZERO;
        BigDecimal comissaoTotal = BigDecimal.ZERO;

        for (Pedido p : vendas) {
            String c = canalDe(p);
            pedPorCanal.computeIfAbsent(c, x -> new long[1])[0]++;
            fatPorCanal.merge(c, val(p), BigDecimal::add);
            fatTotal = fatTotal.add(val(p));
            if (marketplace(c)) comissaoTotal = comissaoTotal.add(val(p).multiply(COMISSAO.get(c)));
        }

        List<Map<String, Object>> canaisOut = new ArrayList<>();
        for (String c : fatPorCanal.keySet()) {
            BigDecimal fat = fatPorCanal.get(c);
            BigDecimal taxa = COMISSAO.getOrDefault(c, BigDecimal.ZERO);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("canal", c);
            m.put("pedidos", pedPorCanal.get(c)[0]);
            m.put("faturamento", fat);
            m.put("percentual", pct(fat, fatTotal));
            m.put("marketplace", marketplace(c));
            m.put("taxaComissao", taxa.multiply(BigDecimal.valueOf(100)));
            m.put("comissaoEstimada", fat.multiply(taxa).setScale(2, RoundingMode.HALF_UP));
            canaisOut.add(m);
        }
        canaisOut.sort((a, b) -> ((BigDecimal) b.get("faturamento")).compareTo((BigDecimal) a.get("faturamento")));

        // ranking de produtos (por itens dos pedidos válidos)
        Set<Long> idsVenda = new HashSet<>();
        Map<Long, Long> lojaDoPedido = new HashMap<>();
        for (Pedido p : vendas) { idsVenda.add(p.id); lojaDoPedido.put(p.id, p.lojaId); }
        Map<String, long[]> qtdProduto = new HashMap<>();
        Map<String, BigDecimal> fatProduto = new HashMap<>();
        for (Loja l : rede) {
            List<Long> ids = idsVenda.stream().filter(id -> l.getId().equals(lojaDoPedido.get(id))).toList();
            if (ids.isEmpty()) continue;
            for (PedidoItem it : itens.findByLojaIdAndPedidoIdIn(l.getId(), ids)) {
                String nome = it.getDescricao() == null || it.getDescricao().isBlank() ? "—" : it.getDescricao();
                int q = it.getQuantidade() == null ? 1 : it.getQuantidade();
                qtdProduto.computeIfAbsent(nome, x -> new long[1])[0] += q;
                fatProduto.merge(nome, it.getSubtotal() == null ? BigDecimal.ZERO : it.getSubtotal(), BigDecimal::add);
            }
        }
        List<Map<String, Object>> produtos = new ArrayList<>();
        for (String nome : qtdProduto.keySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("produto", nome);
            m.put("quantidade", qtdProduto.get(nome)[0]);
            m.put("faturamento", fatProduto.getOrDefault(nome, BigDecimal.ZERO));
            produtos.add(m);
        }
        produtos.sort((a, b) -> Long.compare(((Number) b.get("quantidade")).longValue(), ((Number) a.get("quantidade")).longValue()));
        List<Map<String, Object>> mais = produtos.stream().limit(8).toList();
        List<Map<String, Object>> menos = new ArrayList<>(produtos);
        Collections.reverse(menos);
        menos = menos.stream().limit(8).toList();

        Map<String, Object> out = base(j);
        out.put("canais", canaisOut);
        out.put("faturamentoTotal", fatTotal);
        out.put("comissaoTotal", comissaoTotal.setScale(2, RoundingMode.HALF_UP));
        out.put("faturamentoLiquido", fatTotal.subtract(comissaoTotal).setScale(2, RoundingMode.HALF_UP));
        out.put("produtosMais", mais);
        out.put("produtosMenos", menos);
        return out;
    }

    // ------------------------------------------------------------- 2) horário

    public Map<String, Object> horario(LocalDate inicio, LocalDate fim) {
        List<Loja> rede = minhasLojas();
        Janela j = janela(inicio, fim);
        List<Pedido> vendas = pedidosValidos(rede, j);

        // faixas de 0..23; separa dias úteis (seg-sex) x fim de semana
        long[] utilQtd = new long[24];    BigDecimal[] utilFat = zeros(24);
        long[] fdsQtd = new long[24];     BigDecimal[] fdsFat = zeros(24);

        for (Pedido p : vendas) {
            var dt = p.criadoEm.atZoneSameInstant(ZONE);
            int h = dt.getHour();
            int dow = dt.getDayOfWeek().getValue(); // 1=seg .. 7=dom
            if (dow >= 6) { fdsQtd[h]++; fdsFat[h] = fdsFat[h].add(val(p)); }
            else { utilQtd[h]++; utilFat[h] = utilFat[h].add(val(p)); }
        }

        Map<String, Object> out = base(j);
        out.put("diasUteis", faixas(utilQtd, utilFat));
        out.put("fimSemana", faixas(fdsQtd, fdsFat));
        out.put("pico", pico(utilQtd, fdsQtd));
        return out;
    }

    private List<Map<String, Object>> faixas(long[] qtd, BigDecimal[] fat) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hora", h);
            m.put("pedidos", qtd[h]);
            m.put("faturamento", fat[h]);
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> pico(long[] util, long[] fds) {
        int hUtil = 0, hFds = 0;
        for (int h = 0; h < 24; h++) {
            if (util[h] > util[hUtil]) hUtil = h;
            if (fds[h] > fds[hFds]) hFds = h;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("horaPicoUteis", hUtil);
        m.put("horaPicoFimSemana", hFds);
        return m;
    }

    // ------------------------------------------------------------- 3) tempos + cancelamentos

    public Map<String, Object> tempos(LocalDate inicio, LocalDate fim) {
        List<Loja> rede = minhasLojas();
        Janela j = janela(inicio, fim);

        // tempo médio em cada status (a partir do log de mudança de status)
        Map<String, long[]> acc = new LinkedHashMap<>(); // status -> [somaSegundos, contagem]
        for (Loja l : rede) {
            Map<Long, OffsetDateTime> criadoEm = new HashMap<>();
            for (Pedido p : pedidos.findByLojaIdAndCriadoEmAfterOrderByCriadoEmDesc(l.getId(), j.ini())) {
                if (p.criadoEm.isBefore(j.fimExc())) criadoEm.put(p.id, p.criadoEm);
            }
            var todosLogs = logs.findByLojaIdAndDataHoraGreaterThanEqualOrderByPedidoIdAscDataHoraAsc(l.getId(), j.ini());
            // agrupa por pedido
            Map<Long, List<LogStatus>> porPedido = new LinkedHashMap<>();
            for (LogStatus ls : todosLogs) {
                if (!criadoEm.containsKey(ls.getPedidoId())) continue;
                porPedido.computeIfAbsent(ls.getPedidoId(), x -> new ArrayList<>()).add(ls);
            }
            for (var e : porPedido.entrySet()) {
                OffsetDateTime prev = criadoEm.get(e.getKey());
                for (LogStatus ls : e.getValue()) {
                    String saindoDe = ls.getStatusAnterior() == null ? "RECEBIDO" : ls.getStatusAnterior();
                    long secs = Math.max(0, ChronoUnit.SECONDS.between(prev, ls.getDataHora()));
                    long[] a = acc.computeIfAbsent(saindoDe, x -> new long[2]);
                    a[0] += secs; a[1]++;
                    prev = ls.getDataHora();
                }
            }
        }
        String[] ordem = {"RECEBIDO", "CONFIRMADO", "EM_PREPARO", "PRONTO", "SAIU_PARA_ENTREGA"};
        List<Map<String, Object>> tempos = new ArrayList<>();
        for (String st : ordem) {
            long[] a = acc.get(st);
            long qtd = a == null ? 0 : a[1];
            double minutos = qtd == 0 ? 0 : (a[0] / (double) qtd) / 60.0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", st);
            m.put("amostras", qtd);
            m.put("minutosMedio", BigDecimal.valueOf(minutos).setScale(1, RoundingMode.HALF_UP));
            tempos.add(m);
        }

        // cancelamentos por motivo e por loja
        Map<String, Long> porMotivo = new LinkedHashMap<>();
        List<Map<String, Object>> porLoja = new ArrayList<>();
        long cancelTotal = 0, pedidoTotal = 0;
        for (Loja l : rede) {
            long canc = 0, tot = 0;
            for (Pedido p : pedidos.findByLojaIdAndCriadoEmAfterOrderByCriadoEmDesc(l.getId(), j.ini())) {
                if (p.criadoEm.isBefore(j.fimExc())) {
                    tot++;
                    if (p.status == StatusPedido.CANCELADO) {
                        canc++;
                        String mv = p.motivoCancelamento == null || p.motivoCancelamento.isBlank()
                                ? "Sem motivo informado" : p.motivoCancelamento.trim();
                        porMotivo.merge(mv, 1L, Long::sum);
                    }
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("loja", l.getNome());
            m.put("cancelados", canc);
            m.put("pedidos", tot);
            m.put("percentual", tot == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(canc * 100.0 / tot).setScale(1, RoundingMode.HALF_UP));
            porLoja.add(m);
            cancelTotal += canc; pedidoTotal += tot;
        }
        porLoja.sort((a, b) -> Long.compare(((Number) b.get("cancelados")).longValue(), ((Number) a.get("cancelados")).longValue()));
        List<Map<String, Object>> motivos = new ArrayList<>();
        porMotivo.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(en -> { Map<String, Object> m = new LinkedHashMap<>();
                    m.put("motivo", en.getKey()); m.put("qtd", en.getValue()); motivos.add(m); });

        Map<String, Object> out = base(j);
        out.put("tempos", tempos);
        out.put("cancelamentos", Map.of(
                "total", cancelTotal,
                "pedidos", pedidoTotal,
                "percentual", pedidoTotal == 0 ? BigDecimal.ZERO
                        : BigDecimal.valueOf(cancelTotal * 100.0 / pedidoTotal).setScale(1, RoundingMode.HALF_UP),
                "porMotivo", motivos,
                "porLoja", porLoja));
        return out;
    }

    // ----------------------------------------------------------------- util

    private Map<String, Object> base(Janela j) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inicio", j.inicio().toString());
        out.put("fim", j.fim().toString());
        return out;
    }

    private BigDecimal pct(BigDecimal parte, BigDecimal total) {
        if (total == null || total.signum() == 0) return BigDecimal.ZERO;
        return parte.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }

    private BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }
}
