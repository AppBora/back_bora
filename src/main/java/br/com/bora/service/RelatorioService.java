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
    private final br.com.bora.repository.UsuarioLojaRepository vinculos;
    private final br.com.bora.repository.LojaRepository lojas;
    private final AuthContext ctx;

    public RelatorioService(PedidoRepository pedidos, PedidoItemRepository itens,
                            br.com.bora.repository.UsuarioLojaRepository vinculos,
                            br.com.bora.repository.LojaRepository lojas, AuthContext ctx) {
        this.pedidos = pedidos;
        this.itens = itens;
        this.vinculos = vinculos;
        this.lojas = lojas;
        this.ctx = ctx;
    }

    /**
     * Loja do relatório: a do token, ou outra da rede quando o dono pede.
     *
     * <p>Sem isto, ver o relatório de outra unidade obriga a trocar o contexto inteiro do painel.
     * A permissão continua a mesma da troca de loja: vínculo do usuário (ou plataforma).</p>
     */
    private Long lojaDoRelatorio(Long pedida) {
        if (pedida == null || pedida.equals(ctx.lojaIdOuNulo())) return ctx.lojaId();
        if (!ctx.isAdminBora() && !vinculos.existsByUsuarioIdAndLojaId(ctx.atual().userId(), pedida)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Você não tem vínculo com essa loja");
        }
        return pedida;
    }

    public Map<String, Object> gerar(int dias, Long lojaPedida) {
        // Faturamento, CMV, margem e lucro sao numeros de dono, nao de balcao: sem esta linha qualquer
        // usuario da loja (atendente, entregador) via o resultado financeiro. As telas irmas
        // (DesempenhoService, RedeService.balancete) ja exigiam o mesmo.
        ctx.requirePapel("ADMINISTRADOR_LOJA", "GERENTE");
        Long lojaId = lojaDoRelatorio(lojaPedida);
        int janela = dias <= 0 ? 30 : Math.min(dias, 365);
        // A JANELA E DE DIAS INTEIROS, no fuso da loja -- e isto era metade do defeito do grafico
        // (achado pelo QA em 26/10/2026).
        //
        // Era `now().minusDays(janela)`, um corte no INSTANTE: as 14h de hoje, a janela de 30 dias
        // comecava as 14h de 30 dias atras. O grafico, por outro lado, monta as barras por DATA do
        // calendario de Sao Paulo. As vendas entre o corte e a meia-noite seguinte entravam no
        // faturamento do cabecalho e nao tinham barra nenhuma para cair: a soma das barras dava
        // menos que o numero grande, e o lojista nao tinha como saber de onde vinha a diferenca.
        //
        // "Ultimos 30 dias" para quem le a tela sao 30 dias do calendario, nao 30 vezes 24 horas.
        LocalDate hoje = LocalDate.now(ZONE);
        LocalDate primeiroDia = hoje.minusDays(janela - 1L);
        OffsetDateTime corte = primeiroDia.atStartOfDay(ZONE).toOffsetDateTime();

        List<Pedido> todos = pedidos.findByLojaIdAndCriadoEmAfterOrderByCriadoEmDesc(lojaId, corte);
        // Venda e o que foi vendido de verdade: pedido com PIX gerado e nao pago inflava o
        // faturamento e a margem, e nunca saia do relatorio porque ninguem o cancelava.
        List<Pedido> vendas = todos.stream()
                .filter(p -> p.status != StatusPedido.CANCELADO)
                .filter(p -> !p.pagamentoPendente())
                .toList();
        Set<Long> idsVenda = new HashSet<>(); vendas.forEach(p -> idsVenda.add(p.id));
        Map<Long, Pedido> porId = new HashMap<>(); todos.forEach(p -> porId.put(p.id, p));

        String nomeLoja = lojas.findById(lojaId).map(l -> l.getNome()).orElse(null);
        BigDecimal fat = soma(vendas.stream().map(p -> p.valorTotal));
        int qtdPed = vendas.size();
        // Cancelado e o pedido CANCELADO, nada mais. Isto era `todos.size() - vendas.size()`, e como
        // `vendas` tambem exclui o PIX gerado e nao pago, todo checkout esperando pagamento aparecia na
        // tela como cancelamento. Numa loja com PIX ligado isso inflava o numero todos os dias, e o
        // lojista achava que estava perdendo venda que na verdade ainda podia entrar.
        int cancelados = (int) todos.stream().filter(p -> p.status == StatusPedido.CANCELADO).count();
        // Os que ficaram de fora do faturamento por falta de pagamento sao outra coisa, e o lojista
        // quer saber: ainda podem virar venda.
        int aguardandoPagamento = (int) todos.stream()
                .filter(p -> p.status != StatusPedido.CANCELADO)
                .filter(Pedido::pagamentoPendente)
                .count();
        BigDecimal ticket = qtdPed == 0 ? BigDecimal.ZERO : fat.divide(BigDecimal.valueOf(qtdPed), 2, RoundingMode.HALF_UP);
        // ITEM 5.8 DA VARREDURA: quanto deste faturamento e taxa de entrega que o MARKETPLACE ficou.
        //
        // O `faturamento` acima e o bruto que o cliente pagou, e continua sendo -- descontar a taxa
        // mudaria um numero que o dono olha todo dia, e isso e decisao dele. O que faltava era poder
        // VER o tamanho: a informacao existia so como texto na observacao ("Entrega pelo iFood"), que
        // nenhuma conta alcanca.
        //
        // So conta quando `entregaPeloCanal` e TRUE. Nulo e "nao informado" (pedido de balcao, ou
        // anterior a V58) e nao pode virar taxa de ninguem.
        BigDecimal taxaDoCanal = soma(vendas.stream()
                .filter(p -> Boolean.TRUE.equals(p.entregaPeloCanal))
                .map(p -> p.taxaEntrega));

        // POR DIA: uma barra para CADA dia da janela, e era a outra metade do defeito.
        //
        // A serie era cortada em 30 dias (`min(janela, 30)`) enquanto o faturamento somava a janela
        // inteira. A tela oferece o botao de 90 dias: o cabecalho dizia "ultimos 90 dias" e o
        // grafico mostrava, calado, so os ultimos 30. Dois numeros da mesma tela que nao fechavam.
        //
        // Agora a soma das barras e, por construcao, igual ao faturamento. Quem agrupa para caber na
        // tela e o grafico (por semana, quando sao muitos dias) -- decisao de desenho, feita com
        // todos os dados na mao, e nao um pedaco do periodo jogado fora aqui.
        Map<String, BigDecimal> porDia = new LinkedHashMap<>();
        for (int i = janela - 1; i >= 0; i--) porDia.put(hoje.minusDays(i).toString(), BigDecimal.ZERO);
        BigDecimal somaDasBarras = BigDecimal.ZERO;
        for (Pedido p : vendas) {
            String d = p.criadoEm.atZoneSameInstant(ZONE).toLocalDate().toString();
            BigDecimal v = p.valorTotal == null ? BigDecimal.ZERO : p.valorTotal;
            if (porDia.containsKey(d)) {
                porDia.merge(d, v, BigDecimal::add);
                somaDasBarras = somaDasBarras.add(v);
            }
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
        List<PedidoItem> itensVenda = idsVenda.isEmpty() ? List.of()
                : itens.findByLojaIdAndPedidoIdIn(lojaId, idsVenda);
        int itensSemCusto = 0;
        for (PedidoItem it : itensVenda) {
            int q = it.getQuantidade() == null ? 1 : it.getQuantidade();
            // Custo nulo e "nao cadastrado", nao "de graca" -- e aqui ele entra como ZERO, porque nao
            // ha nada melhor a fazer com ele. O que faltava era CONTAR, para a tela poder dizer que o
            // CMV sai menor do que e e a margem maior (achado pelo QA em 26/10/2026).
            //
            // MEDIDO NA PRODUCAO em 26/10/2026, somente leitura: 26 dos 48 itens de pedido estao sem
            // custo unitario. A margem que o lojista le hoje esta maior do que a real, e nada na tela
            // diz isso.
            if (it.getCustoUnitario() == null) itensSemCusto++;
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
        out.put("lojaId", lojaId);
        out.put("loja", nomeLoja);
        out.put("faturamento", fat);
        // Item 5.8: ao lado do faturamento, nao dentro dele.
        out.put("taxaDoMarketplace", taxaDoCanal);
        out.put("pedidos", qtdPed);
        out.put("ticketMedio", ticket);
        out.put("cancelados", cancelados);
        out.put("aguardandoPagamento", aguardandoPagamento);
        out.put("cmv", cmv);
        out.put("margem", margem);
        out.put("margemPct", margemPct);
        // Quantos itens vendidos no periodo nao tem custo cadastrado. Com qualquer um deles, o CMV
        // sai MENOR do que e e a margem MAIOR -- a tela avisa, igual ao `custosIncompletos` do
        // Desempenho, que ja faz isso para imposto e custo fixo.
        out.put("itensSemCusto", itensSemCusto);
        out.put("itensVendidos", itensVenda.size());
        out.put("custosIncompletos", itensSemCusto > 0);
        out.put("porDia", porDia);
        // O periodo exato, em datas: a tela dizia so "ultimos N dias", e N dias contados de quando
        // era justamente a duvida de quem comparava os dois numeros.
        out.put("de", primeiroDia.toString());
        out.put("ate", hoje.toString());
        // A soma das barras. Igual ao faturamento por construcao -- esta aqui para a tela poder
        // conferir em vez de confiar, e para o teste nao depender de eu somar certo.
        out.put("porDiaSoma", somaDasBarras);
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
