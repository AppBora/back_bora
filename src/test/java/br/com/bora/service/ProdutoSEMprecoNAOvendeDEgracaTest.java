package br.com.bora.service;

import br.com.bora.dto.ItemPedidoRequest;
import br.com.bora.dto.NovoPedidoRequest;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.Produto;
import br.com.bora.repository.*;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Produto sem preço não é cadastrado, e não é vendido por R$ 0,00.
 *
 * <h2>O defeito (18/11/2026)</h2>
 * <p>{@code ProdutoService.criar} e {@code atualizar} validavam nome e categoria, e <b>nunca</b> o
 * preço. {@code produto.preco} é {@code numeric(12,2)} sem {@code not null} e sem {@code check}.
 * Os dois caminhos que gravam venda completavam o buraco:</p>
 * <ul>
 *   <li>{@code PedidoService.criar} — {@code (prod.preco != null ? prod.preco : ZERO)}</li>
 *   <li>{@code PublicController} (cardápio próprio) — {@code (prod.preco == null ? ZERO : prod.preco)}</li>
 * </ul>
 * <p>Ou seja: produto sem preço ia para a cozinha cobrando zero, com o total do pedido errado.</p>
 *
 * <h2>POR QUE ESTE ARQUIVO EXISTE, se o conserto já estava feito</h2>
 * <p>Porque eu consertei e a suíte não reclamou de nada — e isso não é um bom sinal, é a ausência de
 * medição. Apaguei as três guardas novas e rodei tudo: <b>1549 testes, 0 falhas</b>. Os três mutantes
 * sobreviveram. Os 13 testes que eu havia escrito mediam {@link PrecoDoCardapio} <b>isolado</b>, e
 * nenhum deles passava pelo cadastro nem pela venda. Guarda sem teste de caminho é guarda que o
 * próximo refactor apaga em silêncio.</p>
 *
 * <h2>E O ZERO CONTINUA VENDENDO</h2>
 * <p>{@link #oMONTEseuACAIcontinuaVENDENDO_zeroMAISoCOMPLEMENTO()} é o teste que me impede de
 * "endurecer" esta regra para {@code preco > 0}. Medido no cardápio público em 18/11/2026: nas lojas
 * 18, 19 e 20 da Zirá o "Monte Seu Açaí" está a <b>0,00</b> de propósito, porque quem carrega o preço
 * é o grupo "Recipientes" ({@code minimo: 1}, opções de R$ 12,90 a R$ 50,00).</p>
 */
class ProdutoSEMprecoNAOvendeDEgracaTest {

    private static final Long LOJA = 1L;

    // --- cadastro
    private ProdutoRepository produtoRepo;
    private ProdutoService cadastro;

    // --- venda pelo painel/PDV
    private PedidoRepository pedidoRepo;
    private PedidoItemRepository itemRepo;
    private ComplementoService complementos;
    private PedidoService venda;

    @BeforeEach
    void montar() {
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, LOJA, "ADMINISTRADOR_LOJA", "admin@bora.app"));

        produtoRepo = mock(ProdutoRepository.class);
        when(produtoRepo.save(any(Produto.class))).thenAnswer(i -> i.getArgument(0));
        cadastro = new ProdutoService(produtoRepo,
                mock(ProdutoInsumoRepository.class),
                mock(ComplementoGrupoRepository.class),
                mock(ComplementoItemRepository.class),
                ctx);

        pedidoRepo = mock(PedidoRepository.class);
        itemRepo = mock(PedidoItemRepository.class);
        complementos = mock(ComplementoService.class);
        InsumoService insumos = mock(InsumoService.class);
        FidelidadeService fidelidade = mock(FidelidadeService.class);
        venda = new PedidoService(pedidoRepo, itemRepo, produtoRepo, mock(ClienteRepository.class),
                mock(LogStatusRepository.class), mock(PlanoService.class), mock(IntegracaoService.class),
                mock(TaxaEntregaRepository.class), insumos, fidelidade, complementos, ctx,
                mock(EstornoDaVendaService.class), mock(PublicadorDeEventos.class),
                GerenciadorDeTransacaoFalso.novo());
        ReflectionTestUtils.setField(venda, "notifCliente", mock(NotificacaoClienteService.class));

        when(pedidoRepo.save(any(Pedido.class))).thenAnswer(i -> {
            Pedido p = i.getArgument(0);
            p.id = 500L;
            return p;
        });
        when(complementos.aplicar(anyLong(), any(Produto.class), any()))
                .thenReturn(new ComplementoService.Escolha(BigDecimal.ZERO, List.of()));
        when(insumos.consumirFicha(anyLong(), anyLong(), anyInt())).thenReturn(null);
        when(fidelidade.resgatePossivel(anyLong(), any(), any(BigDecimal.class))).thenReturn(BigDecimal.ZERO);
    }

    /** Um produto do jeito que o lojista manda do formulário. {@code preco} nulo = campo vazio. */
    private Produto doFormulario(String nome, BigDecimal preco) {
        Produto p = new Produto();
        p.nome = nome;
        p.categoria = "Açaí";
        p.preco = preco;
        return p;
    }

    private Produto noBanco(long id, String nome, BigDecimal preco) {
        Produto p = new Produto();
        p.id = id;
        p.lojaId = LOJA;
        p.nome = nome;
        p.categoria = "Açaí";
        p.preco = preco;
        when(produtoRepo.findById(id)).thenReturn(Optional.of(p));
        when(produtoRepo.findByIdAndLojaId(id, LOJA)).thenReturn(Optional.of(p));
        return p;
    }

    // ===================================================== 1) cadastrar

    @Test
    void criarSEMprecoEHrecusadoENADAehGRAVADO() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> cadastro.criar(doFormulario("Açaí 500ml", null)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("Açaí 500ml"), "a mensagem diz qual produto: " + e.getReason());
        verify(produtoRepo, never()).save(any(Produto.class));
    }

    @Test
    void criarComPRECOnegativoEHrecusado() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> cadastro.criar(doFormulario("Combo", new BigDecimal("-1.00"))));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(produtoRepo, never()).save(any(Produto.class));
    }

    /** ZERO é um cadastro válido: é o "Monte Seu Açaí" e é o brinde. */
    @Test
    void criarComPRECOzeroPASSA() {
        assertDoesNotThrow(() -> cadastro.criar(doFormulario("Monte Seu Açaí", BigDecimal.ZERO)));
        verify(produtoRepo).save(any(Produto.class));
    }

    // ===================================================== 2) editar

    /**
     * Apagar o campo preço numa edição NÃO pode apagar o preço que está no banco.
     *
     * <p>É o caso que mais aparece na prática: o lojista abre o produto para trocar a categoria, o
     * campo de preço volta vazio por qualquer motivo, ele salva. Antes disso virar erro, o produto
     * ficava sem preço e o cardápio público seguia vendendo.</p>
     */
    @Test
    void editarAPAGANDOoPRECOnaoAPAGAoPRECOdoBANCO() {
        Produto antes = noBanco(11L, "Açaí 500ml", new BigDecimal("24.90"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> cadastro.atualizar(11L, doFormulario("Açaí 500ml", null)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertEquals(0, new BigDecimal("24.90").compareTo(antes.preco), "o preco do banco foi mexido");
        verify(produtoRepo, never()).save(any(Produto.class));
    }

    @Test
    void editarComPRECOnegativoEHrecusado() {
        noBanco(11L, "Açaí 500ml", new BigDecimal("24.90"));

        assertThrows(ResponseStatusException.class,
                () -> cadastro.atualizar(11L, doFormulario("Açaí 500ml", new BigDecimal("-0.01"))));

        verify(produtoRepo, never()).save(any(Produto.class));
    }

    @Test
    void editarPARAzeroPASSA() {
        noBanco(11L, "Monte Seu Açaí", new BigDecimal("24.90"));

        assertDoesNotThrow(() -> cadastro.atualizar(11L, doFormulario("Monte Seu Açaí", BigDecimal.ZERO)));
        verify(produtoRepo).save(any(Produto.class));
    }

    // ===================================================== 3) vender

    /**
     * O CASO DE DINHEIRO: um produto legado sem preço não sai cobrando zero.
     *
     * <p>Medido no cardápio público em 18/11/2026: hoje não existe nenhum produto ativo sem preço nas
     * 6 lojas reais, então este defeito estava <b>latente</b>, não sangrando. Mas as portas que
     * criavam um estavam todas abertas, e a venda silenciava o resultado.</p>
     */
    @Test
    void venderPRODUTOsemPRECOehRECUSADOeNENHUMpedidoEHgravado() {
        noBanco(10L, "Açaí 500ml", null);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> venda.criar(new NovoPedidoRequest(null, "101", "Dinheiro", "Balcao", null, false,
                        List.of(new ItemPedidoRequest(10L, 2, null)), null, null, null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(pedidoRepo, never()).save(any(Pedido.class));
        verify(itemRepo, never()).saveAll(any());
    }

    @Test
    void venderPRODUTOcomPRECOnegativoEHrecusado() {
        noBanco(10L, "Combo errado", new BigDecimal("-10.00"));

        assertThrows(ResponseStatusException.class,
                () -> venda.criar(new NovoPedidoRequest(null, "102", "Dinheiro", "Balcao", null, false,
                        List.of(new ItemPedidoRequest(10L, 1, null)), null, null, null, null)));

        verify(pedidoRepo, never()).save(any(Pedido.class));
    }

    /**
     * O TESTE QUE PROTEGE A ZIRÁ: preço 0,00 + complemento de R$ 16,90 cobra R$ 16,90.
     *
     * <p>Se alguém trocar a regra por {@code preco > 0}, este teste reprova — e é o único aviso de
     * que "Monte Seu Açaí" acabou de parar de vender em três lojas reais.</p>
     */
    @Test
    void oMONTEseuACAIcontinuaVENDENDO_zeroMAISoCOMPLEMENTO() {
        Produto monte = noBanco(50L, "Monte Seu Açaí", BigDecimal.ZERO);
        // O grupo "Recipientes" tem minimo 1: "Copo 500ml Puro" custa 16,90.
        when(complementos.aplicar(eq(LOJA), eq(monte), any()))
                .thenReturn(new ComplementoService.Escolha(new BigDecimal("16.90"), List.of()));

        venda.criar(new NovoPedidoRequest(null, "103", "Dinheiro", "Balcao", null, false,
                List.of(new ItemPedidoRequest(50L, 1, List.of(1667L))), null, null, null, null));

        ArgumentCaptor<Pedido> p = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidoRepo, atLeastOnce()).save(p.capture());
        assertEquals(0, new BigDecimal("16.90").compareTo(p.getValue().valorTotal),
                "o complemento tem de ser o preco do item");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PedidoItem>> itens = ArgumentCaptor.forClass(List.class);
        verify(itemRepo).saveAll(itens.capture());
        assertEquals(0, new BigDecimal("16.90").compareTo(itens.getValue().get(0).getPrecoUnitario()),
                "o unitario gravado tem de ser 0 + 16,90");
    }

    /** Dois do mesmo "Monte Seu Açaí": a quantidade multiplica o complemento, não o zero. */
    @Test
    void doisMONTEseuACAIcobramODOBROdoCOMPLEMENTO() {
        Produto monte = noBanco(50L, "Monte Seu Açaí", BigDecimal.ZERO);
        when(complementos.aplicar(eq(LOJA), eq(monte), any()))
                .thenReturn(new ComplementoService.Escolha(new BigDecimal("16.90"), List.of()));

        venda.criar(new NovoPedidoRequest(null, "104", "Dinheiro", "Balcao", null, false,
                List.of(new ItemPedidoRequest(50L, 2, List.of(1667L))), null, null, null, null));

        ArgumentCaptor<Pedido> p = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidoRepo, atLeastOnce()).save(p.capture());
        assertEquals(0, new BigDecimal("33.80").compareTo(p.getValue().valorTotal));
    }

    /** O brinde de verdade: zero sem complemento nenhum sai por zero, e isso é o combinado. */
    @Test
    void oBRINDEgratisSAIporZEROeIssoEHoCOMBINADO() {
        noBanco(60L, "Brinde Grátis", BigDecimal.ZERO);

        venda.criar(new NovoPedidoRequest(null, "105", "Dinheiro", "Balcao", null, false,
                List.of(new ItemPedidoRequest(60L, 1, null)), null, null, null, null));

        ArgumentCaptor<Pedido> p = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidoRepo, atLeastOnce()).save(p.capture());
        assertEquals(0, BigDecimal.ZERO.compareTo(p.getValue().valorTotal),
                "brinde e de graca de proposito");
    }
}
