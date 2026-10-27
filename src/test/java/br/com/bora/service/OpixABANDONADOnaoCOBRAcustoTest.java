package br.com.bora.service;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.ConfiguracaoLojaRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checkout abandonado nao cobra custo de uma venda que nunca existiu.
 *
 * <h2>O defeito (achado pelo QA em 26/10/2026)</h2>
 * <p>No Desempenho, a receita vem do {@code somaReceita} e a contagem do
 * {@code contaPedidosValidos} — <b>as duas excluem o PIX gerado e nao pago</b>, porque checkout
 * abandonado nao e venda. Mas a lista de onde saem o <b>CMV</b> e a <b>taxa de entrega</b> excluia so
 * o CANCELADO.</p>
 *
 * <p>Resultado: o pedido abandonado no checkout entrava como CUSTO sem entrar como receita, e o lucro
 * da loja saia menor do que e. Com o PIX expirando sozinho em 30 minutos, todo checkout abandonado
 * cobrava um pedaco do lucro.</p>
 *
 * <p>Nao e um numero de enfeite: e a tela em que o dono decide preco, e a mesma em que ele olha se a
 * loja do cliente da lucro.</p>
 */
class OpixABANDONADOnaoCOBRAcustoTest {

    private static final Long LOJA = 18L;

    private LojaRepository lojas;
    private PedidoRepository pedidos;
    private PedidoItemRepository itens;
    private ConfiguracaoLojaRepository configs;
    private UsuarioLojaRepository vinculos;
    private AuthContext ctx;
    private DesempenhoService service;

    @BeforeEach
    void montar() {
        lojas = mock(LojaRepository.class);
        pedidos = mock(PedidoRepository.class);
        itens = mock(PedidoItemRepository.class);
        configs = mock(ConfiguracaoLojaRepository.class);
        vinculos = mock(UsuarioLojaRepository.class);
        ctx = mock(AuthContext.class);

        Loja l = new Loja();
        l.id = LOJA;
        l.nome = "Zira Centro";
        when(ctx.isAdminBora()).thenReturn(true);
        when(lojas.findAll()).thenReturn(List.of(l));
        when(configs.findByLojaId(LOJA)).thenReturn(Optional.empty());
        // Receita e contagem ja excluem o PIX nao pago: e o lado que estava certo.
        when(pedidos.somaReceita(anyLong(), any(), any())).thenReturn(new BigDecimal("100.00"));
        when(pedidos.contaPedidosValidos(anyLong(), any(), any())).thenReturn(1L);
        service = new DesempenhoService(lojas, pedidos, itens, configs, vinculos, ctx);
    }

    /** Um pedido da loja. {@code pix} = PIX gerado e nunca pago. */
    private Pedido pedido(long id, boolean pix, String taxa) {
        Pedido p = new Pedido();
        p.id = id;
        p.lojaId = LOJA;
        p.status = StatusPedido.RECEBIDO;
        p.criadoEm = OffsetDateTime.now().minusHours(2);
        p.valorTotal = new BigDecimal("100.00");
        p.taxaEntrega = new BigDecimal(taxa);
        p.aguardandoPagamento = pix;
        p.pagoEm = null;
        return p;
    }

    private PedidoItem item(long pedidoId, String custo) {
        PedidoItem it = new PedidoItem();
        it.setLojaId(LOJA);
        it.setPedidoId(pedidoId);
        it.setQuantidade(1);
        it.setCustoUnitario(new BigDecimal(custo));
        it.setDescricao("Acai 500ml");
        return it;
    }

    private Map<String, Object> daLoja(Pedido... lista) {
        when(pedidos.findByLojaIdAndCriadoEmAfterOrderByCriadoEmDesc(eq(LOJA), any()))
                .thenReturn(List.of(lista));
        Map<String, Object> saida = service.consolidado(LocalDate.now().minusDays(7), LocalDate.now());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> linhas = (List<Map<String, Object>>) saida.get("lojas");
        return linhas.get(0);
    }

    // ---------------------------------------------------------------- o defeito
    @Test
    void oCMVdoPIXabandonadoNAOentraNOcusto() {
        Pedido pago = pedido(1L, false, "0.00");
        Pedido abandonado = pedido(2L, true, "0.00");
        when(itens.findByLojaIdAndPedidoIdIn(eq(LOJA), any()))
                .thenAnswer(i -> {
                    java.util.Collection<Long> ids = i.getArgument(1);
                    return ids.contains(2L)
                            ? List.of(item(1L, "30.00"), item(2L, "30.00"))
                            : List.of(item(1L, "30.00"));
                });

        Map<String, Object> m = daLoja(pago, abandonado);

        assertEquals(0, new BigDecimal("30.00").compareTo((BigDecimal) m.get("cmv")),
                "o CMV saiu " + m.get("cmv") + ": o checkout abandonado cobrou custo sem receita");
    }

    @Test
    void aTAXAdeENTREGAdoPIXabandonadoNAOentra() {
        when(itens.findByLojaIdAndPedidoIdIn(eq(LOJA), any())).thenReturn(List.of());

        Map<String, Object> m = daLoja(pedido(1L, false, "8.00"), pedido(2L, true, "8.00"));

        assertEquals(0, new BigDecimal("8.00").compareTo((BigDecimal) m.get("entrega")),
                "a taxa do pedido abandonado entrou no custo: " + m.get("entrega"));
    }

