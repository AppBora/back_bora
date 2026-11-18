package br.com.bora.controller;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.Produto;
import br.com.bora.entity.StatusPedido;
import br.com.bora.entity.TaxaEntrega;
import br.com.bora.repository.*;
import br.com.bora.security.RegraDeAcesso;
import br.com.bora.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A venda do CARDAPIO PROPRIO emite evento para a fila do ERP (entrega A2).
 *
 * <h2>O buraco que esta classe fecha (18/11/2026)</h2>
 * <p>Os cinco pontos de gravacao do outbox foram instalados no {@code PedidoService}. O checkout do
 * cardapio proprio <b>nao passa por ele</b>: monta o {@code Pedido} dentro do proprio
 * {@code PublicController}, num {@code tx.execute}. O resultado era que a venda do canal proprio —
 * QR, cashback, PIX — <b>nao emitia evento nenhum</b>.</p>
 *
 * <p>Consequencia no dia do acoplamento: venda feita pelo QR da loja seria <b>invisivel</b> para os
 * modulos de estoque e fiscal. O estoque baixaria no modulo principal e nao no modulo de estoque; a
 * nota nunca seria emitida. E ninguem notaria pela fila, porque a fila simplesmente nao teria a
 * venda.</p>
 *
 * <h2>Os tres momentos, e por que nenhum deles e opcional</h2>
 * <ol>
 *   <li><b>A venda nasce</b> → {@code PedidoRecebido}.</li>
 *   <li><b>O PIX nao nasce</b> (o Asaas recusou) → o pedido e cancelado por uma transacao
 *       POSTERIOR, e precisa de {@code PedidoCancelado}. Sem ele a fila fica com uma venda que
 *       deixou de existir, e o rollback nao ajuda: o commit do {@code PedidoRecebido} ja
 *       aconteceu.</li>
 *   <li><b>O PIX e pago depois do cancelamento</b> → a reabertura, provada em
 *       {@code PedidoReabreNoWebhookDoPixTest}, que e onde a fiacao do webhook ja esta montada.</li>
 * </ol>
 *
 * <h2>O que estes testes NAO provam</h2>
 * <p>Que o INSERT entra na mesma transacao da venda. Isso e comportamento do Spring com o
 * {@code JdbcTemplate}, e aqui o gerenciador de transacao e de mentira. O que da para provar aqui —
 * e e o que importa para quem mexer no codigo depois — e <b>onde</b> a chamada esta: o pedido que
 * chega ao publicador tem de estar com o total FINAL, e a venda recusada nao pode emitir nada.</p>
 */
class AvendaDoCardapioPROPRIOemiteEventoTest {

    private static final Long LOJA = 1L;

    private LojaRepository lojas;
    private ProdutoRepository produtos;
    private PedidoRepository pedidos;
    private PedidoItemRepository itens;
    private TaxaEntregaRepository taxas;
    private FidelidadeService fidelidade;
    private PixService pix;
    private EstornoDaVendaService devolucaoDeEstoque;
    private IntegracaoCanalRepository integracoes;
    /** O alvo: a fila de eventos do ERP. */
    private PublicadorDeEventos publicador;
    private PublicController controller;

    private Produto acai;
    private Loja loja;
    /** Promovido a campo para o teste do "Monte Seu Acai" trocar o acrescimo do complemento. */
    private ComplementoService complementosDoCardapio;

