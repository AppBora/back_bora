package br.com.bora.service;

import br.com.bora.dto.InboundOrder;
import br.com.bora.dto.ItemPedidoRequest;
import br.com.bora.dto.NovoPedidoRequest;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.Produto;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.*;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Os CINCO ganchos de evento do {@link PedidoService} que nenhum teste media.
 *
 * <h2>Por que este arquivo existe</h2>
 * <p>A revisão técnica disse que os ganchos do {@code PedidoService} não tinham teste. Eu medi, um
 * por um: comentei cada chamada a {@code eventos.dePedido} e rodei a suíte inteira. Resultado em
 * 18/11/2026:</p>
 *
 * <pre>
 * criar (painel/PDV) ............ 1563 testes, 0 falhas   MUTANTE VIVO
 * criarExterno (marketplace) .... 1563 testes, 0 falhas   MUTANTE VIVO
 * cancelarPorMarketplace ........ 1563 testes, 0 falhas   MUTANTE VIVO
 * avancarPorMarketplace ......... 1563 testes, 3 falhas   coberto
 * alterarStatus (painel) ........ 1563 testes, 0 falhas   MUTANTE VIVO
 * excluir ....................... 1563 testes, 0 falhas   MUTANTE VIVO
 * </pre>
 *
 * <p>Cinco de seis podiam ser apagados sem uma única reclamação. O único coberto é o que eu havia
 * consertado hoje — e só porque escrevi teste junto.</p>
 *
 * <h2>Por que isso importa AGORA, e não "quando o módulo existir"</h2>
 * <p>É o caminho inteiro do dinheiro do painel e dos marketplaces: toda venda lançada no balcão,
 * todo pedido de iFood/99, todo clique de status, todo cancelamento, toda exclusão. Hoje ninguém
 * consome a fila, então um gancho errado não quebra nada e <b>não aparece</b>. No dia em que a chave
 * ligar, o módulo passa a decidir estoque e contabilidade por estes eventos — e um gancho que some,
 * ou que sai com o estado errado, vira divergência que ninguém consegue rastrear até aqui.</p>
 *
 * <h2>Instantâneo, não ArgumentCaptor</h2>
 * <p>O captor guarda a <b>referência</b> do {@code Pedido}, não uma cópia. O objeto continua sendo
 * alterado depois da emissão, então o captor devolve o estado <b>final</b> — e uma asserção sobre "o
 * que o evento levou" passa mesmo com a emissão no lugar errado. Isso foi <b>medido</b> hoje, em
 * {@code AvendaDoCardapioPROPRIOemiteEventoTest}: movendo a emissão para antes do laço dos itens,
 * onde o total ainda era zero, o teste do total final continuava verde. Aqui, o dublê copia os
 * campos no instante da chamada.</p>
 */
class TodosOSganchosDOpedidoEMITEMeventoTest {

    private static final Long LOJA = 1L;

    private PedidoRepository repo;
    private PedidoItemRepository itemRepo;
    private ProdutoRepository produtos;
    private LogStatusRepository logs;
    private IntegracaoService integracoes;
    private EstornoDaVendaService devolucao;
    private ComplementoService complementos;
    private FidelidadeService fidelidade;
    private PublicadorDeEventos publicador;
    private ClienteRepository clientes;
    private AuthContext ctx;
    private PedidoService service;

    /** O pedido COMO ELE ESTAVA no instante em que cada evento saiu. */
    private record Instantaneo(StatusPedido status, BigDecimal valorTotal, Long id,
                               OffsetDateTime canceladoEm, String motivoCancelamento,
                               Map<String, Object> extras) {}

    private final Map<String, Instantaneo> quandoSaiu = new HashMap<>();
    private final java.util.List<String> ordemDosEventos = new java.util.ArrayList<>();

