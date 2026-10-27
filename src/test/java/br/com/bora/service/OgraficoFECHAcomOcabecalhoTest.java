package br.com.bora.service;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O grafico por dia fecha com o faturamento do cabecalho, e o CMV sem custo avisa.
 *
 * <h2>Defeito 1: a janela era em instantes, o grafico em datas</h2>
 * <p>O corte era {@code now().minusDays(janela)}. As 14h, a janela de 30 dias comecava as 14h de 30
 * dias atras — mas o grafico monta as barras por DATA do calendario de Sao Paulo. As vendas entre o
 * corte e a meia-noite seguinte entravam no faturamento e <b>nao tinham barra para cair</b>: a soma
 * das barras dava menos que o numero grande, e nada na tela explicava a diferenca.</p>
 *
 * <h2>Defeito 2: a serie era cortada em 30 dias</h2>
 * <p>{@code min(janela, 30)}, enquanto o faturamento somava a janela inteira. A tela oferece o botao
 * de <b>90 dias</b>: o cabecalho dizia "ultimos 90 dias" e o grafico mostrava, calado, so os ultimos
 * 30.</p>
 *
 * <h2>Defeito 3: CMV sem custo, sem aviso</h2>
 * <p>Item com custo nulo entra no CMV como zero — nao ha nada melhor a fazer com ele. O que faltava
 * era CONTAR, para a tela dizer que ali o CMV sai menor do que e e a margem maior. O Desempenho ja
 * faz isso para imposto e custo fixo ({@code custosIncompletos}).</p>
 *
 * <p><b>Esta acontecendo hoje:</b> medido na producao em 26/10/2026, somente leitura, 26 dos 48
 * itens de pedido estao sem custo unitario.</p>
 */
class OgraficoFECHAcomOcabecalhoTest {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Long LOJA = 18L;

    private PedidoRepository pedidos;
    private PedidoItemRepository itens;
    private LojaRepository lojas;
    private RelatorioService service;

    @BeforeEach
    void montar() {
        pedidos = mock(PedidoRepository.class);
        itens = mock(PedidoItemRepository.class);
        lojas = mock(LojaRepository.class);
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        when(lojas.findById(LOJA)).thenReturn(java.util.Optional.empty());
        when(itens.findByLojaIdAndPedidoIdIn(anyLong(), any())).thenReturn(List.of());
        service = new RelatorioService(pedidos, itens,
                mock(br.com.bora.repository.UsuarioLojaRepository.class), lojas, ctx);
    }

    /** Uma venda de {@code valor} feita em {@code dia} (data de Sao Paulo), as {@code hora}. */
    private Pedido venda(long id, LocalDate dia, int hora, String valor) {
        Pedido p = new Pedido();
        p.id = id;
        p.lojaId = LOJA;
        p.status = StatusPedido.ENTREGUE;
        p.valorTotal = new BigDecimal(valor);
        p.aguardandoPagamento = false;
        p.criadoEm = dia.atTime(hora, 0).atZone(ZONE).toOffsetDateTime();
        return p;
    }

    private void comVendas(Pedido... lista) {
        when(pedidos.findByLojaIdAndCriadoEmAfterOrderByCriadoEmDesc(eq(LOJA), any()))
                .thenReturn(List.of(lista));
    }

    @SuppressWarnings("unchecked")
    private Map<String, BigDecimal> barras(Map<String, Object> rel) {
        return (Map<String, BigDecimal>) rel.get("porDia");
    }

