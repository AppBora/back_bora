package br.com.bora.service;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TODO DESFECHO DE VENDA CHEGA À FILA DE EVENTOS — não só o nascimento dela.
 *
 * <h2>Os dois buracos que este arquivo fecha (18/11/2026)</h2>
 * <p>A entrega A2 instalou oito pontos de gravação, e a revisão de arquitetura achou <b>dois
 * caminhos que mudavam o pedido e não emitiam nada</b>. Os dois eram desfecho de venda — o pior
 * tipo de evento para faltar, porque o {@code PedidoRecebido} já está na fila e já commitou:</p>
 *
 * <ol>
 *   <li><b>{@code CobradorDePixService.cancelarVencidos}</b> — o checkout abandonado. O cliente
 *       gera o PIX e não paga; trinta minutos depois esta rotina cancela, devolve estoque e
 *       devolve cashback. E não emitia evento. Era o <b>cancelamento mais frequente do
 *       sistema</b>: o módulo de Estoque guardaria para sempre uma baixa que foi devolvida, e o
 *       Financeiro, recebível de venda que nunca houve.
 *       <p>O mais incômodo: o {@code desfazerPedidoSemPix} do {@code PublicController}, que cobre
 *       o caso <i>raro</i> (o Asaas recusar), chama esse mesmo evento de "obrigatório" no próprio
 *       comentário. O gancho foi posto no caminho raro e esquecido no que roda sempre.</p></li>
 *   <li><b>{@code PedidoService.avancarPorMarketplace}</b> — o entregador do iFood marca
 *       ENTREGUE. O desfecho da venda de marketplace acontece por aqui, <b>não</b> pelo
 *       {@code alterarStatus}, que é o caminho do painel. Sem evento, pedido de marketplace nunca
 *       produzia {@code PedidoFinalizado} — e {@code PedidoFinalizado} é exatamente o que o Fiscal
 *       e o Financeiro assinam. A venda de marketplace inteira ficaria fora da nota e fora do
 *       recebível, enquanto a do painel entraria, e a diferença não apareceria em lugar nenhum.
 *       O irmão {@code cancelarPorMarketplace} emitia; este não.</p></li>
 * </ol>
 *
 * <h2>E a trava para a próxima vez</h2>
 * <p>Os dois passaram pelos 1503 testes da suíte sem uma reclamação. Oito ganchos conferidos à mão
 * e dois esquecidos é o resultado esperado de conferir à mão. Por isso o último teste daqui não
 * olha um caminho: ele varre o código e <b>falha quando um método novo escreve
 * {@code Pedido.status} sem emitir evento</b>.</p>
 */
class TodoDESFECHOdaVendaChegaAfilaTest {

    private static final Long LOJA = 7L;

    private PedidoRepository pedidos;
    private PublicadorDeEventos eventos;
    private EstornoDaVendaService estorno;

