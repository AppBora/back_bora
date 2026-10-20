package br.com.bora.service;

import br.com.bora.dto.InboundOrder;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.Produto;
import br.com.bora.repository.*;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pedido que vem do iFood/99 tem que mexer no estoque e gravar o custo.
 *
 * <h2>O defeito que isto trava (medido em 20/10/2026)</h2>
 * <p>O {@code criarExterno} montava os itens com descrição, quantidade e preço — e <b>nada mais</b>.
 * Sem {@code produtoId}, nenhuma linha de estoque se mexia: nem o produto, nem os insumos da ficha.
 * E sem {@code custoUnitario}, o item nascia com custo nulo, então CMV e margem ignoravam toda venda
 * de marketplace. Numa loja em que o delivery é o canal que mais vende, isso é o estoque do painel
 * errado o dia inteiro e o relatório de lucro contando metade das vendas.</p>
 *
 * <h2>A causa</h2>
 * <p>Não havia como saber QUAL produto da loja foi vendido. O {@code InboundItem} só trazia o nome
 * montado para a tela — com complemento e observação colados ("Açaí 500ml (+ Granola) — obs: sem
 * granola") — que não casa com {@code produto.nome} em nenhum item com adicional. O
 * {@code externalCode} vinha no payload e era jogado fora.</p>
 *
 * <h2>O que cada teste prova</h2>
 * <p>Que o código casa, que o nome limpo casa quando não há código, que nome ambíguo NÃO casa, e —
 * o mais importante — que um item que não casou <b>nunca</b> derruba o pedido: comida vendida entra
 * no sistema de qualquer jeito, só sem baixa.</p>
 */
class EstoqueDoMarketplaceTest {

    private ProdutoRepository produtos;
    private InsumoService insumos;
    private PedidoItemRepository itemRepo;
    private PedidoService service;
    private final List<PedidoItem> gravados = new ArrayList<>();

    @BeforeEach
    void montar() {
        produtos = mock(ProdutoRepository.class);
        insumos = mock(InsumoService.class);
        itemRepo = mock(PedidoItemRepository.class);
        PedidoRepository repo = mock(PedidoRepository.class);
        AuthContext ctx = mock(AuthContext.class);
        ClienteRepository clientes = mock(ClienteRepository.class);

        service = new PedidoService(repo, itemRepo, produtos,
                clientes, mock(LogStatusRepository.class), mock(PlanoService.class),
                mock(IntegracaoService.class), mock(TaxaEntregaRepository.class), insumos,
                mock(FidelidadeService.class), mock(ComplementoService.class), ctx,
                mock(EstornoDaVendaService.class), GerenciadorDeTransacaoFalso.novo());
        ReflectionTestUtils.setField(service, "notifCliente", mock(NotificacaoClienteService.class));

        when(ctx.lojaId()).thenReturn(1L);
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, 1L, "ADMINISTRADOR_LOJA", "a@b.c"));
        when(repo.findFirstByLojaIdAndCanalExternoAndIdExterno(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        // O cliente do marketplace e criado na hora; sem isto o upsert recebe nulo do mock.
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
        gravados.clear();
        when(itemRepo.saveAll(any())).thenAnswer(inv -> {
            ((Iterable<PedidoItem>) inv.getArgument(0)).forEach(gravados::add);
            return List.of();
        });
    }

    private Produto produto(long id, String nome, String codigo, String custo) {
        Produto p = new Produto();
        p.id = id;
        p.lojaId = 1L;
        p.nome = nome;
        p.codigoExterno = codigo;
        p.custo = custo == null ? null : new BigDecimal(custo);
        p.estoque = 10;
        return p;
    }

    /** Um pedido de um item só, com o nome que a tela vê e o nome/código do cadastro. */
    private void chega(String nomeDaTela, String codigo, String nomeLimpo, int qtd) {
        InboundOrder in = new InboundOrder("ped-1", "Cliente", "62999990000", "Rua X, 1", "Centro",
                "Pago online", null, new BigDecimal("30"),
                List.of(new InboundOrder.InboundItem(nomeDaTela, qtd, new BigDecimal("30"),
                        codigo, nomeLimpo)),
                BigDecimal.ZERO, "1022");
        service.criarExterno(1L, "IFOOD", "iFood", in);
    }

    @Test
    void oCodigoDoMarketplaceCasaEBaixaEstoque() {
        when(produtos.findByLojaId(1L)).thenReturn(List.of(
                produto(11L, "Açaí 500ml", "7470", "8.50"),
                produto(12L, "Água", "5307", "1.20")));
        when(insumos.consumirFicha(anyLong(), anyLong(), anyInt())).thenReturn(null);

        chega("Açaí 500ml (+ Granola) — obs: sem cebola", "7470", "Açaí 500ml", 2);

        verify(produtos).baixarEstoque(11L, 1L, 2);
        assertEquals(1, gravados.size());
        assertEquals(11L, gravados.get(0).getProdutoId());
        assertEquals(new BigDecimal("8.50"), gravados.get(0).getCustoUnitario());
        assertEquals(Boolean.FALSE, gravados.get(0).getConsumiuFicha());
    }

    @Test
    void oNomeMontadoParaATelaNaoServeParaCasar_oNomeLimpoSim() {
        // Este é o motivo de existir o `nomeLimpo`. O nome da tela traz os adicionais colados; se o
        // casamento usasse ele, nenhum item com complemento acharia o produto — e numa açaiteria é
        // quase todo item.
        when(produtos.findByLojaId(1L)).thenReturn(List.of(produto(11L, "Açaí 500ml", null, "8.50")));
        when(insumos.consumirFicha(anyLong(), anyLong(), anyInt())).thenReturn(null);

        chega("Açaí 500ml (+ Granola, + Leite Ninho) — obs: sem banana", null, "Açaí 500ml", 1);

        verify(produtos).baixarEstoque(11L, 1L, 1);
    }

    @Test
    void acentoCaixaEEspacoSobrandoNaoImpedemOCasamento() {
        // O lojista digita "ACAI 500 ML" aqui e "Açaí 500 ml" no iFood. É a mesma coisa.
        when(produtos.findByLojaId(1L)).thenReturn(List.of(produto(11L, "  ACAI   500 ml ", null, "8.50")));
        when(insumos.consumirFicha(anyLong(), anyLong(), anyInt())).thenReturn(null);

        chega("Açaí 500 ML", null, "Açaí 500 ML", 1);

        verify(produtos).baixarEstoque(11L, 1L, 1);
    }

    @Test
    void oCodigoGanhaDoNome() {
        // Se o lojista preencheu o código, é nele que se confia: foi ele que cadastrou os dois lados.
        when(produtos.findByLojaId(1L)).thenReturn(List.of(
                produto(11L, "Açaí 500ml", "OUTRO", "8.50"),
                produto(12L, "Combo da casa", "7470", "12.00")));
        when(insumos.consumirFicha(anyLong(), anyLong(), anyInt())).thenReturn(null);

        chega("Açaí 500ml", "7470", "Açaí 500ml", 1);

        verify(produtos).baixarEstoque(12L, 1L, 1);
        verify(produtos, never()).baixarEstoque(eq(11L), anyLong(), anyInt());
    }

    @Test
    void comFichaTecnicaConsomeOsInsumosENaoOProduto() {
        when(produtos.findByLojaId(1L)).thenReturn(List.of(produto(11L, "Açaí 500ml", "7470", "8.50")));
        when(insumos.consumirFicha(1L, 11L, 3)).thenReturn(new BigDecimal("6.40"));

        chega("Açaí 500ml", "7470", "Açaí 500ml", 3);

        verify(insumos).consumirFicha(1L, 11L, 3);
        verify(produtos, never()).baixarEstoque(anyLong(), anyLong(), anyInt());
        assertEquals(new BigDecimal("6.40"), gravados.get(0).getCustoUnitario(),
                "com ficha o custo é o da ficha, não o do produto");
        assertEquals(Boolean.TRUE, gravados.get(0).getConsumiuFicha(),
                "sem esta marca o cancelamento devolveria o produto em vez dos insumos");
    }

    @Test
    void itemQueNaoCasaNAOderrubaOpedido() {
        // A regra mais importante do conserto. O pedido já foi aceito no iFood e o cliente vai
        // receber a comida. Recusar aqui perderia a venda e deixaria o painel sem o pedido.
        when(produtos.findByLojaId(1L)).thenReturn(List.of(produto(11L, "Pizza", "999", "8.50")));

        assertDoesNotThrow(() -> chega("Produto que ninguém cadastrou", "NAO-EXISTE", "Sei lá", 1));

        assertEquals(1, gravados.size(), "o item tem que entrar no pedido mesmo sem casar");
        assertNull(gravados.get(0).getProdutoId());
        verify(produtos, never()).baixarEstoque(anyLong(), anyLong(), anyInt());
        verify(insumos, never()).consumirFicha(anyLong(), anyLong(), anyInt());
    }

    @Test
    void nomeRepetidoNoCadastroNaoCasaComNenhum() {
        // Dois produtos com o mesmo nome: escolher um é adivinhar, e baixar o estoque do errado é pior
        // que não baixar nenhum, porque ninguém percebe.
        when(produtos.findByLojaId(1L)).thenReturn(List.of(
                produto(11L, "Açaí 500ml", null, "8.50"),
                produto(12L, "Açaí 500ml", null, "9.00")));

        chega("Açaí 500ml", null, "Açaí 500ml", 1);

        verify(produtos, never()).baixarEstoque(anyLong(), anyLong(), anyInt());
        assertNull(gravados.get(0).getProdutoId());
    }

    @Test
    void semItemNenhumNaoConsultaOCatalogo() {
        InboundOrder in = new InboundOrder("ped-2", "Cliente", null, null, null, null, null,
                new BigDecimal("10"), List.of(), BigDecimal.ZERO, "1023");

        service.criarExterno(1L, "IFOOD", "iFood", in);

        verify(produtos, never()).findByLojaId(anyLong());
    }

    @Test
    void oCatalogoEhLidoUmaVezPorPEDIDO_naoUmaPorITEM() {
        // Um pedido de 6 itens não pode virar 6 consultas ao banco.
        when(produtos.findByLojaId(1L)).thenReturn(List.of(produto(11L, "Açaí 500ml", "7470", "8.50")));
        when(insumos.consumirFicha(anyLong(), anyLong(), anyInt())).thenReturn(null);

        List<InboundOrder.InboundItem> seis = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            seis.add(new InboundOrder.InboundItem("Açaí 500ml", 1, new BigDecimal("30"), "7470", "Açaí 500ml"));
        }
        service.criarExterno(1L, "IFOOD", "iFood",
                new InboundOrder("ped-3", "C", null, null, null, null, null, new BigDecimal("180"),
                        seis, BigDecimal.ZERO, "1024"));

        verify(produtos, times(1)).findByLojaId(1L);
    }

    @Test
    void oPrecoContinuaSendoOdoMarketplace_naoOdoNossoCadastro() {
        // Quem cobrou do cliente foi o iFood, com o preço que está lá. Se o pedido fechasse pelo nosso
        // preço, o painel mostraria um valor que ninguém pagou — e o acerto com o canal não bateria.
        Produto p = produto(11L, "Açaí 500ml", "7470", "8.50");
        p.preco = new BigDecimal("99.00");
        when(produtos.findByLojaId(1L)).thenReturn(List.of(p));
        when(insumos.consumirFicha(anyLong(), anyLong(), anyInt())).thenReturn(null);

        chega("Açaí 500ml", "7470", "Açaí 500ml", 1);

        assertEquals(new BigDecimal("30"), gravados.get(0).getPrecoUnitario());
    }

    // ------------------------------------------------------------------ o payload real

    @Test
    void oNormalizadorDoIfoodPassaOexternalCodeEOnomeLimpo() {
        // Conferido no arquivo de pedido real do projeto: "externalCode": "7470".
        InboundOrder in = new MarketplaceNormalizer().normalizar("IFOOD", Map.of(
                "id", "ped-ifood",
                "items", List.of(Map.of(
                        "name", "Açaí 500ml",
                        "externalCode", "7470",
                        "quantity", 2,
                        "unitPrice", 30.0,
                        "options", List.of(Map.of("name", "Granola"))))));

        assertEquals(1, in.itens().size());
        var item = in.itens().get(0);
        assertEquals("7470", item.codigoExterno(), "o externalCode do iFood tem que chegar ao serviço");
        assertEquals("Açaí 500ml", item.nomeLimpo(), "o nome limpo é o que casa com o cadastro");
        assertTrue(item.nome().contains("Granola"),
                "o nome da TELA continua trazendo o complemento — é o que o lojista precisa ler");
    }

    @Test
    void oNormalizadorDaOpenDeliveryTambemPassaOcodigo() {
        // Conferido no arquivo de pedido real: "externalCode": "PIZZA-CAL".
        InboundOrder in = new MarketplaceNormalizer().normalizar("99FOOD", Map.of(
                "id", "ped-99",
                "items", List.of(Map.of(
                        "name", "Pizza Calabresa",
                        "externalCode", "PIZZA-CAL",
                        "quantity", 1,
                        "unitPrice", Map.of("value", 49.9)))));

        assertEquals("PIZZA-CAL", in.itens().get(0).codigoExterno());
        assertEquals("Pizza Calabresa", in.itens().get(0).nomeLimpo());
    }

    // ------------------------------------------------------------------ nunca recusar

    @Test
    void semEstoqueOpedidoDoIFoodENTRAdoMesmoJeito() {
        // O marketplace NUNCA recusa por falta de estoque: o pedido ja foi aceito do outro lado e o
        // cliente vai receber a comida. Recusar aqui perderia a venda e deixaria o painel sem o
        // pedido, com o entregador chegando. O saldo vai a negativo de proposito -- e informacao de
        // que a contagem esta errada, nao motivo para derrubar a venda.
        //
        // Quem recusa e so o cardapio publico, onde o cliente nao tem nada na mao. Ver
        // VendaSemEstoqueTest.
        Produto p = produto(11L, "Açaí 500ml", "7470", "8.50");
        p.estoque = 1;
        when(produtos.findByLojaId(1L)).thenReturn(List.of(p));
        when(insumos.consumirFicha(anyLong(), anyLong(), anyInt())).thenReturn(null);

        assertDoesNotThrow(() -> chega("Açaí 500ml", "7470", "Açaí 500ml", 5));

        verify(produtos).baixarEstoque(11L, 1L, 5);
        verify(produtos, never()).baixarEstoqueSeTiver(anyLong(), anyLong(), anyInt());
        assertEquals(11L, gravados.get(0).getProdutoId(), "o item tem que entrar igual");
    }

    @Test
    void oMarketplaceUsaOconsumirFichaQueNAOrecusa() {
        // A sobrecarga de 3 argumentos e a que deixa passar. Se alguem trocar para a de 4 com `true`,
        // um pedido ja aceito no iFood passaria a ser recusado aqui.
        when(produtos.findByLojaId(1L)).thenReturn(List.of(produto(11L, "Açaí 500ml", "7470", "8.50")));
        when(insumos.consumirFicha(anyLong(), anyLong(), anyInt())).thenReturn(new BigDecimal("6.40"));

        chega("Açaí 500ml", "7470", "Açaí 500ml", 2);

        verify(insumos).consumirFicha(1L, 11L, 2);
        verify(insumos, never()).consumirFicha(anyLong(), anyLong(), anyInt(), anyBoolean());
    }

    @Test
    void canalSemCodigoContinuaFuncionando() {
        // Rappi e Uber Eats não mandam externalCode. O construtor curto do InboundItem existe para
        // isso: codigo e nome limpo nulos, e o item entra sem baixa, como entrava antes.
        var item = new InboundOrder.InboundItem("Marmita", 1, new BigDecimal("25"));

        assertNull(item.codigoExterno());
        assertNull(item.nomeLimpo());
    }
}