    @BeforeEach
    void montar() {
        lojas = mock(LojaRepository.class);
        produtos = mock(ProdutoRepository.class);
        pedidos = mock(PedidoRepository.class);
        itens = mock(PedidoItemRepository.class);
        taxas = mock(TaxaEntregaRepository.class);
        fidelidade = mock(FidelidadeService.class);
        pix = mock(PixService.class);
        devolucaoDeEstoque = mock(EstornoDaVendaService.class);
        integracoes = mock(IntegracaoCanalRepository.class);
        publicador = mock(PublicadorDeEventos.class);

        complementosDoCardapio = mock(ComplementoService.class);
        when(complementosDoCardapio.aplicar(anyLong(), any(), any()))
                .thenReturn(new ComplementoService.Escolha(BigDecimal.ZERO, List.of()));
        ComplementoService complementos = complementosDoCardapio;
        OperacaoService operacao = mock(OperacaoService.class);
        when(operacao.abertaAgora(anyLong())).thenReturn(true);

        controller = new PublicController(lojas, produtos, pedidos, itens, integracoes, pix,
                mock(ComplementoGrupoRepository.class), mock(ComplementoItemRepository.class),
                complementos, mock(CupomRepository.class), mock(DescontoService.class),
                new br.com.bora.security.FreioDoCardapio(0, 0), // freio desligado
                fidelidade, operacao, taxas, mock(InsumoService.class), devolucaoDeEstoque,
                new RegraDeAcesso(false), mock(ConfiguracaoLojaRepository.class),
                mock(RepetirPedidoService.class), mock(CategoriaOrdemService.class),
                publicador, gerenciadorFalso(), false);

        loja = new Loja();
        loja.id = LOJA;
        loja.ativo = true;
        when(lojas.findById(LOJA)).thenReturn(Optional.of(loja));

        acai = new Produto();
        acai.id = 10L;
        acai.lojaId = LOJA;
        acai.nome = "Açaí 500ml";
        acai.preco = new BigDecimal("20.00");
        acai.ativo = true;
        when(produtos.findById(10L)).thenReturn(Optional.of(acai));
        // O mock devolveria 0, que aqui significaria "acabou" e recusaria TODO pedido. Num banco de
        // verdade produto sem controle de estoque devolve 1 (ver EstoqueNaoPerdeBaixaIT).
        when(produtos.baixarEstoqueSeTiver(anyLong(), anyLong(), anyInt())).thenReturn(1);
        when(pedidos.save(any(Pedido.class))).thenAnswer(i -> {
            Pedido p = i.getArgument(0);
            if (p.id == null) p.id = 99L;
            return p;
        });
        when(fidelidade.resgatePossivel(anyLong(), any(), any())).thenReturn(BigDecimal.ZERO);
        gravarInstantaneos();
    }

