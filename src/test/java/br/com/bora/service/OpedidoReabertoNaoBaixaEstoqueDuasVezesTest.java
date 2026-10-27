package br.com.bora.service;

import br.com.bora.entity.LogStatus;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.LogStatusRepository;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.ProdutoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pedido reaberto nao baixa o estoque duas vezes.
 *
 * <h2>O defeito (achado pelo QA em 26/10/2026)</h2>
 * <p>O {@code devolver} decide pelo ESTAGIO: insumo volta so se o pedido ainda nao entrou em preparo
 * (a cozinha ja usou a polpa, ela nao volta), e estoque de produto pronto volta so se o pedido ainda
 * nao saiu da loja. O {@code rebaixarDeNovo} afirmava no proprio javadoc ser "o espelho exato do
 * devolver" e <b>nao tinha nenhuma das duas guardas</b>: consumia sempre.</p>
 *
 * <p>O caminho real, com um caso so: pedido do cardapio com PIX, o lojista avanca para EM_PREPARO
 * (nada bloqueia isso -- o {@code CobradorDePixService} documenta que acontece), o PIX expira, o
 * pedido e cancelado. No cancelamento o insumo NAO foi devolvido, e isso esta certo. Depois o
 * dinheiro cai e o pedido e reaberto: o {@code rebaixarDeNovo} consumia a ficha de novo. A polpa
 * saia DUAS VEZES do estoque para um acai, e o CMV do mes contava o ingrediente em dobro.</p>
 *
 * <h2>O que este arquivo mede</h2>
 * <p>Caso a caso, e a SIMETRIA: para cada status, o que o {@code rebaixarDeNovo} tira tem de ser
 * exatamente o que o {@code devolver} devolveu. E o contrato dos dois metodos, e e a trava contra
 * eles se separarem de novo -- foi exatamente assim que este defeito nasceu.</p>
 */
class OpedidoReabertoNaoBaixaEstoqueDuasVezesTest {

    private static final Long LOJA = 7L;
    private static final Long PEDIDO = 4242L;
    private static final Long PRODUTO = 88L;

    private PedidoItemRepository itens;
    private ProdutoRepository produtos;
    private InsumoService insumos;
    private FidelidadeService fidelidade;
    private LogStatusRepository logs;
    private EstornoDaVendaService service;

    @BeforeEach
    void montar() {
        itens = mock(PedidoItemRepository.class);
        produtos = mock(ProdutoRepository.class);
        insumos = mock(InsumoService.class);
        fidelidade = mock(FidelidadeService.class);
        logs = mock(LogStatusRepository.class);
        when(produtos.baixarEstoque(anyLong(), anyLong(), anyInt())).thenReturn(1);
        when(produtos.devolverEstoque(anyLong(), anyLong(), anyInt())).thenReturn(1);
        when(insumos.devolverFicha(anyLong(), anyLong(), anyInt())).thenReturn(true);
        when(insumos.devolverAdicionais(anyLong(), anyList(), anyInt())).thenReturn(1);
        when(insumos.consumirFicha(anyLong(), anyLong(), anyInt())).thenReturn(BigDecimal.ONE);
        service = new EstornoDaVendaService(itens, produtos, insumos, fidelidade, logs);
    }

    private Pedido pedido() {
        Pedido p = new Pedido();
        p.id = PEDIDO;
        p.lojaId = LOJA;
        p.clienteId = 3L;
        p.valorTotal = new BigDecimal("30.00");
        return p;
    }

    /** Um item, com ou sem ficha tecnica, com um adicional de granola gravado nele. */
    private void comItem(Boolean consumiuFicha) {
        PedidoItem i = new PedidoItem();
        i.setId(1L);
        i.setLojaId(LOJA);
        i.setPedidoId(PEDIDO);
        i.setProdutoId(PRODUTO);
        i.setQuantidade(2);
        i.setConsumiuFicha(consumiuFicha);
        // O adicional entra de verdade: `ler(null)` devolve NULO, e um teste com anyList() nao
        // casaria -- mediria a ausencia do dado em vez do caminho do adicional.
        i.setComplementos(ComplementosDoItem.escrever(List.of(new ComplementoService.Escolhido(
                12L, "Granola", new BigDecimal("3.00"), 50L, new BigDecimal("0.030")))));
        when(itens.findByLojaIdAndPedidoIdOrderById(LOJA, PEDIDO)).thenReturn(List.of(i));
    }

    /** O historico que o metodo le: a mudanca que levou o pedido a CANCELADO. */
    private void historicoCanceladoDe(StatusPedido... caminho) {
        List<LogStatus> h = new ArrayList<>();
        String anterior = null;
        for (StatusPedido s : caminho) {
            LogStatus l = new LogStatus();
            l.setLojaId(LOJA);
            l.setPedidoId(PEDIDO);
            l.setStatusAnterior(anterior);
            l.setStatusNovo(s.name());
            h.add(l);
            anterior = s.name();
        }
        when(logs.findByLojaIdAndPedidoIdOrderByDataHoraAsc(LOJA, PEDIDO)).thenReturn(h);
    }

