package br.com.bora.service;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.*;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Excluir um pedido devolve o que ele consumiu — e não devolve duas vezes.
 *
 * <h2>O defeito que isto trava (medido em 20/10/2026)</h2>
 * <p>{@code excluir} apagava pedido, itens e histórico e <b>não devolvia nada</b>: nem estoque, nem
 * insumo, nem cashback. E este é justamente o botão oferecido para "pedido de teste ou lançado
 * errado" — o caso em que tudo tem que voltar. O cliente perdia o saldo que gastou numa venda que o
 * lojista acabou de declarar que nunca existiu.</p>
 *
 * <p>Pior: os itens eram apagados <b>primeiro</b>. O estorno lê os itens para saber o que devolver
 * ({@code consumiuFicha} diz se saiu produto ou insumo), então depois daquela linha não havia mais
 * como recalcular nem à mão.</p>
 *
 * <h2>A armadilha do conserto ingênuo</h2>
 * <p>Chamar o estorno sempre troca um defeito por outro. O caminho natural é <b>cancelar o pedido de
 * teste e depois apagar</b> — e no cancelamento o estorno já rodou. Devolver de novo somaria estoque
 * e cashback <b>em dobro</b>. Esse defeito dá de graça em vez de tirar, então ninguém reclama, e
 * ninguém descobre.</p>
 */
class ExcluirPedidoDevolveOQueConsumiuTest {

    private PedidoRepository repo;
    private PedidoItemRepository itemRepo;
    private LogStatusRepository logs;
    private EstornoDaVendaService devolucao;
    private IntegracaoService integracoes;
    private PedidoService service;

    private static final Long LOJA = 1L;