    @Test
    void oCUSTOehOMESMOcomOUsemOcheckoutABANDONADO() {
        // A prova direta: a mesma loja, a mesma janela, com e sem o pedido abandonado. Se o
        // abandonado nao e venda, nenhum numero de custo pode mudar.
        //
        // Comparar as duas rodadas, em vez de cravar um valor, porque o `custoTotal` tambem leva a
        // mensalidade rateada pelos dias -- foi ela que reprovou a primeira versao deste teste, e o
        // erro era meu, nao do codigo.
        when(itens.findByLojaIdAndPedidoIdIn(eq(LOJA), any()))
                .thenAnswer(i -> {
                    java.util.Collection<Long> ids = i.getArgument(1);
                    return ids.contains(2L)
                            ? List.of(item(1L, "30.00"), item(2L, "30.00"))
                            : List.of(item(1L, "30.00"));
                });

        Map<String, Object> so = daLoja(pedido(1L, false, "0.00"));
        Map<String, Object> com = daLoja(pedido(1L, false, "0.00"), pedido(2L, true, "8.00"));

        for (String chave : List.of("cmv", "entrega", "custoTotal", "lucro", "faturamento")) {
            assertEquals(0, ((BigDecimal) so.get(chave)).compareTo((BigDecimal) com.get(chave)),
                    "o checkout abandonado mudou o " + chave + ": " + so.get(chave) + " -> "
                            + com.get(chave));
        }
    }

    @Test
    void oCUSTOtotalEHaSOMAdasPARTES() {
        // Trava a conta: se o abandonado entrar por alguma outra parcela, isto denuncia.
        when(itens.findByLojaIdAndPedidoIdIn(eq(LOJA), any())).thenReturn(List.of(item(1L, "30.00")));

        Map<String, Object> m = daLoja(pedido(1L, false, "8.00"), pedido(2L, true, "8.00"));

        BigDecimal soma = BigDecimal.ZERO;
        for (String chave : List.of("cmv", "entrega", "cashback", "imposto", "custoFixo", "mensalidade")) {
            soma = soma.add((BigDecimal) m.get(chave));
        }
        assertEquals(0, soma.compareTo((BigDecimal) m.get("custoTotal")), "custoTotal: " + m);
        assertEquals(0, ((BigDecimal) m.get("faturamento")).subtract(soma)
                .compareTo((BigDecimal) m.get("lucro")), "lucro: " + m);
        assertEquals(0, new BigDecimal("8.00").compareTo((BigDecimal) m.get("entrega")),
                "so a taxa do pedido pago");
    }

    // ---------------------------------------------------------------- o que continua valendo
    @Test
    void oPIXjaPAGOcontaCOMOvenda() {
        // `aguardandoPagamento` true com `pagoEm` preenchido e venda de verdade -- e o estado em que
        // dois dos caminhos de pagamento deixam o pedido.
        Pedido pago = pedido(1L, true, "5.00");
        pago.pagoEm = OffsetDateTime.now().minusHours(1);
        when(itens.findByLojaIdAndPedidoIdIn(eq(LOJA), any())).thenReturn(List.of(item(1L, "30.00")));

        Map<String, Object> m = daLoja(pago);

        assertEquals(0, new BigDecimal("30.00").compareTo((BigDecimal) m.get("cmv")),
                "o PIX PAGO e venda: o custo dele tem de entrar");
        assertEquals(0, new BigDecimal("5.00").compareTo((BigDecimal) m.get("entrega")));
    }

    @Test
    void oCANCELADOcontinuaDEfora() {
        Pedido cancelado = pedido(2L, false, "8.00");
        cancelado.status = StatusPedido.CANCELADO;
        when(itens.findByLojaIdAndPedidoIdIn(eq(LOJA), any())).thenReturn(List.of(item(1L, "30.00")));

        Map<String, Object> m = daLoja(pedido(1L, false, "0.00"), cancelado);

        assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) m.get("entrega")));
    }

    @Test
    void aVENDAnormalCONTINUAcontando() {
        when(itens.findByLojaIdAndPedidoIdIn(eq(LOJA), any())).thenReturn(List.of(item(1L, "30.00")));

        Map<String, Object> m = daLoja(pedido(1L, false, "8.00"));

        assertEquals(0, new BigDecimal("30.00").compareTo((BigDecimal) m.get("cmv")));
        assertEquals(0, new BigDecimal("8.00").compareTo((BigDecimal) m.get("entrega")));
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) m.get("faturamento")));
    }

    /** A regra da receita e a do custo tem de ser a MESMA, e e isso que o defeito violava. */
    @Test
    void aREGRAdoCUSTOehAmesmaDAreceita() {
        String fonte = assertDoesNotThrow(() -> java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/br/com/bora/service/DesempenhoService.java"),
                java.nio.charset.StandardCharsets.UTF_8));
        int i = fonte.indexOf("List<Pedido> vendas =");
        assertTrue(i > 0, "nao achei a lista de vendas: este teste mede nada");
        String trecho = fonte.substring(i, fonte.indexOf(".toList();", i));
        assertTrue(trecho.contains("pagamentoPendente()"),
                "a lista de onde saem o CMV e a entrega voltou a incluir o PIX nao pago, que a "
                        + "receita exclui");
    }
}
