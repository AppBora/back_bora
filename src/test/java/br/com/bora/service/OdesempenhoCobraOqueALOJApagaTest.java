package br.com.bora.service;

import br.com.bora.entity.ConfiguracaoLoja;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * O custo que a tela de Desempenho mostra e o que a loja PAGA de verdade.
 *
 * <h2>Os dois defeitos que isto fecha (pente fino de 29/10/2026)</h2>
 *
 * <p><b>1) A mensalidade nao cobrava o Modulo IA.</b> A parcela era
 * {@code rateio(l.precoEfetivo(), dias)}, e o {@code precoEfetivo()} devolve so o plano. Quem soma o
 * add-on e o {@code precoComModulos()}, que acrescenta os R$ 99,00 quando {@code moduloIa} esta
 * ligado. A loja com o modulo via R$ 199 de mensalidade rateada pagando R$ 298, e o <b>lucro saia
 * R$ 99/mes maior do que e</b>.</p>
 *
 * <p><b>2) O cashback incidia em 100% do faturamento.</b> A conta era {@code faturamento * pct}, como
 * se toda venda gerasse saldo. O sistema so credita quando ha cliente -- o
 * {@code FidelidadeService.registrar} sai na primeira linha com {@code if (clienteId == null) return;}
 * -- e no pedido de marketplace o credito e desligado quando o telefone nao e de pessoa. Numa loja
 * com PDV, a maioria das vendas nao tem cadastro nenhum, e todas elas eram cobradas.</p>
 *
 * <p>Os dois erram para lados opostos, o que e pior do que errar para um so: eles se escondem um no
 * outro. E esta e a tela em que o dono decide preco.</p>
 */
class OdesempenhoCobraOqueALOJApagaTest {

    private static final Long LOJA = 18L;

    private LojaRepository lojas;
    private PedidoRepository pedidos;
    private ConfiguracaoLojaRepository configs;
    private AuthContext ctx;
    private PedidoItemRepository itensRepo;
    private DesempenhoService service;
    private Loja loja;

    @BeforeEach
    void montar() {
        lojas = mock(LojaRepository.class);
        pedidos = mock(PedidoRepository.class);
        configs = mock(ConfiguracaoLojaRepository.class);
        ctx = mock(AuthContext.class);

        loja = new Loja();
        loja.id = LOJA;
        loja.nome = "Zira Centro";
        loja.precoMensal = new BigDecimal("199.00");
        when(ctx.isAdminBora()).thenReturn(true);
        when(lojas.findAll()).thenReturn(List.of(loja));
        when(configs.findByLojaId(LOJA)).thenReturn(Optional.empty());
        when(pedidos.somaReceita(anyLong(), any(), any())).thenReturn(new BigDecimal("1000.00"));
        when(pedidos.contaPedidosValidos(anyLong(), any(), any())).thenReturn(10L);
        itensRepo = mock(PedidoItemRepository.class);
        service = new DesempenhoService(lojas, pedidos, itensRepo, configs,
                mock(UsuarioLojaRepository.class), ctx);
    }

    private void cashbackDe(String pct) {
        ConfiguracaoLoja cfg = new ConfiguracaoLoja();
        cfg.lojaId = LOJA;
        cfg.cashbackPercentual = new BigDecimal(pct);
        when(configs.findByLojaId(LOJA)).thenReturn(Optional.of(cfg));
    }

    /** Uma venda de R$ 100. {@code clienteId} nulo = balcao sem cadastro. */
    private Pedido venda(Long clienteId, String canalExterno, String telefone) {
        Pedido p = new Pedido();
        p.id = (long) (Math.random() * 1_000_000);
        p.lojaId = LOJA;
        p.status = StatusPedido.ENTREGUE;
        p.criadoEm = OffsetDateTime.now().minusHours(2);
        p.valorTotal = new BigDecimal("100.00");
        p.taxaEntrega = BigDecimal.ZERO;
        p.aguardandoPagamento = false;
        p.clienteId = clienteId;
        p.canalExterno = canalExterno;
        p.clienteTelefone = telefone;
        return p;
    }

    private Map<String, Object> daLoja(Pedido... lista) {
        when(pedidos.findByLojaIdAndCriadoEmAfterOrderByCriadoEmDesc(eq(LOJA), any()))
                .thenReturn(List.of(lista));
        Map<String, Object> saida = service.consolidado(LocalDate.now(), LocalDate.now());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> linhas = (List<Map<String, Object>>) saida.get("lojas");
        return linhas.get(0);
    }