    @BeforeEach
    void montar() {
        repo = mock(PedidoRepository.class);
        itemRepo = mock(PedidoItemRepository.class);
        logs = mock(LogStatusRepository.class);
        devolucao = mock(EstornoDaVendaService.class);
        integracoes = mock(IntegracaoService.class);
        AuthContext ctx = mock(AuthContext.class);

        service = new PedidoService(repo, itemRepo, mock(ProdutoRepository.class),
                mock(ClienteRepository.class), logs, mock(PlanoService.class), integracoes,
                mock(TaxaEntregaRepository.class), mock(InsumoService.class), mock(FidelidadeService.class),
                mock(ComplementoService.class), ctx, devolucao, GerenciadorDeTransacaoFalso.novo());
        ReflectionTestUtils.setField(service, "notifCliente", mock(NotificacaoClienteService.class));

        when(ctx.lojaId()).thenReturn(LOJA);
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, LOJA, "ADMINISTRADOR_LOJA", "a@b.c"));
        when(devolucao.devolver(any(), any(), anyBoolean())).thenReturn(2);
    }

    /** Um pedido de balcão em RECEBIDO: o caso do "lancei errado, apaga". */
    private Pedido pedido(StatusPedido status, boolean aguardandoPagamento, OffsetDateTime pagoEm) {
        Pedido p = new Pedido();
        p.id = 500L;
        p.lojaId = LOJA;
        p.codigo = "A17";
        p.clienteId = 70L;
        p.status = status;
        p.origem = "Balcao";
        p.valorTotal = new BigDecimal("30.00");
        p.cashbackUsado = new BigDecimal("5.00");
        p.aguardandoPagamento = aguardandoPagamento;
        p.pagoEm = pagoEm;
        when(repo.findByIdAndLojaId(500L, LOJA)).thenReturn(Optional.of(p));
        return p;
    }

    // ================================================================== o conserto

    @Test
    void excluirDEVOLVEoQueOpedidoConsumiu() {
        Pedido p = pedido(StatusPedido.RECEBIDO, false, null);

        service.excluir(500L);

        verify(devolucao).devolver(p, StatusPedido.RECEBIDO, true);
    }

    @Test
    void devolveANTESdeApagarOsItens() {
        // A ordem e o conserto. O estorno le os itens no banco para saber o que devolver; depois do
        // delete eles nao existem, e nao ha mais como recalcular nem a mao.
        Pedido p = pedido(StatusPedido.RECEBIDO, false, null);

        service.excluir(500L);

        InOrder ordem = inOrder(devolucao, itemRepo, repo);
        ordem.verify(devolucao).devolver(any(), any(), anyBoolean());
        ordem.verify(itemRepo).deleteByLojaIdAndPedidoId(LOJA, 500L);
        ordem.verify(repo).apagarDaLoja(500L, LOJA);
    }

    @Test
    void oEstagioDoPedidoEhOqueDECIDEoQueVolta_eQuemSabeDissoEhOestorno() {
        // Pedido ENTREGUE: a comida foi embora e o ingrediente acabou de verdade. Quem aplica essa
        // regra e o EstornoDaVendaService; a obrigacao daqui e passar o status QUE O PEDIDO TINHA,
        // nao um status inventado. Passar CANCELADO devolveria tudo sempre, inclusive comida feita.
        Pedido p = pedido(StatusPedido.ENTREGUE, false, null);

        service.excluir(500L);

        verify(devolucao).devolver(p, StatusPedido.ENTREGUE, true);
    }

    // ================================================================== a armadilha

    @Test
    void pedidoJAcanceladoNAOdevolveDEnovo() {
        // O caminho natural: cancela o pedido de teste, depois apaga. No cancelamento o estorno ja
        // rodou. Devolver aqui somaria estoque e cashback EM DOBRO.
        pedido(StatusPedido.CANCELADO, false, null);

        service.excluir(500L);

        verify(devolucao, never()).devolver(any(), any(), anyBoolean());
    }

    @Test
    void pedidoJAcanceladoAindaASSIMehApagadoPorCompleto() {
        // Nao devolver nao pode virar nao apagar.
        pedido(StatusPedido.CANCELADO, false, null);

        service.excluir(500L);

        verify(itemRepo).deleteByLojaIdAndPedidoId(LOJA, 500L);
        verify(logs).deleteByLojaIdAndPedidoId(LOJA, 500L);
        verify(repo).apagarDaLoja(500L, LOJA);
    }

    // ================================================================== PIX nao pago

    @Test
    void pixGERADOeNAOpago_aVendaNUNCAfoiContabilizada() {
        // Esse pedido nunca entrou no historico do cliente nem gerou cashback: houve apenas o DEBITO
        // do resgate. Dizer que foi contabilizada faria o estorno tirar do cliente um cashback que
        // ele nunca recebeu.
        Pedido p = pedido(StatusPedido.RECEBIDO, true, null);

        service.excluir(500L);

        verify(devolucao).devolver(p, StatusPedido.RECEBIDO, false);
    }

    @Test
    void pixPAGOcontaComoVendaContabilizada() {
        Pedido p = pedido(StatusPedido.RECEBIDO, true, OffsetDateTime.now());

        service.excluir(500L);

        verify(devolucao).devolver(p, StatusPedido.RECEBIDO, true);
    }

    // ================================================================== o que ja funcionava

    @Test
    void continuaApagandoItens_historicoEoPedido() {
        pedido(StatusPedido.RECEBIDO, false, null);

        service.excluir(500L);

        verify(itemRepo).deleteByLojaIdAndPedidoId(LOJA, 500L);
        verify(logs).deleteByLojaIdAndPedidoId(LOJA, 500L);
        verify(repo).apagarDaLoja(500L, LOJA);
    }

    @Test
    void apagaPELOID_naoPelaEntidade() {
        // O estorno roda consultas com clearAutomatically, que limpam o contexto do Hibernate: o
        // objeto do pedido fica desanexado, e `delete(entidade)` num desanexado faz MERGE primeiro --
        // grava o estado em memoria de volta na linha e so depois apaga. Funciona por acidente, e
        // depende de um detalhe do ORM que ninguem lembra de olhar.
        pedido(StatusPedido.RECEBIDO, false, null);

        service.excluir(500L);

        verify(repo, never()).delete(any(Pedido.class));
        verify(repo).apagarDaLoja(500L, LOJA);
    }

    @Test
    void pedidoDeMARKETPLACEcontinuaDescontandoOcontador() {
        Pedido p = pedido(StatusPedido.RECEBIDO, false, null);
        p.canalExterno = "IFOOD";

        service.excluir(500L);

        verify(integracoes).descontarRecebido(LOJA, "IFOOD");
    }

    @Test
    void pedidoDoBALCAOnaoMexeNoContadorDeMarketplace() {
        pedido(StatusPedido.RECEBIDO, false, null);

        service.excluir(500L);

        verify(integracoes, never()).descontarRecebido(anyLong(), anyString());
    }
}
