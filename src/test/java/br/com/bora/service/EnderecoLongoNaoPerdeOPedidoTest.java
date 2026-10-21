package br.com.bora.service;

import br.com.bora.dto.InboundOrder;
import br.com.bora.entity.Cliente;
import br.com.bora.entity.LarguraDeColuna;
import br.com.bora.entity.Pedido;
import br.com.bora.repository.*;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Endereço comprido do marketplace não pode perder o pedido.
 *
 * <h2>O defeito (medido em 21/10/2026)</h2>
 * <p>O <b>mesmo</b> endereço que vem do marketplace ia para dois lugares no mesmo método:
 * {@code upsertCliente} cortava em 500 (porque {@code cliente.endereco} é varchar(500) desde a V37,
 * largada justamente por causa de endereço de marketplace) e {@code criarExterno} gravava
 * <b>sem corte</b> em {@code pedido.endereco_entrega}, varchar(255).</p>
 *
 * <p>O Postgres não trunca: ele <b>recusa o INSERT</b>
 * ({@code value too long for type character varying(255)}), e o <b>pedido inteiro se perde</b> — não
 * o campo. A venda já aconteceu no iFood e o entregador está a caminho de um pedido que o painel
 * nunca viu. E o {@code ddl-auto: validate} não pega isso: ele confere tipo, não largura.</p>
 *
 * <p><b>Nunca disparou:</b> medido na produção, nenhum pedido tem {@code endereco_entrega}
 * preenchido e o maior endereço de cliente da base tem 43 caracteres. O conserto é preventivo.</p>
 *
 * <h2>Por que cortar e não recusar</h2>
 * <p>Endereço cortado em 500 caracteres ainda dá para entregar. Pedido que não existe no painel, não.
 * E quem corta <b>avisa no log</b>, porque perder o fim de um endereço é ruim o suficiente para
 * alguém conferir antes de despachar.</p>
 */
class EnderecoLongoNaoPerdeOPedidoTest {

    private PedidoRepository repo;
    private ClienteRepository clientes;
    private PedidoItemRepository itemRepo;
    private PedidoService service;
    private final java.util.List<Pedido> gravados = new java.util.ArrayList<>();
    private final java.util.List<Cliente> clientesGravados = new java.util.ArrayList<>();