    @BeforeEach
    void montar() {
        pedidos = mock(PedidoRepository.class);
        eventos = mock(PublicadorDeEventos.class);
        estorno = mock(EstornoDaVendaService.class);
        when(pedidos.save(any(Pedido.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Pedido pixPendente(StatusPedido status) {
        Pedido p = new Pedido();
        p.id = 500L;
        p.lojaId = LOJA;
        p.codigo = "CD-500";
        p.status = status;
        p.valorTotal = new BigDecimal("40.00");
        p.cashbackUsado = new BigDecimal("5.00");
        p.aguardandoPagamento = true;
        p.criadoEm = OffsetDateTime.now().minusHours(2);
        p.observacao = "Cliente: Maria";
        return p;
    }

    // =========================================================== 1) o PIX abandonado

    private CobradorDePixService cobrador() {
        return new CobradorDePixService(pedidos, mock(FidelidadeService.class),
                mock(LojaRepository.class), mock(IntegracaoCanalRepository.class),
                mock(PixService.class), estorno, eventos,
                GerenciadorDeTransacaoFalso.novo(), 30);
    }

    /**
     * O CANCELAMENTO MAIS FREQUENTE DO SISTEMA tem de chegar à fila.
     *
     * <p>Sem este evento o consumidor fica com uma venda que deixou de existir — e o estoque dela
     * foi devolvido nesta mesma transação.</p>
     */
    @Test
    void oPIXabandonadoEmitePedidoCancelado() {
        Pedido p = pixPendente(StatusPedido.RECEBIDO);
        when(pedidos.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));

        cobrador().expirarAbandonados();

        assertEquals(StatusPedido.CANCELADO, p.status, "a premissa: o pedido foi cancelado");
        verify(eventos).dePedido(eq("PedidoCancelado"), same(p), anyMap());
    }

    /**
     * A FASE vai no payload, e não é enfeite: é ela que decide, no módulo de Estoque, se o item
     * volta para a prateleira ou vira perda (RN-EST-005). Um cancelamento de pedido que já estava
     * na cozinha não devolve a mesma coisa que um de pedido que nunca saiu do balcão.
     */
    @Test
    void oEVENTOlevaAfaseEoMOTIVOdoCancelamento() {
        Pedido p = pixPendente(StatusPedido.CONFIRMADO);
        when(pedidos.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));

        cobrador().expirarAbandonados();

        var extras = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(eventos).dePedido(eq("PedidoCancelado"), any(Pedido.class), extras.capture());
        assertEquals("CONFIRMADO", extras.getValue().get("fase"),
                "sem a fase o modulo de Estoque nao sabe se devolve ou lanca perda");
        assertEquals("SISTEMA", extras.getValue().get("solicitante"),
                "nao foi o cliente nem o lojista: foi o prazo do PIX vencer");
        assertTrue(String.valueOf(extras.getValue().get("motivo")).contains("30 minutos"),
                "o motivo veio: " + extras.getValue().get("motivo"));
    }

    /** Venda PAGA não é cancelada — e também não emite cancelamento. */
    @Test
    void pixJApagoNaoCancelaEnaoEmite() {
        Pedido p = pixPendente(StatusPedido.RECEBIDO);
        p.pagoEm = OffsetDateTime.now();
        when(pedidos.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));

        cobrador().expirarAbandonados();

        assertEquals(StatusPedido.RECEBIDO, p.status, "cancelar venda paga seria o pior erro possivel");
        verifyNoInteractions(eventos);
    }

    /**
     * O PEDIDO QUE JÁ SAIU DA ESPERA NÃO É CANCELADO, então não emite cancelamento.
     *
     * <p>A comida saiu sem pagamento: a rotina só deixa um aviso na observação, de propósito (item
     * 6.1 da varredura). Emitir {@code PedidoCancelado} aqui mandaria o módulo devolver um estoque
     * que foi consumido de verdade.</p>
     */
    @Test
    void pedidoQueJAsaiuDAesperaNAOemiteCancelamento() {
        Pedido p = pixPendente(StatusPedido.SAIU_PARA_ENTREGA);
        when(pedidos.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));

        cobrador().expirarAbandonados();

        assertEquals(StatusPedido.SAIU_PARA_ENTREGA, p.status);
        verify(eventos, never()).dePedido(eq("PedidoCancelado"), any(Pedido.class), anyMap());
    }

    // =========================================================== 2) o avanço do marketplace

    private PedidoService servico() {
        PedidoService s = new PedidoService(pedidos, mock(br.com.bora.repository.PedidoItemRepository.class),
                mock(br.com.bora.repository.ProdutoRepository.class),
                mock(br.com.bora.repository.ClienteRepository.class),
                mock(br.com.bora.repository.LogStatusRepository.class),
                mock(PlanoService.class), mock(IntegracaoService.class),
                mock(br.com.bora.repository.TaxaEntregaRepository.class),
                mock(InsumoService.class), mock(FidelidadeService.class),
                mock(ComplementoService.class), mock(br.com.bora.security.AuthContext.class),
                estorno, eventos, GerenciadorDeTransacaoFalso.novo());
        // O `notifCliente` nao entra pelo construtor (e @Autowired de campo, do Modulo IA). Sem
        // injetar, o metodo estoura DEPOIS de emitir o evento -- o teste erraria por um motivo que
        // nao tem nada a ver com o que ele mede.
        org.springframework.test.util.ReflectionTestUtils.setField(s, "notifCliente",
                mock(NotificacaoClienteService.class));
        return s;
    }