    /** Executa de verdade o que esta dentro da transacao: mock puro devolveria null e nada rodaria. */
    private org.springframework.transaction.PlatformTransactionManager gerenciadorFalso() {
        var gerente = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(gerente.getTransaction(any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        return gerente;
    }

    private Map<String, Object> corpo(Object... extras) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itens", List.of(Map.of("produtoId", 10, "quantidade", 2)));
        m.put("clienteNome", "Maria");
        m.put("telefone", "15999990001");
        m.put("endereco", "Rua A, 1");
        for (int i = 0; i < extras.length; i += 2) m.put((String) extras[i], extras[i + 1]);
        return m;
    }

    private static jakarta.servlet.http.HttpServletRequest requisicao() {
        return mock(jakarta.servlet.http.HttpServletRequest.class);
    }

    /**
     * O pedido COMO ELE ESTAVA no instante em que o evento foi emitido.
     *
     * <h3>Por que um {@code ArgumentCaptor} nao serve aqui</h3>
     * <p>O captor guarda a <b>referencia</b> do objeto, nao uma copia. O {@code Pedido} continua
     * sendo alterado depois da chamada, entao o captor devolve o estado <b>final</b> — e uma
     * asserção sobre "o que o evento levou" passa mesmo com a emissao no lugar errado.</p>
     *
     * <p><b>Isto foi medido, nao deduzido:</b> movendo a emissao para antes do laco dos itens, onde
     * o {@code valorTotal} ainda e ZERO, o teste do total final continuava <b>verde</b>. Ele nao
     * media nada. Com o instantaneo, o mesmo mutante morre.</p>
     */
    private record Instantaneo(java.math.BigDecimal valorTotal, String codigo,
                               br.com.bora.entity.StatusPedido status,
                               java.time.OffsetDateTime canceladoEm) {}

    private final Map<String, Instantaneo> noMomentoDaChamada = new java.util.HashMap<>();

    /** Liga o instantaneo. Sempre ativo: asserção de ordem sem isto mede o estado final. */
    private void gravarInstantaneos() {
        doAnswer(inv -> {
            Pedido p = inv.getArgument(1);
            noMomentoDaChamada.put(inv.getArgument(0),
                    new Instantaneo(p.valorTotal, p.codigo, p.status, p.canceladoEm));
            return null;
        }).when(publicador).dePedido(anyString(), any(Pedido.class), anyMap());
    }

    private Instantaneo quandoEmitiu(String tipo) {
        Instantaneo i = noMomentoDaChamada.get(tipo);
        assertNotNull(i, "o evento " + tipo + " nao foi emitido");
        return i;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extrasDe(String tipo) {
        var c = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(publicador).dePedido(eq(tipo), any(Pedido.class), c.capture());
        return c.getValue();
    }

    // ============================================================== a venda nasce

    /** A premissa do buraco: antes desta entrega, nenhuma chamada chegava aqui. */
    @Test
    void oCHECKOUTdoCARDAPIOemitePedidoRecebido() {
        controller.pedirOnline(LOJA, corpo(), null, requisicao());

        verify(publicador).dePedido(eq("PedidoRecebido"), any(Pedido.class), anyMap());
    }

    /**
     * O CANAL VAI COMO CODIGO, nao como o rotulo da tela.
     *
     * <p>O {@code origem} do pedido e {@code "Cardápio Digital"} — com acento, e com espaco. Esse
     * texto ja fez o canal do cardapio ser contado como Instagram (o {@code IG} casava dentro de
     * "CARDÁPIO D-IG-ITAL") e depois ser contado como nada (o canal Site procurava {@code CARDAP} e
     * "CARDÁPIO" tem acento). Evento que o modulo vai assinar nao pode depender de rotulo.</p>
     */
    @Test
    void oCANALvaiComoCODIGOestavel_naoComoOrotuloAcentuado() {
        controller.pedirOnline(LOJA, corpo(), null, requisicao());

        assertEquals("CARDAPIO", extrasDe("PedidoRecebido").get("canal"));
    }

    /**
     * O EVENTO SAI COM O TOTAL FINAL — e e por isto que ele e o ultimo passo da transacao.
     *
     * <p>O pedido e gravado uma primeira vez com {@code valorTotal} ZERO, para ter id antes do laco
     * dos itens. Quem emitir o evento ali mandaria R$ 0,00 para o modulo financeiro. O frete, o
     * cupom e o cashback entram depois disso.</p>
     */
    @Test
    void oEVENTOlevaOtotalFINAL_naoOzeroDaPRIMEIRAgravacao() {
        TaxaEntrega t = new TaxaEntrega();
        t.bairro = "Centro";
        t.taxa = new BigDecimal("7.00");
        t.ativo = true;
        when(taxas.findByLojaIdAndAtivoTrueOrderByBairroAscIdDesc(LOJA)).thenReturn(List.of(t));
        when(taxas.findFirstByLojaIdAndBairroIgnoreCaseAndAtivoTrueOrderByIdDesc(LOJA, "Centro"))
                .thenReturn(Optional.of(t));

        controller.pedirOnline(LOJA, corpo("bairro", "Centro"), null, requisicao());

        // INSTANTANEO, nao captor: ver o javadoc de Instantaneo. Com captor este teste passava
        // tambem com a emissao antes do laco dos itens, onde o total ainda e ZERO.
        Instantaneo i = quandoEmitiu("PedidoRecebido");
        assertEquals(0, new BigDecimal("47.00").compareTo(i.valorTotal()),
                "2 x R$ 20 + R$ 7 de frete; zero aqui significa que o evento sai cedo demais");
        assertEquals("CD-99", i.codigo(), "sem o codigo o modulo nao tem como falar do pedido com o lojista");
        assertEquals(StatusPedido.RECEBIDO, i.status());
    }

    @Test
    void oEVENTOlevaOfreteEoQUEfoiABATIDO() {
        controller.pedirOnline(LOJA, corpo(), null, requisicao());

        Map<String, Object> e = extrasDe("PedidoRecebido");
        assertEquals(BigDecimal.ZERO, e.get("taxaEntrega"));
        assertEquals(BigDecimal.ZERO, e.get("desconto"));
        assertEquals(BigDecimal.ZERO, e.get("cashbackUsado"));
        assertEquals(false, e.get("retirada"));
    }

    @Test
    void aRETIRADAnoBALCAOvaiMARCADAnoEVENTO() {
        controller.pedirOnline(LOJA, corpo("tipoEntrega", "RETIRADA"), null, requisicao());

        assertEquals(true, extrasDe("PedidoRecebido").get("retirada"),
                "entrega e retirada dao trabalho diferente para o modulo de logistica");
    }

    // ============================================================== venda que nao aconteceu

    /**
     * VENDA RECUSADA NAO EMITE NADA — o caso que o outbox existe para evitar.
     *
     * <p>O {@code tx.execute} recusa no meio do laco de itens, a transacao volta atras e o pedido
     * nao existe. Se o evento tivesse sido emitido antes, o modulo processaria uma venda que nunca
     * houve: baixaria estoque de pedido inexistente, emitiria nota de venda desfeita.</p>
     */
    @Test
    void pedidoRECUSADOporFALTAdeESTOQUEnaoEmiteEVENTO() {
        when(produtos.baixarEstoqueSeTiver(anyLong(), anyLong(), anyInt())).thenReturn(0);

        assertThrows(ResponseStatusException.class,
                () -> controller.pedirOnline(LOJA, corpo(), null, requisicao()));

        verifyNoInteractions(publicador);
    }

    @Test
    void produtoDEoutraLOJAnaoEmiteEVENTO() {
        acai.lojaId = 2L;

        assertThrows(ResponseStatusException.class,
                () -> controller.pedirOnline(LOJA, corpo(), null, requisicao()));

        verifyNoInteractions(publicador);
        verify(itens, never()).save(any(PedidoItem.class));
    }

    /**
     * O SEGUNDO TOQUE NAO EMITE DE NOVO.
     *
     * <p>O cliente tocou "Confirmar" duas vezes e a idempotencia devolveu o pedido que ja existia,
     * sem gravar outro. Emitir aqui daria ao modulo duas vendas onde houve uma — estoque baixado em
     * dobro e faturamento inflado, pelo caminho exato que a idempotencia foi feita para fechar.</p>
     */
    @Test
    void oSEGUNDOtoqueIDEMPOTENTEnaoEmiteDEnovo() {
        Pedido jaGravado = new Pedido();
        jaGravado.id = 777L;
        jaGravado.lojaId = LOJA;
        jaGravado.codigo = "CD-777";
        jaGravado.valorTotal = new BigDecimal("40.00");
        jaGravado.tipoEntrega = "ENTREGA";
        when(pedidos.findByLojaIdAndChaveIdempotencia(LOJA, "chave-do-cliente"))
                .thenReturn(Optional.of(jaGravado));

        Map<String, Object> r = controller.pedirOnline(LOJA, corpo(), "chave-do-cliente", requisicao());

        assertEquals(777L, r.get("pedidoId"));
        verifyNoInteractions(publicador);
    }

    // ============================================================== o PIX

    private void lojaComPix() {
        loja.asaasApiKey = "chave";
        loja.asaasStatus = "ATIVO";
        loja.asaasWalletId = "w1";
    }

    /**
     * PIX PENDENTE E VENDA SEM DINHEIRO, e o evento tem de dizer isso.
     *
     * <p>Modulo financeiro que criar recebivel a partir de um {@code PedidoRecebido} sem olhar este
     * campo lanca receita que pode nunca entrar — e o cobrador cancela o PIX abandonado meia hora
     * depois.</p>
     */
    @Test
    void oPIXpendenteAVISAqueOdinheiroNAOentrou() {
        lojaComPix();
        when(pix.criarCobranca(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("paymentId", "pay_1"));

        controller.pedirOnline(LOJA, corpo("formaPagamento", "PIX", "cpf", "11144477735"),
                null, requisicao());

        assertEquals(true, extrasDe("PedidoRecebido").get("aguardandoPagamento"));
    }

    @Test
    void semPIXoEVENTOdizQueNAOhaPagamentoPendente() {
        controller.pedirOnline(LOJA, corpo(), null, requisicao());

        assertEquals(false, extrasDe("PedidoRecebido").get("aguardandoPagamento"));
    }

    /**
     * O PIX QUE NAO NASCEU: a venda e desfeita, e o cancelamento TEM de ir para a fila.
     *
     * <p>Aqui o {@code PedidoRecebido} ja foi emitido e sua transacao ja fechou. A venda e desfeita
     * por uma transacao <b>posterior</b>, entao nenhum rollback desfaz o evento: a fila ficaria com
     * uma venda que deixou de existir, e e o cancelamento que corrige a conta do consumidor.</p>
     */
    @Test
    void oPIXqueNAOnasceEmitePedidoCancelado() {
        lojaComPix();
        when(pix.criarCobranca(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Asaas fora do ar"));
        when(pedidos.findById(99L)).thenAnswer(i -> {
            Pedido p = new Pedido();
            p.id = 99L;
            p.lojaId = LOJA;
            p.codigo = "CD-99";
            p.status = StatusPedido.RECEBIDO;
            p.valorTotal = new BigDecimal("40.00");
            p.aguardandoPagamento = true;
            return Optional.of(p);
        });

        assertThrows(ResponseStatusException.class, () -> controller.pedirOnline(LOJA,
                corpo("formaPagamento", "PIX", "cpf", "11144477735"), null, requisicao()));

        verify(publicador).dePedido(eq("PedidoRecebido"), any(Pedido.class), anyMap());
        Instantaneo c = quandoEmitiu("PedidoCancelado");
        assertEquals(StatusPedido.CANCELADO, c.status(),
                "emitir com o status antigo mandaria o modulo arquivar uma venda que ele acha viva");
        assertNotNull(c.canceladoEm(), "a hora do cancelamento tem de estar gravada quando o evento sai");
        Map<String, Object> e = extrasDe("PedidoCancelado");
        assertEquals("CARDAPIO", e.get("canal"));
        assertEquals("RECEBIDO", e.get("statusAnterior"),
                "a FASE do cancelamento decide se o estoque volta ou vira perda (RN-EST-005)");
        assertEquals("SISTEMA", e.get("solicitante"),
                "nao foi o cliente nem o lojista que cancelou: foi o Asaas nao responder");
    }

    // ================================================== preco: sem preco nao vende (18/11/2026)

    /** Um segundo produto, com preço de verdade, para montar carrinho misto. */
    private Produto refrigerante(String preco) {
        Produto r = new Produto();
        r.id = 11L;
        r.lojaId = LOJA;
        r.nome = "Refrigerante";
        r.preco = new BigDecimal(preco);
        r.ativo = true;
        when(produtos.findById(11L)).thenReturn(Optional.of(r));
        return r;
    }

    private static Map<String, Object> umItem(int produtoId, int quantidade) {
        return Map.of("produtoId", produtoId, "quantidade", quantidade);
    }

    /**
     * O CASO QUE REALMENTE PERDIA DINHEIRO: carrinho MISTO.
     *
     * <h3>Eu diagnostiquei isto errado primeiro, e o teste me corrigiu</h3>
     * <p>Eu disse que produto sem preço "era vendido por R$ 0,00 no cardápio próprio". Não era —
     * não sozinho. Existe uma guarda em {@code PublicController} desde antes:
     * {@code if (total <= 0) throw "Pedido sem valor"}. Um carrinho com <b>só</b> o item sem preço
     * dava total zero e já era recusado.</p>
     *
     * <p>Mas a guarda olha o <b>TOTAL</b>, não o item. Então o buraco de verdade é este, e é pior do
     * que o pedido ser recusado: com um item sem preço <b>junto</b> de um item pago, o total fica
     * positivo, a guarda deixa passar, e o item sem preço vai embora a R$ 0,00 <b>dentro de um
     * pedido de aparência normal</b>. O lojista não tem como notar: ele vê um pedido que fechou.</p>
     *
     * <p>Medido: com {@code (prod.preco == null ? ZERO : prod.preco)} de volta, este teste grava o
     * pedido com total R$ 8,00 — os 2 açaís de graça e o refrigerante pago.</p>
     */
    @Test
    void oITEMsemPRECOnaoVIAJAdeGRACAdentroDEumPEDIDOpago() {
        acai.preco = null;
        refrigerante("8.00");

        assertThrows(ResponseStatusException.class, () -> controller.pedirOnline(LOJA,
                corpo("itens", List.of(umItem(10, 2), umItem(11, 1))), null, requisicao()));

        verify(publicador, never()).dePedido(eq("PedidoRecebido"), any(Pedido.class), anyMap());
    }

    /**
     * O item sem preço sozinho também é recusado — e agora com a mensagem que diz QUAL item.
     *
     * <p>Antes a recusa vinha da guarda do total, com o texto "Pedido sem valor": verdadeiro e
     * inútil, porque o cliente não tem como saber o que tirar do carrinho.</p>
     */
    @Test
    void aMENSAGEMdoSEMprecoDIZqualITEMeEHparaOCLIENTE() {
        acai.preco = null;

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.pedirOnline(LOJA, corpo(), null, requisicao()));

        assertTrue(e.getReason().contains("Açaí 500ml"), "o cliente precisa saber qual item tirar");
        assertFalse(e.getReason().toLowerCase().contains("digite"),
                "o cliente nao cadastra preco, a mensagem nao e para ele: " + e.getReason());
    }

    /**
     * O ZERO CONTINUA VENDENDO NO CARDÁPIO PRÓPRIO — é o "Monte Seu Açaí" da Zirá.
     *
     * <p>Medido no cardápio público em 18/11/2026: o item está a 0,00 nas lojas 18, 19 e 20, e o
     * grupo "Recipientes" tem {@code minimo: 1} com opções de R$ 12,90 a R$ 50,00. O preço sai do
     * complemento, que é o {@code extra} somado no unitário aqui. Se alguém endurecer a guarda para
     * {@code preco.signum() <= 0}, este teste reprova antes de chegar ao ar.</p>
     */
    @Test
    void oZEROcomCOMPLEMENTOcontinuaVENDENDOnoCARDAPIO() {
        acai.nome = "Monte Seu Açaí";
        acai.preco = BigDecimal.ZERO;
        // "Copo 500ml Puro", R$ 16,90, do grupo "Recipientes" (minimo 1).
        when(complementosDoCardapio.aplicar(anyLong(), any(), any()))
                .thenReturn(new ComplementoService.Escolha(new BigDecimal("16.90"), List.of()));

        controller.pedirOnline(LOJA, corpo(), null, requisicao());

        Instantaneo i = quandoEmitiu("PedidoRecebido");
        assertEquals(0, new BigDecimal("33.80").compareTo(i.valorTotal()),
                "2 x (0,00 do item + 16,90 do recipiente)");
    }
}