    // ---------------------------------------------------------------- a premissa
    @Test
    void PREMISSA_oDEVOLVERjaTINHAasGUARDAS() {
        // Sem isto, um `devolver` que devolvesse sempre faria a simetria abaixo passar por igualdade
        // entre dois erros.
        comItem(true);
        service.devolver(pedido(), StatusPedido.EM_PREPARO, true);
        verify(insumos, never()).devolverFicha(anyLong(), anyLong(), anyInt());

        montar();
        comItem(true);
        service.devolver(pedido(), StatusPedido.RECEBIDO, true);
        verify(insumos).devolverFicha(LOJA, PRODUTO, 2);
    }

    // ---------------------------------------------------------------- o caso medido
    @Test
    void oACAIemPREPAROnaoCONSOMEaPOLPAdeNOVO() {
        comItem(true);
        historicoCanceladoDe(StatusPedido.CONFIRMADO, StatusPedido.EM_PREPARO, StatusPedido.CANCELADO);

        int baixados = service.rebaixarDeNovo(pedido());

        verify(insumos, never()).consumirFicha(anyLong(), anyLong(), anyInt());
        verify(insumos, never()).consumirAdicionais(anyLong(), anyList(), anyInt(), anyBoolean());
        assertEquals(0, baixados, "nada foi devolvido no cancelamento: nada ha para tirar de novo");
    }

    @Test
    void oPEDIDOqueSAIUparaENTREGAnaoMEXEemNADA() {
        comItem(false);
        historicoCanceladoDe(StatusPedido.EM_PREPARO, StatusPedido.SAIU_PARA_ENTREGA, StatusPedido.CANCELADO);

        assertEquals(0, service.rebaixarDeNovo(pedido()));
        verify(produtos, never()).baixarEstoque(anyLong(), anyLong(), anyInt());
        verify(insumos, never()).consumirFicha(anyLong(), anyLong(), anyInt());
    }

    @Test
    void oADICIONALsegueOinsumo_eNAOsaiDEnovoEMpreparo() {
        // O adicional e insumo: a granola ja foi para o copo. No `devolver` ele e barrado pela mesma
        // guarda, e aqui tem de ser barrado tambem.
        comItem(false); // produto SEM ficha: o estoque dele volta, o adicional nao
        historicoCanceladoDe(StatusPedido.EM_PREPARO, StatusPedido.CANCELADO);

        service.rebaixarDeNovo(pedido());

        verify(insumos, never()).consumirAdicionais(anyLong(), anyList(), anyInt(), anyBoolean());
        verify(produtos).baixarEstoque(PRODUTO, LOJA, 2);
    }

    // ---------------------------------------------------------------- o que tem de continuar valendo
    @Test
    void oPIXqueCAIUantesDOpreparoBAIXAoINSUMOdeNOVO() {
        // O caso comum, e o motivo do metodo existir: o cancelamento devolveu, a reabertura tira.
        comItem(true);
        historicoCanceladoDe(StatusPedido.RECEBIDO, StatusPedido.CANCELADO);

        assertEquals(1, service.rebaixarDeNovo(pedido()));
        verify(insumos).consumirFicha(LOJA, PRODUTO, 2);
        verify(insumos).consumirAdicionais(eq(LOJA), anyList(), eq(2), eq(false));
    }

    @Test
    void oREFRIGERANTEvoltaAsairDOestoque() {
        comItem(false);
        historicoCanceladoDe(StatusPedido.CONFIRMADO, StatusPedido.CANCELADO);

        assertEquals(1, service.rebaixarDeNovo(pedido()));
        verify(produtos).baixarEstoque(PRODUTO, LOJA, 2);
    }

    // ---------------------------------------------------------------- o que nao se sabe
    @Test
    void semHISTORICOnaoBAIXAnada() {
        // Historico apagado ou pedido antigo: se nao se sabe o que foi devolvido, nao se sabe o que
        // tirar de novo. A mesma regra que o `devolver` aplica ao item sem a marca da V45.
        comItem(true);
        when(logs.findByLojaIdAndPedidoIdOrderByDataHoraAsc(LOJA, PEDIDO)).thenReturn(List.of());

        assertEquals(0, service.rebaixarDeNovo(pedido()));
        verify(insumos, never()).consumirFicha(anyLong(), anyLong(), anyInt());
        verify(produtos, never()).baixarEstoque(anyLong(), anyLong(), anyInt());
    }

    @Test
    void historicoCOMtextoDESCONHECIDOnaoBAIXAnada() {
        comItem(true);
        LogStatus l = new LogStatus();
        l.setLojaId(LOJA);
        l.setPedidoId(PEDIDO);
        l.setStatusAnterior("ESTADO_QUE_NAO_EXISTE_MAIS");
        l.setStatusNovo(StatusPedido.CANCELADO.name());
        when(logs.findByLojaIdAndPedidoIdOrderByDataHoraAsc(LOJA, PEDIDO)).thenReturn(List.of(l));

        assertEquals(0, service.rebaixarDeNovo(pedido()));
        verify(insumos, never()).consumirFicha(anyLong(), anyLong(), anyInt());
    }

