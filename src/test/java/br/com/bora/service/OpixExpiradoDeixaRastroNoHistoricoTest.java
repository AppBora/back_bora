package br.com.bora.service;

import br.com.bora.entity.LogStatus;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.LogStatusRepository;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.repository.ProdutoRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O PIX que expira deixa escrito DE QUE status o pedido foi cancelado.
 *
 * <h2>O defeito que isto fecha (pente fino de 29/10/2026)</h2>
 * <p>{@code EstornoDaVendaService.rebaixarDeNovo} e o unico caminho que baixa estoque de novo quando
 * um pedido cancelado volta a valer. Ele decide o que baixar lendo o {@code log_status}, pelo
 * {@code statusAntesDoCancelamento}. O {@code CobradorDePixService} -- que e justamente quem cancela
 * o PIX de 30 minutos -- gravava o pedido direto, <b>sem nenhuma linha de historico</b>.</p>
 *
 * <p>Ou seja: no unico caso para o qual o {@code rebaixarDeNovo} foi escrito (o proprio javadoc dele
 * diz "um unico caso: o PIX do cardapio expirou, o pedido foi cancelado e DEPOIS o dinheiro
 * entrou"), ele nao achava o status anterior, logava "NADA foi baixado de novo" e devolvia 0. O
 * insumo que o cancelamento devolveu nunca saia outra vez, embora a comida fosse sair -- e se o
 * lojista seguisse a instrucao gravada na observacao do pedido e cancelasse de novo, o estoque era
 * devolvido uma SEGUNDA vez. Polpa que volta duas vezes ao pote para um acai que saiu uma.</p>
 *
 * <h2>Por que o teste antigo nao pegou</h2>
 * <p>{@code OpedidoReabertoNaoBaixaEstoqueDuasVezesTest} mocka o repositorio de log e <b>injeta um
 * historico montado a mao</b>. Ele mede a simetria com um cenario que a producao nunca produzia. Por
 * isso este teste olha para o outro lado: quem CANCELA tem de escrever a linha que o outro le.</p>
 */
class OpixExpiradoDeixaRastroNoHistoricoTest {

    private static final Long LOJA = 18L;

    private Pedido pixVencido(StatusPedido status) {
        Pedido p = new Pedido();
        p.id = 4242L;
        p.lojaId = LOJA;
        p.status = status;
        p.aguardandoPagamento = true;
        p.pagoEm = null;
        p.criadoEm = OffsetDateTime.now().minusHours(2);
        return p;
    }

    // ============================================================ o cobrador grava o rastro

    @Test
    void oCANCELAMENTOdoPIXvencidoREGISTRAdeQUEstatusVEIO() {
        PedidoRepository repo = mock(PedidoRepository.class);
        EstornoDaVendaService devolucao = mock(EstornoDaVendaService.class);
        Pedido p = pixVencido(StatusPedido.RECEBIDO);
        when(repo.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));

        new CobradorDePixService(repo, mock(FidelidadeService.class),
                mock(br.com.bora.repository.LojaRepository.class),
                mock(br.com.bora.repository.IntegracaoCanalRepository.class), mock(PixService.class),
                devolucao, GerenciadorDeTransacaoFalso.novo(), 30).expirarAbandonados();

        assertEquals(StatusPedido.CANCELADO, p.status);
        // RECEBIDO: e esse o status que o `rebaixarDeNovo` precisa achar depois, porque e com ele que
        // o `devolver` decidiu o que devolver. Gravar CANCELADO como anterior, ou nada, quebra a volta.
        verify(devolucao).registrarCancelamentoNoHistorico(p, StatusPedido.RECEBIDO);
    }

    @Test
    void oPEDIDOqueJAsaiuDAesperaNAOeCANCELADOeNAOgeraLINHA() {
        // Pedido EM_PREPARO com PIX vencido nao e cancelado de proposito (a comida saiu). Entao nao ha
        // cancelamento para registrar -- escrever linha aqui seria inventar historico.
        PedidoRepository repo = mock(PedidoRepository.class);
        EstornoDaVendaService devolucao = mock(EstornoDaVendaService.class);
        Pedido p = pixVencido(StatusPedido.EM_PREPARO);
        p.observacao = "";
        when(repo.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));

        new CobradorDePixService(repo, mock(FidelidadeService.class),
                mock(br.com.bora.repository.LojaRepository.class),
                mock(br.com.bora.repository.IntegracaoCanalRepository.class), mock(PixService.class),
                devolucao, GerenciadorDeTransacaoFalso.novo(), 30).expirarAbandonados();

        assertEquals(StatusPedido.EM_PREPARO, p.status);
        verify(devolucao, org.mockito.Mockito.never())
                .registrarCancelamentoNoHistorico(any(), any());
    }

    // ============================================================ e a linha gravada e a certa

    @Test
    void aLINHAgravadaTEMaLOJAoPEDIDOeOSdoisSTATUS() {
        LogStatusRepository logs = mock(LogStatusRepository.class);
        EstornoDaVendaService servico = new EstornoDaVendaService(mock(PedidoItemRepository.class),
                mock(ProdutoRepository.class), mock(InsumoService.class), mock(FidelidadeService.class),
                logs);
        Pedido p = pixVencido(StatusPedido.CANCELADO);

        servico.registrarCancelamentoNoHistorico(p, StatusPedido.CONFIRMADO);

        ArgumentCaptor<LogStatus> gravado = ArgumentCaptor.forClass(LogStatus.class);
        verify(logs).save(gravado.capture());
        LogStatus l = gravado.getValue();
        assertEquals(LOJA, l.getLojaId(), "linha sem loja nao e achada pela consulta do historico");
        assertEquals(4242L, l.getPedidoId());
        assertEquals("CONFIRMADO", l.getStatusAnterior(),
                "e este campo que o statusAntesDoCancelamento le; errado aqui, o estoque nao volta a baixar");
        assertEquals("CANCELADO", l.getStatusNovo(),
                "a consulta so olha as linhas com status_novo = CANCELADO");
        assertNull(l.getUsuarioId(), "quem cancelou foi o sistema, nao um usuario do painel");
    }

    @Test
    void semPEDIDOnaoGRAVAnada() {
        LogStatusRepository logs = mock(LogStatusRepository.class);
        EstornoDaVendaService servico = new EstornoDaVendaService(mock(PedidoItemRepository.class),
                mock(ProdutoRepository.class), mock(InsumoService.class), mock(FidelidadeService.class),
                logs);

        servico.registrarCancelamentoNoHistorico(null, StatusPedido.RECEBIDO);
        Pedido semId = new Pedido();
        semId.lojaId = LOJA;
        servico.registrarCancelamentoNoHistorico(semId, StatusPedido.RECEBIDO);

        verify(logs, org.mockito.Mockito.never()).save(any(LogStatus.class));
    }
}