    private BigDecimal somaDasBarras(Map<String, Object> rel) {
        return barras(rel).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---------------------------------------------------------------- 1 e 2: o grafico fecha
    @Test
    void aSOMAdasBARRASehOfaturamento() {
        LocalDate hoje = LocalDate.now(ZONE);
        comVendas(venda(1L, hoje, 10, "100.00"),
                venda(2L, hoje.minusDays(5), 14, "250.00"),
                venda(3L, hoje.minusDays(29), 23, "80.00"));

        Map<String, Object> rel = service.gerar(30, null);

        assertEquals(0, ((BigDecimal) rel.get("faturamento")).compareTo(somaDasBarras(rel)),
                "o grafico nao fecha com o cabecalho: cabecalho " + rel.get("faturamento")
                        + ", barras " + somaDasBarras(rel));
        assertEquals(0, new BigDecimal("430.00").compareTo((BigDecimal) rel.get("faturamento")));
    }

    @Test
    void aVENDAdaMADRUGADAdoPRIMEIROdiaTEMbarra() {
        // O caso que se perdia: venda nas horas iniciais do primeiro dia da janela. Com o corte no
        // instante, ela entrava no faturamento e nao tinha barra -- ou ficava fora dos dois, dependendo
        // da hora em que o lojista abrisse a tela.
        LocalDate primeiro = LocalDate.now(ZONE).minusDays(29);
        comVendas(venda(1L, primeiro, 1, "60.00"));

        Map<String, Object> rel = service.gerar(30, null);

        assertEquals(0, new BigDecimal("60.00").compareTo(barras(rel).get(primeiro.toString())),
                "a venda da madrugada do primeiro dia nao tem barra");
        assertEquals(0, ((BigDecimal) rel.get("faturamento")).compareTo(somaDasBarras(rel)));
    }

    @Test
    void aJANELAdeNOVENTAdiasTEMnoventaBARRAS() {
        // A tela oferece o botao de 90 dias, e o grafico mostrava 30 calado.
        comVendas();

        Map<String, Object> rel = service.gerar(90, null);

        assertEquals(90, barras(rel).size(),
                "o grafico de 90 dias tinha " + barras(rel).size() + " barras");
        assertEquals(90, rel.get("dias"));
    }

    @Test
    void aVENDAdeSESSENTAdiasATRASapareceNOgraficoDEnoventa() {
        LocalDate dia = LocalDate.now(ZONE).minusDays(60);
        comVendas(venda(1L, dia, 12, "500.00"));

        Map<String, Object> rel = service.gerar(90, null);

        assertEquals(0, new BigDecimal("500.00").compareTo(barras(rel).get(dia.toString())),
                "venda de 60 dias atras nao tinha barra no grafico de 90 dias");
        assertEquals(0, ((BigDecimal) rel.get("faturamento")).compareTo(somaDasBarras(rel)));
    }

    @Test
    void aJANELAtemEXATAMENTEosDIASpedidos() {
        comVendas();
        for (int dias : new int[] { 7, 30, 90 }) {
            Map<String, Object> rel = service.gerar(dias, null);
            assertEquals(dias, barras(rel).size(), "janela de " + dias + " dias");
            assertEquals(LocalDate.now(ZONE).toString(), rel.get("ate"));
            assertEquals(LocalDate.now(ZONE).minusDays(dias - 1L).toString(), rel.get("de"),
                    "a data inicial de " + dias + " dias");
        }
    }

    @Test
    void oSERVICOdizAsomaDASbarras_paraATELApoderCONFERIR() {
        LocalDate hoje = LocalDate.now(ZONE);
        comVendas(venda(1L, hoje, 10, "100.00"), venda(2L, hoje.minusDays(3), 10, "40.00"));

        Map<String, Object> rel = service.gerar(30, null);

        assertEquals(0, somaDasBarras(rel).compareTo((BigDecimal) rel.get("porDiaSoma")));
        assertEquals(0, ((BigDecimal) rel.get("faturamento")).compareTo((BigDecimal) rel.get("porDiaSoma")));
    }

    @Test
    void oPIXnaoPAGOnaoENTRAemNENHUMdosDOIS() {
        // Ele ja estava fora do faturamento; o que nao pode e ele aparecer na barra.
        LocalDate hoje = LocalDate.now(ZONE);
        Pedido pendente = venda(2L, hoje, 11, "999.00");
        pendente.aguardandoPagamento = true;
        comVendas(venda(1L, hoje, 10, "100.00"), pendente);

        Map<String, Object> rel = service.gerar(30, null);

        assertEquals(0, new BigDecimal("100.00").compareTo(barras(rel).get(hoje.toString())));
        assertEquals(0, ((BigDecimal) rel.get("faturamento")).compareTo(somaDasBarras(rel)));
        assertEquals(1, rel.get("aguardandoPagamento"));
    }

    // ---------------------------------------------------------------- 3: o CMV sem custo
    @Test
    void oRELATORIOdizQUANTOSitensNAOtemCUSTO() {
        LocalDate hoje = LocalDate.now(ZONE);
        comVendas(venda(1L, hoje, 10, "100.00"));
        when(itens.findByLojaIdAndPedidoIdIn(anyLong(), any()))
                .thenReturn(List.of(item("30.00"), item(null), item(null)));

        Map<String, Object> rel = service.gerar(30, null);

        assertEquals(2, rel.get("itensSemCusto"), "o CMV sai menor do que e e nada avisava");
        assertEquals(3, rel.get("itensVendidos"));
        assertEquals(Boolean.TRUE, rel.get("custosIncompletos"));
    }

    @Test
    void comTODOScustosCADASTRADOSnaoAVISA() {
        // Aviso que aparece sempre vira ruido e o lojista para de ler.
        LocalDate hoje = LocalDate.now(ZONE);
        comVendas(venda(1L, hoje, 10, "100.00"));
        when(itens.findByLojaIdAndPedidoIdIn(anyLong(), any()))
                .thenReturn(List.of(item("30.00"), item("10.00")));

        Map<String, Object> rel = service.gerar(30, null);

        assertEquals(0, rel.get("itensSemCusto"));
        assertEquals(Boolean.FALSE, rel.get("custosIncompletos"));
    }

    @Test
    void custoZEROcadastradoNAOehAUSENCIAdeCUSTO() {
        // Zero cadastrado e uma afirmacao do lojista; nulo e a falta dela. Confundir os dois faria o
        // aviso aparecer para quem fez o dever de casa.
        LocalDate hoje = LocalDate.now(ZONE);
        comVendas(venda(1L, hoje, 10, "100.00"));
        when(itens.findByLojaIdAndPedidoIdIn(anyLong(), any())).thenReturn(List.of(item("0.00")));

        assertEquals(0, service.gerar(30, null).get("itensSemCusto"));
    }

    @Test
    void oCMVcontinuaSOMANDOoQUEtemCUSTO() {
        LocalDate hoje = LocalDate.now(ZONE);
        comVendas(venda(1L, hoje, 10, "100.00"));
        when(itens.findByLojaIdAndPedidoIdIn(anyLong(), any()))
                .thenReturn(List.of(item("30.00"), item(null)));

        Map<String, Object> rel = service.gerar(30, null);

        assertEquals(0, new BigDecimal("30.00").compareTo((BigDecimal) rel.get("cmv")));
        assertEquals(0, new BigDecimal("70.00").compareTo((BigDecimal) rel.get("margem")));
    }

    private PedidoItem item(String custo) {
        PedidoItem it = new PedidoItem();
        it.setLojaId(LOJA);
        it.setPedidoId(1L);
        it.setQuantidade(1);
        it.setDescricao("Acai 500ml");
        it.setSubtotal(new BigDecimal("100.00"));
        it.setCustoUnitario(custo == null ? null : new BigDecimal(custo));
        return it;
    }

    // ---------------------------------------------------------------- o que nao pode voltar
    @Test
    void aSERIEnaoVOLTAaSERcortadaEM30() {
        String fonte = assertDoesNotThrow(() -> java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/br/com/bora/service/RelatorioService.java"),
                java.nio.charset.StandardCharsets.UTF_8));
        List<String> codigo = new ArrayList<>();
        for (String linha : fonte.split("\n", -1)) {
            String t = linha.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            codigo.add(linha);
        }
        String limpo = String.join("\n", codigo);
        assertFalse(limpo.contains("Math.min(janela, 30)"),
                "voltou o corte da serie em 30 dias: o grafico de 90 mostra 30 calado");
        assertTrue(limpo.contains("primeiroDia.atStartOfDay(ZONE)"),
                "o corte voltou a ser no instante, e o grafico perde a madrugada do primeiro dia");
    }
}