    @BeforeEach
    void montar() {
        repo = mock(PedidoRepository.class);
        clientes = mock(ClienteRepository.class);
        itemRepo = mock(PedidoItemRepository.class);
        AuthContext ctx = mock(AuthContext.class);

        service = new PedidoService(repo, itemRepo, mock(ProdutoRepository.class), clientes,
                mock(LogStatusRepository.class), mock(PlanoService.class), mock(IntegracaoService.class),
                mock(TaxaEntregaRepository.class), mock(InsumoService.class), mock(FidelidadeService.class),
                mock(ComplementoService.class), ctx, mock(EstornoDaVendaService.class),
                GerenciadorDeTransacaoFalso.novo());
        ReflectionTestUtils.setField(service, "notifCliente", mock(NotificacaoClienteService.class));

        when(ctx.lojaId()).thenReturn(1L);
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, 1L, "ADMINISTRADOR_LOJA", "a@b.c"));
        when(repo.findFirstByLojaIdAndCanalExternoAndIdExterno(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        gravados.clear();
        clientesGravados.clear();
        when(repo.save(any(Pedido.class))).thenAnswer(i -> {
            Pedido p = i.getArgument(0);
            p.id = 500L;
            gravados.add(p);
            return p;
        });
        when(clientes.save(any())).thenAnswer(i -> {
            Cliente c = i.getArgument(0);
            c.id = 70L;
            clientesGravados.add(c);
            return c;
        });
    }

    /** Um endereço de `tamanho` caracteres, com texto reconhecível no começo e no fim. */
    private static String enderecoDe(int tamanho) {
        String inicio = "Rua das Palmeiras 1234, apto 501, bloco B, ";
        String fim = " FIM";
        StringBuilder sb = new StringBuilder(inicio);
        while (sb.length() < tamanho - fim.length()) sb.append("referencia proxima ao mercado, ");
        return sb.substring(0, tamanho - fim.length()) + fim;
    }

    private Pedido chega(String endereco, String bairro, String idExterno) {
        InboundOrder in = new InboundOrder(idExterno, "Cliente", "62999990000", endereco, bairro,
                "Pago online", null, new BigDecimal("30"), List.of(), BigDecimal.ZERO, "1022");
        return service.criarExterno(1L, "IFOOD", "iFood", in);
    }

    // ================================================================= o conserto

    @Test
    void enderecoDE600caracteres_oPedidoENTRA_cortadoEm500() {
        // Antes: o INSERT era recusado e a venda desaparecia.
        Pedido p = chega(enderecoDe(600), "Centro", "ped-1");

        assertEquals(1, gravados.size(), "o pedido tem que entrar");
        assertEquals(LarguraDeColuna.PEDIDO_ENDERECO, p.enderecoEntrega.length());
        assertTrue(p.enderecoEntrega.startsWith("Rua das Palmeiras 1234"),
                "o comeco do endereco, que e a parte que entrega, tem que sobrar");
    }

    @Test
    void enderecoDE300caracteresCABEinteiro_porqueAcolunaAgoraE500() {
        // O caso que o defeito matava: 300 nao cabia em 255 e perdia o pedido. Agora cabe INTEIRO --
        // nem precisa cortar. A V53 igualou a coluna a do cliente.
        String endereco = enderecoDe(300);

        Pedido p = chega(endereco, "Centro", "ped-2");

        assertEquals(endereco, p.enderecoEntrega, "300 caracteres cabem em 500: nada a cortar");
        assertTrue(p.enderecoEntrega.endsWith("FIM"), "o fim do endereco tem que estar la");
    }

    @Test
    void oMESMOenderecoVaiParaOpedidoEparaOcliente_comOmesmoTamanho() {
        // A assimetria que causou o defeito: o mesmo valor, dois destinos, dois tamanhos.
        String endereco = enderecoDe(450);

        Pedido p = chega(endereco, "Centro", "ped-3");

        assertEquals(1, clientesGravados.size());
        assertEquals(p.enderecoEntrega, clientesGravados.get(0).endereco,
                "o pedido e o cliente tem que guardar o MESMO texto");
    }

    @Test
    void bairroLONGOtambemNAOderrubaOpedido() {
        // Mesma classe de defeito, e o agente nao tinha citado: bairro_entrega e varchar(100) e
        // gravava sem corte.
        Pedido p = chega("Rua X, 1", "B".repeat(250), "ped-4");

        assertEquals(1, gravados.size());
        assertEquals(LarguraDeColuna.PEDIDO_BAIRRO, p.bairroEntrega.length());
    }

    @Test
    void idEXTERNOlongoTambemNAOderrubaOpedido() {
        // Mesma classe. Nenhum canal manda id longo (os do iFood sao UUID de 36), mas gravar sem
        // corte numa coluna com limite perderia o pedido pelo mesmo caminho.
        Pedido p = chega("Rua X, 1", "Centro", "x".repeat(300));

        assertEquals(1, gravados.size());
        assertEquals(LarguraDeColuna.PEDIDO_ID_EXTERNO, p.idExterno.length());
    }

    // ================================================================= o aviso do corte

    /** Pendura um coletor no log do PedidoService e devolve o que ele escrever. */
    private java.util.List<String> coletandoOLog(Runnable acao) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PedidoService.class);
        var coletor = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        coletor.start();
        logger.addAppender(coletor);
        try {
            acao.run();
        } finally {
            logger.detachAppender(coletor);
            coletor.stop();
        }
        return coletor.list.stream().map(e -> e.getFormattedMessage()).toList();
    }

    @Test
    void cortarAVISAnoLog_comOpedidoEoTextoCOMPLETO() {
        // Cortar perde informacao, e perder o fim de um endereco faz o entregador descobrir na RUA.
        // Esta mutacao passou verde na primeira rodada: o conserto cortava calado. O texto completo
        // vai para o log de proposito -- e a unica copia do que o marketplace mandou.
        String endereco = enderecoDe(600);

        var linhas = coletandoOLog(() -> chega(endereco, "Centro", "ped-aviso"));

        var aviso = linhas.stream().filter(l -> l.contains("CORTADO")).findFirst();
        assertTrue(aviso.isPresent(), "nenhum aviso de corte no log; as linhas foram: " + linhas);
        assertTrue(aviso.get().contains("ped-aviso"), "o aviso tem que dizer QUAL pedido: " + aviso.get());
        assertTrue(aviso.get().contains("endereco de entrega"), "e qual campo: " + aviso.get());
        assertTrue(aviso.get().contains("600"), "e o tamanho original: " + aviso.get());
        assertTrue(aviso.get().contains(endereco),
                "e o texto COMPLETO, que e a unica copia do que o marketplace mandou");
    }

    @Test
    void semCORTEnaoHAaviso() {
        // Aviso que sai sempre vira ruido e para de ser lido.
        var linhas = coletandoOLog(() -> chega("Rua das Flores, 42", "Centro", "ped-curto"));

        assertTrue(linhas.stream().noneMatch(l -> l.contains("CORTADO")),
                "endereco que cabe nao pode gerar aviso; as linhas foram: " + linhas);
    }

    @Test
    void oBAIRROcortadoTambemAvisa() {
        var linhas = coletandoOLog(() -> chega("Rua X, 1", "B".repeat(250), "ped-bairro"));

        assertTrue(linhas.stream().anyMatch(l -> l.contains("CORTADO") && l.contains("bairro")),
                "as linhas foram: " + linhas);
    }

    // ================================================================= o que nao pode mudar

    @Test
    void enderecoVAZIOcontinuaVirandoNULO() {
        // Campo em branco do marketplace nao e endereco em branco: e ausencia. A tela de Entregas
        // agrupa por isso.
        Pedido p = chega("   ", "  ", "ped-5");

        assertNull(p.enderecoEntrega);
        assertNull(p.bairroEntrega);
    }

    @Test
    void enderecoNULOcontinuaNULO() {
        Pedido p = chega(null, null, "ped-6");

        assertNull(p.enderecoEntrega);
        assertNull(p.bairroEntrega);
    }

    @Test
    void oESPACOdasPontasContinuaSendoTirado() {
        Pedido p = chega("  Rua X, 1  ", "  Centro  ", "ped-7");

        assertEquals("Rua X, 1", p.enderecoEntrega);
        assertEquals("Centro", p.bairroEntrega);
    }

    @Test
    void enderecoNORMALpassaIntocado() {
        Pedido p = chega("Rua das Flores, 42 - apto 101", "Setor Bueno", "ped-8");

        assertEquals("Rua das Flores, 42 - apto 101", p.enderecoEntrega);
        assertEquals("Setor Bueno", p.bairroEntrega);
        assertEquals("ped-8", p.idExterno);
    }

    @Test
    void oTIPOdeEntregaContinuaSendoDecididoDepoisDoCorte() {
        // p.ehRetirada() le a observacao e o tipoEntrega; o corte do endereco nao pode atropelar isso.
        Pedido p = chega(enderecoDe(600), "Centro", "ped-9");

        assertEquals("ENTREGA", p.tipoEntrega);
    }
}