    private static void reais(String esperado, Object veio, String porque) {
        assertEquals(0, new BigDecimal(esperado).compareTo((BigDecimal) veio),
                porque + " (esperado R$ " + esperado + ", veio R$ " + veio + ")");
    }

    // ================================================== 1) o Modulo IA

    @Test
    void aMENSALIDADEcobraOMODULOcontratado() {
        loja.moduloIa = true;

        // Um dia de periodo: o rateio e o preco do mes dividido por 30 e multiplicado por 1 dia.
        Map<String, Object> m = daLoja(venda(null, null, null));

        reais("9.93", m.get("mensalidade"),
                "a mensalidade ficou so com o plano: a loja paga R$ 298 (199 + 99 do Modulo IA)");
    }

    @Test
    void semMODULOaMENSALIDADEnaoMUDA() {
        loja.moduloIa = false;

        Map<String, Object> m = daLoja(venda(null, null, null));

        reais("6.63", m.get("mensalidade"), "cobrou modulo de quem nao contratou");
    }

    // ================================================== 2) o cashback

    @Test
    void aVENDAdeBALCAOsemCADASTROnaoGERAcashback() {
        cashbackDe("5");

        // Duas vendas de R$ 100: uma com cliente, uma sem. So a primeira credita saldo.
        Map<String, Object> m = daLoja(venda(500L, null, "11999990000"), venda(null, null, null));

        reais("5.00", m.get("cashback"),
                "cobrou cashback da venda de balcao sem cadastro, que nao credita nada");
    }

    @Test
    void semCLIENTEnenhumOcashbackEHzero() {
        cashbackDe("10");

        Map<String, Object> m = daLoja(venda(null, null, null), venda(null, null, null));

        reais("0.00", m.get("cashback"),
                "a loja que so vende no balcao estava pagando cashback que nunca creditou");
    }

    @Test
    void oPEDIDOdeMARKETPLACEcomTELEFONEdeCENTRALnaoGERAcashback() {
        cashbackDe("5");

        // O iFood manda a central de atendimento mais um codigo. Ninguem resgata saldo por ali, e o
        // proprio PedidoService desliga o credito nesse caso.
        Map<String, Object> m = daLoja(venda(700L, "IFOOD", "0800 000 1234"));

        reais("0.00", m.get("cashback"), "cobrou cashback de pedido de marketplace sem telefone de pessoa");
    }

    @Test
    void oPEDIDOdeMARKETPLACEcomTELEFONEdeVERDADEgeraCASHBACK() {
        cashbackDe("5");

        Map<String, Object> m = daLoja(venda(700L, "NOVENOVE", "11988887777"));

        reais("5.00", m.get("cashback"), "deixou de cobrar cashback que o sistema credita de verdade");
    }

    @Test
    void oPIXdoPROPRIOcardapioCONTAcomoVENDAdeCLIENTE() {
        cashbackDe("5");

        // `canalExterno = "PIX_ASAAS"` nao e marketplace: e onde o cardapio guarda o id da cobranca.
        // Tratar isso como marketplace faria o cashback do canal proprio desaparecer da conta.
        Map<String, Object> m = daLoja(venda(900L, "PIX_ASAAS", "11977776666"));

        reais("5.00", m.get("cashback"), "o PIX do cardapio foi tratado como pedido de marketplace");
    }

    // ================================================== os dois juntos

    @Test
    void oLUCROsaiCERTOcomOSdoisCONSERTOS() {
        loja.moduloIa = true;
        cashbackDe("5");

        // Faturamento de R$ 1.000 (o mock), uma venda com cliente e uma de balcao.
        Map<String, Object> m = daLoja(venda(500L, null, "11999990000"), venda(null, null, null));

        // custo = cmv 0 + entrega 0 + cashback 5 + imposto 0 + custoFixo 0 + mensalidade 9,93
        reais("14.93", m.get("custoTotal"), "o custo total nao fechou com as parcelas");
        reais("985.07", m.get("lucro"), "o lucro da tela em que o dono decide preco esta errado");
    }
    // ================================================== 3) o CMV que sai menor sem avisar
    //
    // O `custosIncompletos` deste servico olhava SOMENTE a aliquota de imposto e o custo fixo. Item
    // vendido sem custo cadastrado entrava no CMV como R$ 0,00 -- o que e aceitavel, nao ha nada
    // melhor a fazer com nulo -- mas passava CALADO: CMV menor que o real, margem MAIOR que a real,
    // e nada na tela dizendo isso.
    //
    // Medido na varredura de 18/11/2026: a loja 18 nao tem custo em NENHUM dos 44 produtos dela. O
    // RelatorioService ja contava isso desde 26/10; este servico, que alimenta o painel da rede, nao.
    // Reconferido por mim em 19/11 e consertado no mesmo dia.

