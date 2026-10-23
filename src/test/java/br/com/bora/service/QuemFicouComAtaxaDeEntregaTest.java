package br.com.bora.service;

import br.com.bora.dto.InboundOrder;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.*;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Quem ficou com a taxa de entrega passa a ser um dado — e o relatório diz o tamanho.
 *
 * <h2>O defeito que isto fecha (item 5.8)</h2>
 * <p>O faturamento da loja é {@code sum(valorTotal)}, e para pedido de marketplace
 * {@code valorTotal} é o {@code total.orderAmount} do payload: o <b>bruto</b> que o cliente pagou,
 * <b>taxa de entrega incluída</b>. Quando quem entrega é o marketplace, essa taxa é dele — e estava
 * somando no faturamento da loja.</p>
 *
 * <p>O dado de <b>quem entrega</b> já vinha no payload ({@code delivery.deliveredBy}) e o
 * normalizador já o lia — mas só para escrever "Entrega pelo iFood" na <b>observação</b>, que é texto
 * e nenhuma conta alcança.</p>
 *
 * <h2>O tamanho, medido na produção em 22/10/2026</h2>
 * <p>7 pedidos de iFood, R$ 104,00 somados, <b>R$ 10,00</b> de taxa dentro — e todos são os pedidos de
 * teste de setembro. Nenhuma loja tem marketplace no ar. Então hoje não há dinheiro em jogo: há a
 * hora certa de arrumar, antes de haver.</p>
 *
 * <h2>O que este teste NÃO afirma</h2>
 * <p>Que o faturamento mudou. <b>Não mudou de propósito:</b> descontar a taxa muda um número que o
 * dono olha todo dia, e isso é decisão dele. O que entrou é um número <b>ao lado</b>
 * ({@code taxaDoMarketplace}), para o tamanho ficar visível. Há teste cobrando as duas coisas.</p>
 */
class QuemFicouComAtaxaDeEntregaTest {

    private static final Long LOJA = 18L;

    // =============================================================== o normalizador

    @SuppressWarnings("unchecked")
    private InboundOrder doPayload(String json) throws Exception {
        Map<String, Object> raw = new ObjectMapper().readValue(json, Map.class);
        return new MarketplaceNormalizer().normalizar("IFOOD", raw);
    }