    @Test
    void valeOULTIMOcancelamento() {
        // Pedido reaberto, cancelado de novo e reaberto outra vez: o que vale e como ele saiu NA
        // ULTIMA vez, nao na primeira.
        comItem(true);
        List<LogStatus> h = new ArrayList<>();
        for (String[] passo : new String[][] {
                { "RECEBIDO", "CANCELADO" },       // primeiro cancelamento: antes do preparo
                { "CANCELADO", "RECEBIDO" },
                { "RECEBIDO", "EM_PREPARO" },
                { "EM_PREPARO", "CANCELADO" } }) { // segundo: a cozinha ja tinha comecado
            LogStatus l = new LogStatus();
            l.setLojaId(LOJA);
            l.setPedidoId(PEDIDO);
            l.setStatusAnterior(passo[0]);
            l.setStatusNovo(passo[1]);
            h.add(l);
        }
        when(logs.findByLojaIdAndPedidoIdOrderByDataHoraAsc(LOJA, PEDIDO)).thenReturn(h);

        assertEquals(0, service.rebaixarDeNovo(pedido()));
        verify(insumos, never()).consumirFicha(anyLong(), anyLong(), anyInt());
    }

    @Test
    void itemSEMaMARCAdaV45naoBAIXAnada() {
        comItem(null);
        historicoCanceladoDe(StatusPedido.RECEBIDO, StatusPedido.CANCELADO);

        assertEquals(0, service.rebaixarDeNovo(pedido()));
        verify(insumos, never()).consumirFicha(anyLong(), anyLong(), anyInt());
        verify(produtos, never()).baixarEstoque(anyLong(), anyLong(), anyInt());
    }

    // ---------------------------------------------------------------- a simetria, status por status
    @Test
    void oINSUMOsaiDEnovoEXATAMENTEnosSTATUSemQUEvoltou() {
        // A trava contra os dois metodos se separarem outra vez. Status por status: devolveu insumo?
        // Entao tem de tirar insumo. Nao devolveu? Entao nao tira.
        for (StatusPedido s : StatusPedido.values()) {
            if (s == StatusPedido.CANCELADO) continue; // nao se cancela de CANCELADO

            montar();
            comItem(true);
            service.devolver(pedido(), s, true);
            boolean devolveu = chamou(insumos, "devolverFicha");

            montar();
            comItem(true);
            historicoCanceladoDe(s, StatusPedido.CANCELADO);
            service.rebaixarDeNovo(pedido());
            boolean tirou = chamou(insumos, "consumirFicha");

            assertEquals(devolveu, tirou,
                    "quebrou a simetria no status " + s + ": devolver devolveu insumo=" + devolveu
                            + " mas rebaixarDeNovo tirou=" + tirou);
        }
    }

    @Test
    void oPRODUTOsaiDEnovoEXATAMENTEnosSTATUSemQUEvoltou() {
        for (StatusPedido s : StatusPedido.values()) {
            if (s == StatusPedido.CANCELADO) continue;

            montar();
            comItem(false);
            service.devolver(pedido(), s, true);
            boolean devolveu = chamou(produtos, "devolverEstoque");

            montar();
            comItem(false);
            historicoCanceladoDe(s, StatusPedido.CANCELADO);
            service.rebaixarDeNovo(pedido());
            boolean tirou = chamou(produtos, "baixarEstoque");

            assertEquals(devolveu, tirou,
                    "quebrou a simetria no status " + s + ": devolver devolveu produto=" + devolveu
                            + " mas rebaixarDeNovo tirou=" + tirou);
        }
    }

    /** O dublê foi chamado neste método? Lido das invocações, para não depender de ordem. */
    private static boolean chamou(Object duble, String metodo) {
        return mockingDetails(duble).getInvocations().stream()
                .anyMatch(i -> i.getMethod().getName().equals(metodo));
    }

    // ---------------------------------------------------------------- bordas
    @Test
    void pedidoNULOouSEMidNAOexplode() {
        assertEquals(0, service.rebaixarDeNovo(null));
        assertEquals(0, service.rebaixarDeNovo(new Pedido()));
        verifyNoInteractions(logs);
    }

    @Test
    void itemCOMquantidadeZEROouSEMprodutoEHignorado() {
        PedidoItem zero = new PedidoItem();
        zero.setProdutoId(PRODUTO);
        zero.setQuantidade(0);
        zero.setConsumiuFicha(true);
        PedidoItem semProduto = new PedidoItem();
        semProduto.setQuantidade(1);
        semProduto.setConsumiuFicha(true);
        when(itens.findByLojaIdAndPedidoIdOrderById(LOJA, PEDIDO)).thenReturn(List.of(zero, semProduto));
        historicoCanceladoDe(StatusPedido.RECEBIDO, StatusPedido.CANCELADO);

        assertEquals(0, service.rebaixarDeNovo(pedido()));
        verify(insumos, never()).consumirFicha(anyLong(), anyLong(), anyInt());
    }
}