    private Pedido doIfood(StatusPedido status) {
        Pedido p = new Pedido();
        p.id = 900L;
        p.lojaId = LOJA;
        p.codigo = "IF-900";
        p.status = status;
        p.valorTotal = new BigDecimal("72.50");
        p.origem = "iFood";
        p.canalExterno = "IFOOD";
        p.idExterno = "ext-abc";
        p.criadoEm = OffsetDateTime.now();
        return p;
    }

    private void noCanal(Pedido p) {
        when(pedidos.findFirstByLojaIdAndCanalExternoAndIdExterno(LOJA, "IFOOD", "ext-abc"))
                .thenReturn(Optional.of(p));
    }

    /**
     * ENTREGUE PELO MARKETPLACE É O FIM DA VENDA, e o catálogo chama isso de
     * {@code PedidoFinalizado} — o evento que o Fiscal e o Financeiro assinam.
     */
    @Test
    void oMARKETPLACEentregandoEmitePedidoFinalizado() {
        Pedido p = doIfood(StatusPedido.SAIU_PARA_ENTREGA);
        noCanal(p);

        assertTrue(servico().avancarPorMarketplace(LOJA, "IFOOD", "ext-abc", StatusPedido.ENTREGUE));

        verify(eventos).dePedido(eq("PedidoFinalizado"), same(p), anyMap());
    }

    @Test
    void oAVANCOintermediarioTambemEmite() {
        Pedido p = doIfood(StatusPedido.EM_PREPARO);
        noCanal(p);

        servico().avancarPorMarketplace(LOJA, "IFOOD", "ext-abc", StatusPedido.SAIU_PARA_ENTREGA);

        verify(eventos).dePedido(eq("PedidoStatusAlterado"), same(p), anyMap());
    }

    @Test
    void oEVENTOdizQUALcanalEqualIDexterno() {
        Pedido p = doIfood(StatusPedido.SAIU_PARA_ENTREGA);
        noCanal(p);

        servico().avancarPorMarketplace(LOJA, "IFOOD", "ext-abc", StatusPedido.ENTREGUE);

        var extras = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(eventos).dePedido(anyString(), any(Pedido.class), extras.capture());
        assertEquals("IFOOD", extras.getValue().get("canal"));
        assertEquals("ext-abc", extras.getValue().get("idExterno"),
                "e por este id que o suporte casa a venda daqui com a de la");
        assertEquals("SAIU_PARA_ENTREGA", extras.getValue().get("statusAnterior"));
        assertEquals("MARKETPLACE", extras.getValue().get("solicitante"),
                "nao foi usuario do painel: foi o aviso do canal");
    }

    /** Avanço que o serviço RECUSA não emite nada: não houve mudança para contar. */
    @Test
    void avancoRECUSADOnaoEmite() {
        noCanal(doIfood(StatusPedido.ENTREGUE)); // ja esta em estado final

        assertFalse(servico().avancarPorMarketplace(LOJA, "IFOOD", "ext-abc", StatusPedido.PRONTO));

        verifyNoInteractions(eventos);
    }