    @SuppressWarnings("unchecked")
    private InboundOrder pedidoRealDaLojaDeTeste() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/ifood/pedido-real-loja-teste.json")) {
            Map<String, Object> raw = new ObjectMapper().readValue(in, Map.class);
            return new MarketplaceNormalizer().normalizar("IFOOD", raw);
        }
    }

    @Test
    void entregaDOifood_ficaMARCADAcomoDOcanal() throws Exception {
        InboundOrder o = doPayload("{\"id\":\"p1\",\"orderType\":\"DELIVERY\","
                + "\"delivery\":{\"deliveredBy\":\"IFOOD\"},"
                + "\"total\":{\"orderAmount\":50.0,\"deliveryFee\":8.0}}");

        assertEquals(Boolean.TRUE, o.entregaPeloCanal());
        assertEquals(0, new BigDecimal("8").compareTo(o.taxaEntrega()));
    }

    @Test
    void entregaDAloja_ficaMARCADAcomoDAloja() throws Exception {
        // Aqui a loja recebe a taxa: marcar como do canal tiraria do faturamento dinheiro que entrou.
        InboundOrder o = doPayload("{\"id\":\"p2\",\"orderType\":\"DELIVERY\","
                + "\"delivery\":{\"deliveredBy\":\"MERCHANT\"},"
                + "\"total\":{\"orderAmount\":50.0,\"deliveryFee\":8.0}}");

        assertEquals(Boolean.FALSE, o.entregaPeloCanal());
    }

    @Test
    void oPEDIDOrealDAlojaDEteste_eENTREGAdaLOJA() throws Exception {
        // O payload de verdade, guardado em 19/09/2026: `deliveredBy: MERCHANT`. Nao e suposicao.
        assertEquals(Boolean.FALSE, pedidoRealDaLojaDeTeste().entregaPeloCanal());
    }

    @Test
    void retiradaNObalcao_naoTEMtaxaDEninguem() throws Exception {
        // Nulo, nao false: nao ha entrega, logo nao ha taxa que alguem tenha ficado. `false` diria
        // "a loja recebeu a taxa de entrega", que e afirmacao sobre dinheiro que nao existe.
        InboundOrder o = doPayload("{\"id\":\"p3\",\"orderType\":\"TAKEOUT\","
                + "\"total\":{\"orderAmount\":30.0}}");

        assertNull(o.entregaPeloCanal());
    }

    @Test
    void semOcampoNOpayload_ficaFALSE_eNAOnulo() throws Exception {
        // Entrega sem `deliveredBy` e o caso comum dos canais que nao mandam o campo. A leitura e
        // "nao e do canal", que e o que o `equalsIgnoreCase("IFOOD")` devolve -- e isso esta certo:
        // o default do iFood e entrega da loja. Fica medido para ninguem "consertar" para nulo.
        InboundOrder o = doPayload("{\"id\":\"p4\",\"orderType\":\"DELIVERY\",\"delivery\":{},"
                + "\"total\":{\"orderAmount\":30.0,\"deliveryFee\":5.0}}");

        assertEquals(Boolean.FALSE, o.entregaPeloCanal());
    }

    @Test
    void oAVISOnaOBSERVACAOcontinuaLA() throws Exception {
        // O texto nao foi trocado pelo dado: ele e o que a cozinha e o card mostram.
        InboundOrder o = doPayload("{\"id\":\"p5\",\"orderType\":\"DELIVERY\","
                + "\"delivery\":{\"deliveredBy\":\"IFOOD\"},"
                + "\"total\":{\"orderAmount\":50.0,\"deliveryFee\":8.0}}");

        assertTrue(o.observacao().contains("Entrega pelo iFood"), o.observacao());
    }

    // =============================================================== a fiacao ate o pedido gravado

    /**
     * O pedido que o {@code criarExterno} gravou.
     *
     * <p><b>Este teste existe porque um mutante sobreviveu:</b> trocar
     * {@code p.entregaPeloCanal = in.entregaPeloCanal()} por {@code = null} passava verde. Eu tinha
     * testado as duas PONTAS — o normalizador le o payload, o relatorio soma a coluna — e deixado o
     * MEIO sem teste. E a mesma licao de sempre, de outro angulo: teste de ponta nao cobre fiacao.</p>
     */
    private Pedido gravado(InboundOrder in) {
        PedidoRepository repo = mock(PedidoRepository.class);
        PedidoItemRepository itemRepo = mock(PedidoItemRepository.class);
        ClienteRepository clientes = mock(ClienteRepository.class);
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, LOJA, "ADMINISTRADOR_LOJA", "a@b.c"));
        when(repo.findFirstByLojaIdAndCanalExternoAndIdExterno(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(clientes.save(any())).thenAnswer(inv -> {
            br.com.bora.entity.Cliente c = inv.getArgument(0);
            c.id = 70L;
            return c;
        });
        when(repo.save(any(Pedido.class))).thenAnswer(inv -> {
            Pedido p = inv.getArgument(0);
            p.id = 500L;
            return p;
        });
        when(itemRepo.saveAll(any())).thenReturn(List.of());

        PedidoService service = new PedidoService(repo, itemRepo, mock(ProdutoRepository.class),
                clientes, mock(LogStatusRepository.class), mock(PlanoService.class),
                mock(IntegracaoService.class), mock(TaxaEntregaRepository.class),
                mock(InsumoService.class), mock(FidelidadeService.class),
                mock(ComplementoService.class), ctx, mock(EstornoDaVendaService.class),
                GerenciadorDeTransacaoFalso.novo());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "notifCliente",
                mock(NotificacaoClienteService.class));

        return service.criarExterno(LOJA, "IFOOD", "iFood", in);
    }

    private InboundOrder doCanal(Boolean quemEntrega) {
        return new InboundOrder("ped-1", "Cliente", "62999990000", "Rua X, 1", "Centro",
                "Pago online", null, new BigDecimal("50"), List.of(),
                new BigDecimal("8"), "1022", null, quemEntrega);
    }

    @Test
    void oQUEoNORMALIZADORleu_CHEGAnoPEDIDOgravado() {
        assertEquals(Boolean.TRUE, gravado(doCanal(true)).entregaPeloCanal,
                "entrega do canal tem que chegar gravada no pedido");
        assertEquals(Boolean.FALSE, gravado(doCanal(false)).entregaPeloCanal);
    }

    @Test
    void oNULOchegaCOMOnulo_eNAOcomoFALSE() {
        assertNull(gravado(doCanal(null)).entregaPeloCanal,
                "\"nao informado\" nao pode virar \"a loja ficou com a taxa\"");
    }

    // =============================================================== o relatorio

    private Pedido venda(String valor, String taxa, Boolean doCanal) {
        Pedido p = new Pedido();
        p.id = (long) (Math.random() * 1_000_000);
        p.lojaId = LOJA;
        p.status = StatusPedido.ENTREGUE;
        p.valorTotal = new BigDecimal(valor);
        p.taxaEntrega = taxa == null ? null : new BigDecimal(taxa);
        p.entregaPeloCanal = doCanal;
        p.criadoEm = OffsetDateTime.now().minusHours(3);
        p.aguardandoPagamento = false;
        return p;
    }

    private Map<String, Object> relatorio(List<Pedido> doBanco) {
        PedidoRepository pedidos = mock(PedidoRepository.class);
        when(pedidos.findByLojaIdAndCriadoEmAfterOrderByCriadoEmDesc(eq(LOJA), any())).thenReturn(doBanco);
        PedidoItemRepository itens = mock(PedidoItemRepository.class);
        when(itens.findByLojaIdAndPedidoIdIn(any(), any())).thenReturn(List.of());
        LojaRepository lojas = mock(LojaRepository.class);
        Loja l = new Loja();
        l.setNome("Zirá Açaíteria");
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, LOJA, "ADMINISTRADOR_LOJA", "admin@bora.app"));
        return new RelatorioService(pedidos, itens, mock(UsuarioLojaRepository.class), lojas, ctx)
                .gerar(30, null);
    }

    @Test
    void oRELATORIOdizQUANTOdaTAXAficouCOMoMARKETPLACE() {
        Map<String, Object> r = relatorio(List.of(
                venda("50.00", "8.00", true),    // iFood entregou: a taxa e dele
                venda("40.00", "6.00", true),    // idem
                venda("30.00", "5.00", false),   // a loja entregou: a taxa e da loja
                venda("20.00", "0.00", null)     // balcao: nao se aplica
        ));

        assertEquals(0, new BigDecimal("14.00").compareTo((BigDecimal) r.get("taxaDoMarketplace")),
                "8 + 6 dos dois pedidos que o canal entregou; foi " + r.get("taxaDoMarketplace"));
    }

    @Test
    void eOfATURAMENTOcontinuaObruto() {
        // De proposito, e e a parte que eu NAO decidi sozinho: 140 e o que o cliente pagou.
        // Descontar a taxa muda um numero que o dono olha todo dia.
        Map<String, Object> r = relatorio(List.of(
                venda("50.00", "8.00", true),
                venda("40.00", "6.00", true),
                venda("30.00", "5.00", false),
                venda("20.00", "0.00", null)
        ));

        assertEquals(0, new BigDecimal("140.00").compareTo((BigDecimal) r.get("faturamento")),
                "o faturamento NAO desconta a taxa: isso e decisao do dono (item 5.8)");
    }

    @Test
    void entregaDAlojaNAOentraNAcontaDOmarketplace() {
        Map<String, Object> r = relatorio(List.of(venda("30.00", "5.00", false)));

        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) r.get("taxaDoMarketplace")),
                "a loja recebeu esta taxa");
    }

    @Test
    void pedidoANTIGOsemAinformacao_naoVIRAtaxaDEninguem() {
        // Nulo e "nao informado" (pedido anterior a V58, ou de balcao). Contar como do marketplace
        // inventaria uma perda; contar como da loja inventaria uma receita.
        Map<String, Object> r = relatorio(List.of(venda("30.00", "5.00", null)));

        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) r.get("taxaDoMarketplace")));
    }

    @Test
    void pedidoCANCELADOnaoENTRAnaCONTA() {
        Pedido cancelado = venda("50.00", "8.00", true);
        cancelado.status = StatusPedido.CANCELADO;

        Map<String, Object> r = relatorio(List.of(cancelado, venda("40.00", "6.00", true)));

        assertEquals(0, new BigDecimal("6.00").compareTo((BigDecimal) r.get("taxaDoMarketplace")),
                "cancelado nao e venda, nem para a taxa");
    }

    @Test
    void pixGERADOeNAOpagoTAMBEMnaoENTRA() {
        Pedido esperandoPix = venda("50.00", "8.00", true);
        esperandoPix.status = StatusPedido.RECEBIDO;
        esperandoPix.aguardandoPagamento = true;

        Map<String, Object> r = relatorio(List.of(esperandoPix, venda("40.00", "6.00", true)));

        assertEquals(0, new BigDecimal("6.00").compareTo((BigDecimal) r.get("taxaDoMarketplace")),
                "a mesma regra do faturamento: PIX que ninguem pagou nao e venda");
    }

    @Test
    void taxaNULAnaoDERRUBAaCONTA() {
        // `taxa_entrega` tem default 0, mas pedido vindo de fora pode chegar sem ela.
        Map<String, Object> r = relatorio(List.of(venda("50.00", null, true), venda("40.00", "6.00", true)));

        assertEquals(0, new BigDecimal("6.00").compareTo((BigDecimal) r.get("taxaDoMarketplace")));
    }

    @Test
    void semPEDIDOnenhum_oNUMEROeZERO_eNAOnulo() {
        // Tela que recebe null de um campo numerico mostra "undefined".
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) relatorio(List.of()).get("taxaDoMarketplace")));
    }
}