    @BeforeEach
    void montar() {
        repo = mock(PedidoRepository.class);
        itemRepo = mock(PedidoItemRepository.class);
        produtos = mock(ProdutoRepository.class);
        logs = mock(LogStatusRepository.class);
        integracoes = mock(IntegracaoService.class);
        devolucao = mock(EstornoDaVendaService.class);
        complementos = mock(ComplementoService.class);
        fidelidade = mock(FidelidadeService.class);
        publicador = mock(PublicadorDeEventos.class);
        clientes = mock(ClienteRepository.class);
        ctx = mock(AuthContext.class);

        service = new PedidoService(repo, itemRepo, produtos, clientes, logs,
                mock(PlanoService.class), integracoes, mock(TaxaEntregaRepository.class),
                mock(InsumoService.class), fidelidade, complementos, ctx, devolucao, publicador,
                GerenciadorDeTransacaoFalso.novo());
        ReflectionTestUtils.setField(service, "notifCliente", mock(NotificacaoClienteService.class));

        when(ctx.lojaId()).thenReturn(LOJA);
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, LOJA, "ADMINISTRADOR_LOJA", "admin@bora.app"));
        when(repo.save(any(Pedido.class))).thenAnswer(i -> {
            Pedido p = i.getArgument(0);
            if (p.id == null) p.id = 500L;
            return p;
        });
        when(repo.avancarSeAindaEsta(any(), any(), any(), any(), any())).thenReturn(1);
        when(complementos.aplicar(anyLong(), any(Produto.class), any()))
                .thenReturn(new ComplementoService.Escolha(BigDecimal.ZERO, List.of()));
        when(fidelidade.resgatePossivel(anyLong(), any(), any(BigDecimal.class))).thenReturn(BigDecimal.ZERO);
        // `criarExterno` CADASTRA o cliente do marketplace antes de gravar o pedido. Mock puro
        // devolve null e o metodo estoura ao ler o id -- erro que nao tem nada a ver com evento.
        when(clientes.save(any(br.com.bora.entity.Cliente.class))).thenAnswer(i -> {
            br.com.bora.entity.Cliente c = i.getArgument(0);
            if (c.id == null) c.id = 300L;
            return c;
        });

        gravarInstantaneos();
    }

    @SuppressWarnings("unchecked")
    private void gravarInstantaneos() {
        doAnswer(inv -> {
            String tipo = inv.getArgument(0);
            Pedido p = inv.getArgument(1);
            quandoSaiu.put(tipo, new Instantaneo(p.status, p.valorTotal, p.id, p.canceladoEm,
                    p.motivoCancelamento, new java.util.LinkedHashMap<>((Map<String, Object>) inv.getArgument(2))));
            ordemDosEventos.add(tipo);
            return null;
        }).when(publicador).dePedido(anyString(), any(Pedido.class), anyMap());
    }

    private Instantaneo evento(String tipo) {
        Instantaneo i = quandoSaiu.get(tipo);
        assertNotNull(i, "o evento " + tipo + " NAO foi emitido. Saiu: " + ordemDosEventos);
        return i;
    }

    private Produto produtoDe(long id, String preco) {
        Produto p = new Produto();
        p.id = id;
        p.lojaId = LOJA;
        p.nome = "Açaí 500ml";
        p.preco = new BigDecimal(preco);
        when(produtos.findByIdAndLojaId(id, LOJA)).thenReturn(Optional.of(p));
        return p;
    }

    private Pedido noBanco(long id, StatusPedido status) {
        Pedido p = new Pedido();
        p.id = id;
        p.lojaId = LOJA;
        p.status = status;
        p.valorTotal = new BigDecimal("40.00");
        when(repo.findByIdAndLojaId(id, LOJA)).thenReturn(Optional.of(p));
        return p;
    }

    private Pedido doMarketplace(long id, StatusPedido status) {
        Pedido p = noBanco(id, status);
        p.canalExterno = "IFOOD";
        p.idExterno = "ext-1022";
        when(repo.findFirstByLojaIdAndCanalExternoAndIdExterno(LOJA, "IFOOD", "ext-1022"))
                .thenReturn(Optional.of(p));
        return p;
    }

    // ============================================================== 1) criar (painel/PDV)

    /**
     * GANCHO 1: toda venda lançada no balcão emite {@code PedidoRecebido}, canal {@code PAINEL}.
     *
     * <p>O canal vai como código estável, não como rótulo de tela: o {@code origem} deste pedido é
     * texto livre que o operador escolhe, e evento que um módulo vai assinar não pode depender de
     * rótulo (foi assim que o cardápio já foi contado como Instagram).</p>
     */
    @Test
    void ganchoUM_aVENDAdoBALCAOemitePedidoRecebido() {
        produtoDe(10L, "20.00");

        service.criar(new NovoPedidoRequest(null, "101", "Dinheiro", "Balcao", null, false,
                List.of(new ItemPedidoRequest(10L, 2, null)), null, null, null, null), null);

        Instantaneo i = evento("PedidoRecebido");
        assertEquals("PAINEL", i.extras().get("canal"));
    }

    /**
     * O EVENTO LEVA O TOTAL FINAL E O ID — não o estado da primeira gravação.
     *
     * <p>É a asserção que só funciona com instantâneo. Com {@code ArgumentCaptor} ela passaria mesmo
     * se o evento saísse antes do laço dos itens, quando o total ainda é zero e o id ainda é nulo.</p>
     */
    @Test
    void ganchoUM_oEVENTOlevaOtotalFINALeOidJAgravado() {
        produtoDe(10L, "20.00");

        service.criar(new NovoPedidoRequest(null, "102", "Dinheiro", "Balcao", null, false,
                List.of(new ItemPedidoRequest(10L, 2, null)), null, null, null, null), null);

        Instantaneo i = evento("PedidoRecebido");
        assertEquals(0, new BigDecimal("40.00").compareTo(i.valorTotal()), "2 x 20,00");
        assertNotNull(i.id(), "o evento saiu antes de o pedido ter id: o modulo nao teria o que casar");
        assertEquals(StatusPedido.RECEBIDO, i.status());
    }

    /** Venda recusada não emite: o produto nem é da loja, então não houve venda. */
    @Test
    void ganchoUM_vendaRECUSADAnaoEMITE() {
        when(produtos.findByIdAndLojaId(anyLong(), anyLong())).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.criar(
                new NovoPedidoRequest(null, "103", "Dinheiro", "Balcao", null, false,
                        List.of(new ItemPedidoRequest(99L, 1, null)), null, null, null, null), null));

        verifyNoInteractions(publicador);
    }

    // ============================================================== 2) criarExterno (marketplace)

    /** GANCHO 2: pedido de iFood/99 emite {@code PedidoRecebido} com o canal e o id de lá. */
    @Test
    void ganchoDOIS_oPEDIDOdoMARKETPLACEemitePedidoRecebido() {
        service.criarExterno(LOJA, "IFOOD", "iFood", inbound("ext-9001"));

        Instantaneo i = evento("PedidoRecebido");
        assertEquals("IFOOD", i.extras().get("canal"));
        assertEquals("ext-9001", i.extras().get("idExterno"),
                "sem o id de lá o módulo não casa este evento com o pedido do marketplace");
    }

    /**
     * REENVIO NÃO EMITE DE NOVO. O iFood reenvia o mesmo pedido no evento de confirmação.
     *
     * <p>Sem isto, um reenvio produziria dois {@code PedidoRecebido} para uma venda — e o módulo
     * contaria a venda duas vezes, que é a forma mais cara de errar.</p>
     */
    @Test
    void ganchoDOIS_oREENVIOdoMARKETPLACEnaoEMITEdeNOVO() {
        doMarketplace(99L, StatusPedido.EM_PREPARO);

        service.criarExterno(LOJA, "IFOOD", "iFood", inbound("ext-1022"));

        assertTrue(ordemDosEventos.isEmpty(),
                "pedido reenviado emitiu de novo: o modulo contaria a venda 2x. Saiu: " + ordemDosEventos);
    }

    /** Canal nulo não vira evento sem canal: vira {@code MARKETPLACE}, que é legível. */
    @Test
    void ganchoDOIS_canalNULOviraMARKETPLACEenaoVAZIO() {
        service.criarExterno(LOJA, null, "?", inbound("ext-9002"));

        assertEquals("MARKETPLACE", evento("PedidoRecebido").extras().get("canal"));
    }

    // ============================================================== 3) cancelarPorMarketplace

    /**
     * GANCHO 3: cancelamento vindo do marketplace emite {@code PedidoCancelado} com a FASE.
     *
     * <p>A fase é o que decide, do outro lado, se o estoque volta para a prateleira ou vira perda
     * (RN-EST-005). Um cancelamento sem fase obriga o módulo a adivinhar.</p>
     */
    @Test
    void ganchoTRES_oCANCELAMENTOdoMARKETPLACEemiteComAFASE() {
        doMarketplace(99L, StatusPedido.EM_PREPARO);

        assertTrue(service.cancelarPorMarketplace(LOJA, "IFOOD", "ext-1022", "item indisponivel"));

        Instantaneo i = evento("PedidoCancelado");
        assertEquals("EM_PREPARO", i.extras().get("fase"), "a fase em que o pedido estava");
        assertEquals("MARKETPLACE", i.extras().get("solicitante"),
                "nao foi o lojista: quem cancelou foi o marketplace");
        assertEquals("item indisponivel", i.extras().get("motivo"));
    }

    /**
     * O EVENTO SAI COM O PEDIDO JÁ CANCELADO, não com o status antigo.
     *
     * <p>Emitir antes de gravar mandaria o módulo arquivar uma venda que ele ainda vê viva.</p>
     */
    @Test
    void ganchoTRES_oEVENTOsaiComOpedidoJAcanceladoEcomAHORA() {
        doMarketplace(99L, StatusPedido.CONFIRMADO);

        service.cancelarPorMarketplace(LOJA, "IFOOD", "ext-1022", "cliente desistiu");

        Instantaneo i = evento("PedidoCancelado");
        assertEquals(StatusPedido.CANCELADO, i.status());
        assertNotNull(i.canceladoEm(), "a hora do cancelamento tem de estar gravada quando o evento sai");
    }

    /** Pedido que já estava cancelado não emite de novo: o módulo devolveria estoque em dobro. */
    @Test
    void ganchoTRES_pedidoJAcanceladoNAOemiteDEnovo() {
        doMarketplace(99L, StatusPedido.CANCELADO);

        assertFalse(service.cancelarPorMarketplace(LOJA, "IFOOD", "ext-1022", "de novo"));
        assertTrue(ordemDosEventos.isEmpty(), "emitiu 2x: estoque voltaria em dobro");
    }

    /** Pedido ENTREGUE não é cancelável pelo marketplace, e não emite. */
    @Test
    void ganchoTRES_pedidoENTREGUEnaoEMITE() {
        doMarketplace(99L, StatusPedido.ENTREGUE);

        assertFalse(service.cancelarPorMarketplace(LOJA, "IFOOD", "ext-1022", "tarde demais"));
        assertTrue(ordemDosEventos.isEmpty());
    }

    // ============================================================== 4) alterarStatus (painel)

    /**
     * GANCHO 4: o clique do lojista emite o evento com o NOME do desfecho, não um genérico.
     *
     * <p>{@code eventoDeStatus} traduz o status para o nome do catálogo. Um módulo que recebesse
     * sempre {@code PedidoStatusAlterado} teria de reimplementar essa tradução — e as duas versões
     * divergiriam no primeiro status novo.</p>
     */
    @Test
    void ganchoQUATRO_cadaSTATUSsaiComOnomeDOdesfecho() {
        Map<StatusPedido, String> esperado = Map.of(
                StatusPedido.CONFIRMADO, "PedidoConfirmado",
                StatusPedido.EM_PREPARO, "PedidoEmPreparo",
                StatusPedido.PRONTO, "PedidoPronto",
                StatusPedido.ENTREGUE, "PedidoFinalizado");

        esperado.forEach((status, nomeDoEvento) -> {
            quandoSaiu.clear();
            ordemDosEventos.clear();
            noBanco(77L, StatusPedido.RECEBIDO);

            service.alterarStatus(77L, status, null);

            Instantaneo i = evento(nomeDoEvento);
            assertEquals("RECEBIDO", i.extras().get("statusAnterior"));
            assertEquals(status.name(), i.extras().get("statusNovo"));
        });
    }

    /** ENTREGUE vira {@code PedidoFinalizado}: é o nome do catálogo, e não "PedidoEntregue". */
    @Test
    void ganchoQUATRO_oENTREGUEviraPedidoFinalizado() {
        noBanco(77L, StatusPedido.PRONTO);

        service.alterarStatus(77L, StatusPedido.ENTREGUE, null);

        Instantaneo i = evento("PedidoFinalizado");
        assertEquals(StatusPedido.ENTREGUE, i.status(), "o status tem de estar gravado quando sai");
    }

    /** Cancelar pelo painel emite {@code PedidoCancelado} com o motivo que o lojista digitou. */
    @Test
    void ganchoQUATRO_cancelarPELOpainelLEVAoMOTIVO() {
        noBanco(77L, StatusPedido.EM_PREPARO);

        service.alterarStatus(77L, StatusPedido.CANCELADO, "faltou ingrediente");

        Instantaneo i = evento("PedidoCancelado");
        assertEquals("faltou ingrediente", i.extras().get("motivo"));
        assertEquals("EM_PREPARO", i.extras().get("statusAnterior"),
                "e a fase que decide se o estoque volta ou vira perda");
        assertEquals(StatusPedido.CANCELADO, i.status());
    }

    /**
     * O MARKETPLACE RECUSANDO O CANCELAMENTO NÃO EMITE.
     *
     * <p>O pedido continua vivo nos dois lados, então não houve desfecho nenhum. Um evento aqui
     * mandaria o módulo devolver estoque de uma venda que não foi cancelada.</p>
     */
    @Test
    void ganchoQUATRO_marketplaceRECUSANDOoCANCELAMENTOnaoEMITE() {
        Pedido p = doMarketplace(77L, StatusPedido.EM_PREPARO);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "o iFood nao aceita"))
                .when(integracoes).cancelarNoMarketplace(any(Pedido.class), anyString());

        assertThrows(ResponseStatusException.class,
                () -> service.alterarStatus(77L, StatusPedido.CANCELADO, "item indisponivel"));

        assertTrue(ordemDosEventos.isEmpty(), "emitiu sem o cancelamento ter acontecido");
        assertEquals(StatusPedido.EM_PREPARO, p.status);
    }

    /** Cancelamento sem motivo é recusado antes de qualquer coisa, e não emite. */
    @Test
    void ganchoQUATRO_cancelamentoSEMmotivoNAOemite() {
        noBanco(77L, StatusPedido.EM_PREPARO);

        assertThrows(ResponseStatusException.class,
                () -> service.alterarStatus(77L, StatusPedido.CANCELADO, "   "));

        assertTrue(ordemDosEventos.isEmpty());
    }

    // ============================================================== 5) excluir

    /**
     * GANCHO 5: excluir emite {@code PedidoCancelado} ANTES de o pedido sumir do banco.
     *
     * <p>Excluir é diferente de cancelar: o pedido <b>some</b>. Para quem consome eventos, some sem
     * aviso — e um módulo que já tivesse baixado estoque por ele nunca saberia que precisa devolver.
     * Por isso o evento sai enquanto o pedido ainda existe para ser lido, com o estado que ele
     * tinha.</p>
     */
    @Test
    void ganchoCINCO_excluirEMITEcomAfaseEaMARCAdeEXCLUSAO() {
        noBanco(77L, StatusPedido.EM_PREPARO);

        service.excluir(77L);

        Instantaneo i = evento("PedidoCancelado");
        assertEquals("EM_PREPARO", i.extras().get("fase"));
        assertEquals("LOJA", i.extras().get("solicitante"));
        assertEquals(Boolean.TRUE, i.extras().get("exclusao"),
                "o modulo precisa distinguir 'cancelado' de 'apagado': o pedido nao existe mais aqui");
        assertNotNull(i.id(), "sem id o modulo nao tem como achar o que desfazer");
    }

    /**
     * EXCLUIR UM PEDIDO JÁ CANCELADO NÃO EMITE DE NOVO (achado pelo QA hoje, medido).
     *
     * <p>Cancelar e depois excluir é o caminho natural — cancela o pedido de teste, depois apaga — e
     * produzia {@code [PedidoCancelado, PedidoCancelado]}. É a mesma razão que faz o estorno não
     * rodar de novo: o módulo devolveria estoque em dobro.</p>
     */
    @Test
    void ganchoCINCO_excluirPEDIDOjaCANCELADOnaoEMITEsegundoCANCELAMENTO() {
        noBanco(77L, StatusPedido.EM_PREPARO);
        service.alterarStatus(77L, StatusPedido.CANCELADO, "cliente desistiu");
        assertEquals(List.of("PedidoCancelado"), ordemDosEventos, "a premissa: um cancelamento ate aqui");

        service.excluir(77L);

        assertEquals(List.of("PedidoCancelado"), ordemDosEventos,
                "a exclusao emitiu um SEGUNDO cancelamento: o modulo devolveria estoque em dobro");
        verify(devolucao, times(1)).devolver(any(Pedido.class), any(), anyBoolean());
    }

    /** Pedido de outra loja não é encontrado, não é excluído e não emite. */
    @Test
    void ganchoCINCO_pedidoDEoutraLOJAnaoEMITE() {
        when(repo.findByIdAndLojaId(anyLong(), anyLong())).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.excluir(77L));

        verifyNoInteractions(publicador);
    }

    // ============================================================== a trava geral

    /**
     * A PREMISSA DESTE ARQUIVO INTEIRO: o dublê de fato intercepta as emissões.
     *
     * <p>Se {@code gravarInstantaneos} parar de casar a assinatura de {@code dePedido} — uma
     * sobrecarga nova, uma mudança de tipo — todos os testes acima que afirmam "não emitiu" ficariam
     * <b>verdes por não medir nada</b>, que é pior que vermelho. Este teste exige ver uma emissão
     * chegar.</p>
     */
    @Test
    void oDUBLEdeFATOinterceptaASemissoes() {
        produtoDe(10L, "20.00");

        service.criar(new NovoPedidoRequest(null, "999", "Dinheiro", "Balcao", null, false,
                List.of(new ItemPedidoRequest(10L, 1, null)), null, null, null, null), null);

        assertFalse(ordemDosEventos.isEmpty(),
                "o duble nao interceptou nada: as assercoes de 'nao emitiu' deste arquivo nao medem nada");
        assertFalse(quandoSaiu.isEmpty());
    }

    private static InboundOrder inbound(String idExterno) {
        return new InboundOrder(idExterno, "Cliente", "62999990000", "Rua X, 1", "Centro",
                "Pago online", "Pedido #1022", new BigDecimal("27"), List.of(),
                new BigDecimal("5"), "1022");
    }
}