    /** Um item vendido, com ou sem custo cadastrado. */
    private br.com.bora.entity.PedidoItem item(Long pedidoId, String custoUnitario) {
        br.com.bora.entity.PedidoItem it = new br.com.bora.entity.PedidoItem();
        it.setLojaId(LOJA);
        it.setPedidoId(pedidoId);
        it.setQuantidade(1);
        it.setSubtotal(new BigDecimal("100.00"));
        it.setCustoUnitario(custoUnitario == null ? null : new BigDecimal(custoUnitario));
        return it;
    }

    private Map<String, Object> comItens(Pedido pedido, br.com.bora.entity.PedidoItem... itens) {
        when(itensRepo.findByLojaIdAndPedidoIdIn(eq(LOJA), any())).thenReturn(List.of(itens));
        return daLoja(pedido);
    }

    /** A PREMISSA: com custo em todos os itens, nada e marcado como incompleto. */
    @Test
    void aPREMISSAcomCUSTOemTODOSitensNADAehINCOMPLETO() {
        configCompleta();
        Pedido v = venda(null, null, null);
        Map<String, Object> linha = comItens(v, item(v.id, "40.00"));

        assertEquals(false, linha.get("custosIncompletos"),
                "com aliquota, custo fixo e custo em todo item, o numero e lucro e nao margem");
        assertEquals(0, linha.get("itensSemCusto"));
    }

    /** O QUE IMPORTA: item sem custo marca o numero como incompleto. */
    @Test
    void itemSEMcustoMARCAoNUMEROcomoINCOMPLETO() {
        configCompleta();
        Pedido v = venda(null, null, null);
        Map<String, Object> linha = comItens(v, item(v.id, "40.00"), item(v.id, null));

        assertEquals(true, linha.get("custosIncompletos"),
                "item vendido sem custo cadastrado entra no CMV como ZERO: o CMV sai menor do que e "
                        + "e a margem MAIOR do que e. A tela tem de dizer isso -- era o unico dos "
                        + "tres casos que passava calado.");
        assertEquals(1, linha.get("itensSemCusto"),
                "a tela precisa do TAMANHO do buraco, nao so do aviso de que existe");
    }

    /** E conta todos, nao so o primeiro. */
    @Test
    void aCONTAGEMdizQUANTOSitensEstaoSEMcusto() {
        configCompleta();
        Pedido v = venda(null, null, null);
        Map<String, Object> linha = comItens(v, item(v.id, null), item(v.id, "40.00"), item(v.id, null));

        assertEquals(2, linha.get("itensSemCusto"));
    }

    /** O CONTROLE: os dois motivos antigos continuam marcando. */
    @Test
    void oCONTROLEaliquotaAUSENTEcontinuaMARCANDO() {
        // sem configCompleta(): a loja fica sem aliquota e sem custo fixo
        Pedido v = venda(null, null, null);
        Map<String, Object> linha = comItens(v, item(v.id, "40.00"));

        assertEquals(true, linha.get("custosIncompletos"),
                "sem aliquota de imposto o numero segue sendo margem, e nao lucro");
        assertEquals(0, linha.get("itensSemCusto"), "e nao ha item sem custo neste caso");
    }

    /** A loja com aliquota e custo fixo preenchidos, para isolar o motivo do item. */
    private void configCompleta() {
        ConfiguracaoLoja cfg = new ConfiguracaoLoja();
        cfg.lojaId = LOJA;
        cfg.aliquotaImposto = new BigDecimal("6.00");
        cfg.custoFixoMensal = new BigDecimal("3000.00");
        when(configs.findByLojaId(LOJA)).thenReturn(Optional.of(cfg));
    }
}