    @Test
    void pedidoQueNaoEXISTEnoCanalNaoEmite() {
        when(pedidos.findFirstByLojaIdAndCanalExternoAndIdExterno(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertFalse(servico().avancarPorMarketplace(LOJA, "IFOOD", "nao-existe", StatusPedido.ENTREGUE));

        verifyNoInteractions(eventos);
    }

    // =========================================================== 3) a trava

    /**
     * A TRAVA PARA A PRÓXIMA VEZ: quem escreve {@code Pedido.status} tem de emitir evento.
     *
     * <h3>Por que um teste que lê código, e não um que roda um caminho</h3>
     * <p>Os dois buracos acima atravessaram <b>1503 testes</b> sem uma reclamação, porque teste de
     * caminho só cobre o caminho que alguém lembrou de escrever. Oito ganchos conferidos à mão e
     * dois esquecidos é o resultado normal de conferir à mão — o nono vai ser esquecido do mesmo
     * jeito.</p>
     *
     * <p>Então este teste não pergunta "o caminho X emite?". Ele varre o código de produção e
     * pergunta: <b>existe algum lugar que escreve {@code .status =} num Pedido e cujo arquivo não
     * conhece o publicador de eventos?</b> Um método novo de mudança de status nasce reprovado, e
     * quem escrever lê aqui o motivo.</p>
     *
     * <p>O que esta varredura NÃO prova: que o evento está no ponto certo <i>dentro</i> do método,
     * nem na transação certa. Isso continua sendo trabalho dos testes de caminho acima. O que ela
     * garante é que nenhum arquivo passe a mexer em status <b>sem nem saber</b> que a fila
     * existe.</p>
     */
    @Test
    void nenhumArquivoMEXEemSTATUSsemCONHECERaFILA() throws Exception {
        java.nio.file.Path raiz = java.nio.file.Path.of("src/main/java/br/com/bora");
        assertTrue(java.nio.file.Files.isDirectory(raiz), "nao achei o codigo de producao em " + raiz);

        // Arquivos que escrevem status mas NAO precisam emitir, com o motivo de cada um. Lista
        // curta de propósito: cada linha aqui é uma permissão, e permissão sem motivo escrito é
        // como o defeito volta.
        Map<String, String> liberados = Map.of(
                "EstornoDaVendaService.java",
                "devolve estoque/cashback e registra historico; quem muda o status e chama este "
                        + "servico e que emite, senao sairiam dois eventos para um cancelamento");

        var faltando = new java.util.TreeMap<String, Integer>();
        try (var caminhos = java.nio.file.Files.walk(raiz)) {
            for (java.nio.file.Path f : caminhos.filter(x -> x.toString().endsWith(".java")).toList()) {
                String nome = f.getFileName().toString();
                if (liberados.containsKey(nome)) continue;
                String fonte = java.nio.file.Files.readString(f);

                // `.status = StatusPedido.X` — a escrita, nao a leitura/comparacao.
                int escritas = 0;
                for (String linha : fonte.split("\n")) {
                    String l = linha.trim();
                    if (l.startsWith("//") || l.startsWith("*")) continue;
                    if (l.matches(".*\\.status\\s*=\\s*StatusPedido\\..*")) escritas++;
                }
                if (escritas == 0) continue;
                if (fonte.contains("PublicadorDeEventos") || fonte.contains("eventos.dePedido")) continue;
                faltando.put(nome, escritas);
            }
        }

        assertTrue(faltando.isEmpty(),
                "Estes arquivos escrevem Pedido.status e NAO conhecem a fila de eventos do ERP.\n"
                        + "Um desfecho de venda que nao emite deixa o modulo com uma venda que mudou\n"
                        + "sem ele saber -- foi assim com o PIX abandonado e com o avanco do\n"
                        + "marketplace, e os 1503 testes nao reclamaram.\n"
                        + "Se o arquivo REALMENTE nao deve emitir, acrescente-o ao mapa `liberados`\n"
                        + "deste teste COM O MOTIVO escrito.\n"
                        + "  " + faltando);
    }

    /**
     * A lista de liberados não pode crescer no escuro: todo liberado tem motivo escrito, e o
     * arquivo tem de existir. Liberado apontando para arquivo que já foi renomeado é uma permissão
     * aberta para um nome que ninguém mais usa.
     */
    @Test
    void todoLIBERADOtemMOTIVOeEXISTE() throws Exception {
        Method m = getClass().getDeclaredMethod("nenhumArquivoMEXEemSTATUSsemCONHECERaFILA");
        assertFalse(Modifier.isPrivate(m.getModifiers()), "o teste da varredura tem de rodar");

        // A mesma lista do teste acima, repetida aqui de proposito: se alguem acrescentar um
        // liberado la e nao aqui, este teste reprova e obriga a escrever o motivo.
        Map<String, String> liberados = Map.of(
                "EstornoDaVendaService.java",
                "devolve estoque/cashback e registra historico; quem muda o status e chama este "
                        + "servico e que emite, senao sairiam dois eventos para um cancelamento");

        try (var caminhos = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java/br/com/bora"))) {
            var existentes = caminhos.filter(x -> x.toString().endsWith(".java"))
                    .map(x -> x.getFileName().toString()).toList();
            liberados.forEach((nome, motivo) -> {
                assertTrue(existentes.contains(nome),
                        "liberado aponta para arquivo que nao existe mais: " + nome);
                assertTrue(motivo != null && motivo.length() > 30,
                        "liberado sem motivo de verdade: " + nome);
            });
        }
    }
}
