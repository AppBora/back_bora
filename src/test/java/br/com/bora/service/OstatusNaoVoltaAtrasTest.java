package br.com.bora.service;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.*;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O status do pedido não volta atrás, e retirada não "sai para entrega".
 *
 * <h2>Item 6.6 da varredura</h2>
 * <p>O {@code alterarStatus} só barrava estado final. Aceitava qualquer pulo e <b>qualquer volta</b>.
 * Uma aba antiga do painel — aberta antes de o pedido andar — re-clicando "preparar" mandava o pedido
 * de volta de SAIU_PARA_ENTREGA para EM_PREPARO. E cada mudança de status <b>avisa o cliente no
 * WhatsApp</b>: ele recebia "seu pedido está sendo preparado" depois de o motoboy ter saído com ele.
 * Com várias telas abertas na loja ao mesmo tempo, isso não é hipótese.</p>
 *
 * <h2>Item 6.9</h2>
 * <p>Pedido de retirada aceitava SAIU_PARA_ENTREGA, e o cliente que estava <b>no balcão esperando</b>
 * recebia "saiu para entrega! Já já chega aí". A KDS oferecia "Despachar" para retirada porque o card
 * não sabia o tipo — desde o item 6.2 ele sabe, e aqui o servidor também recusa.</p>
 *
 * <h2>O que continua valendo: pular para frente</h2>
 * <p>A própria KDS pula CONFIRMADO (vai de RECEBIDO direto para EM_PREPARO), e loja que não usa
 * confirmação trabalha assim. Barrar pulo quebraria o uso normal; o que não pode é recuar nem repetir.</p>
 */
class OstatusNaoVoltaAtrasTest {

    private PedidoRepository repo;
    private NotificacaoClienteService notifCliente;
    private IntegracaoService integracoes;
    private PedidoService service;

    private static final Long LOJA = 1L;

    @BeforeEach
    void montar() {
        repo = mock(PedidoRepository.class);
        notifCliente = mock(NotificacaoClienteService.class);
        integracoes = mock(IntegracaoService.class);
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, LOJA, "GERENTE", "g@zira.com"));

        service = new PedidoService(repo, mock(PedidoItemRepository.class), mock(ProdutoRepository.class),
                mock(ClienteRepository.class), mock(LogStatusRepository.class), mock(PlanoService.class),
                integracoes, mock(TaxaEntregaRepository.class), mock(InsumoService.class),
                mock(FidelidadeService.class), mock(ComplementoService.class), ctx,
                mock(EstornoDaVendaService.class), GerenciadorDeTransacaoFalso.novo());
        ReflectionNotif.injetar(service, notifCliente);
        when(repo.save(any(Pedido.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** O serviço recebe o notificador por campo; o teste precisa dele para medir o aviso ao cliente. */
    static final class ReflectionNotif {
        static void injetar(PedidoService service, NotificacaoClienteService notif) {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "notifCliente", notif);
        }
    }

    private Pedido pedido(StatusPedido status, boolean retirada) {
        Pedido p = new Pedido();
        p.id = 500L;
        p.lojaId = LOJA;
        p.status = status;
        p.tipoEntrega = retirada ? "RETIRADA" : "ENTREGA";
        when(repo.findByIdAndLojaId(500L, LOJA)).thenReturn(Optional.of(p));
        return p;
    }

    // =============================================================== nao volta atras

    @Test
    void naoVOLTAdeSAIUparaEMPREPARO() {
        // A aba velha re-clicando "preparar". Antes isto mandava "esta sendo preparado" ao cliente
        // depois de o motoboy ter saido.
        Pedido p = pedido(StatusPedido.SAIU_PARA_ENTREGA, false);

        var erro = assertThrows(ResponseStatusException.class,
                () -> service.alterarStatus(500L, StatusPedido.EM_PREPARO, null));

        assertEquals(HttpStatus.CONFLICT, erro.getStatusCode());
        assertEquals(StatusPedido.SAIU_PARA_ENTREGA, p.status, "o status nao pode ter mudado");
        verify(notifCliente, never()).notificarFase(any(), any());
    }

    @Test
    void naoVOLTAdePRONTOparaRECEBIDO() {
        pedido(StatusPedido.PRONTO, false);

        assertThrows(ResponseStatusException.class,
                () -> service.alterarStatus(500L, StatusPedido.RECEBIDO, null));
    }

    @Test
    void oMESMOstatusDEnovoEHrecusado() {
        // Clique duplo, ou duas pessoas no mesmo card. Sem isto o cliente recebe o mesmo aviso duas
        // vezes -- e a loja parece desorganizada por um defeito nosso.
        pedido(StatusPedido.EM_PREPARO, false);

        var erro = assertThrows(ResponseStatusException.class,
                () -> service.alterarStatus(500L, StatusPedido.EM_PREPARO, null));

        assertTrue(erro.getReason().contains("já está"), "foi: " + erro.getReason());
        verify(notifCliente, never()).notificarFase(any(), any());
    }

    @Test
    void aMENSAGEMdizOqueFAZERemVEZdeSOrecusar() {
        pedido(StatusPedido.SAIU_PARA_ENTREGA, false);

        var erro = assertThrows(ResponseStatusException.class,
                () -> service.alterarStatus(500L, StatusPedido.EM_PREPARO, null));

        assertTrue(erro.getReason().contains("cancele"),
                "quem precisa desfazer tem que saber o caminho. Foi: " + erro.getReason());
    }

    // =============================================================== mas avanca, e pula

    @Test
    void aVANCARumaETAPAfunciona() {
        Pedido p = pedido(StatusPedido.EM_PREPARO, false);

        assertDoesNotThrow(() -> service.alterarStatus(500L, StatusPedido.PRONTO, null));

        assertEquals(StatusPedido.PRONTO, p.status);
        verify(notifCliente).notificarFase(p, StatusPedido.PRONTO);
    }

    @Test
    void oPULOdaKDScontinuaVALENDO() {
        // A KDS vai de RECEBIDO direto para EM_PREPARO: loja que nao usa confirmacao trabalha assim.
        // Barrar pulo quebraria o uso normal da cozinha.
        Pedido p = pedido(StatusPedido.RECEBIDO, false);

        assertDoesNotThrow(() -> service.alterarStatus(500L, StatusPedido.EM_PREPARO, null));

        assertEquals(StatusPedido.EM_PREPARO, p.status);
    }

    @Test
    void oPULOdeRECEBIDOdiretoParaENTREGUEfunciona() {
        // E o PDV: venda de balcao nasce e e entregue na hora.
        Pedido p = pedido(StatusPedido.RECEBIDO, true);

        assertDoesNotThrow(() -> service.alterarStatus(500L, StatusPedido.ENTREGUE, null));

        assertEquals(StatusPedido.ENTREGUE, p.status);
        assertNotNull(p.entregueEm);
    }

    @Test
    void CANCELARvaleDEqualquerESTADOnaoFINAL() {
        // Cancelar nao e "avancar": tem que poder de qualquer ponto.
        for (StatusPedido st : List.of(StatusPedido.RECEBIDO, StatusPedido.CONFIRMADO,
                StatusPedido.EM_PREPARO, StatusPedido.PRONTO, StatusPedido.SAIU_PARA_ENTREGA)) {
            Pedido p = pedido(st, false);
            assertDoesNotThrow(() -> service.alterarStatus(500L, StatusPedido.CANCELADO, "cliente desistiu"),
                    "nao deu para cancelar a partir de " + st);
            assertEquals(StatusPedido.CANCELADO, p.status);
        }
    }

    @Test
    void oESTADOfinalCONTINUAbarrado_comAmensagemDEsempre() {
        pedido(StatusPedido.ENTREGUE, false);

        var erro = assertThrows(ResponseStatusException.class,
                () -> service.alterarStatus(500L, StatusPedido.CANCELADO, "motivo"));

        assertTrue(erro.getReason().contains("estado final"), "foi: " + erro.getReason());
    }

    // =============================================================== retirada nao sai para entrega

    @Test
    void retiradaNAOsaiPARAentrega() {
        // O cliente esta no balcao. Antes ele recebia "saiu para entrega! Ja ja chega ai".
        Pedido p = pedido(StatusPedido.PRONTO, true);

        var erro = assertThrows(ResponseStatusException.class,
                () -> service.alterarStatus(500L, StatusPedido.SAIU_PARA_ENTREGA, null));

        assertEquals(StatusPedido.PRONTO, p.status);
        assertTrue(erro.getReason().contains("retirada"), "foi: " + erro.getReason());
        assertTrue(erro.getReason().contains("ENTREGUE"), "e tem que dizer o que fazer no lugar");
        verify(notifCliente, never()).notificarFase(any(), any());
    }

    @Test
    void retiradaVAIdePRONTOdiretoParaENTREGUE() {
        // O caminho certo da retirada, e tem que continuar aberto.
        Pedido p = pedido(StatusPedido.PRONTO, true);

        assertDoesNotThrow(() -> service.alterarStatus(500L, StatusPedido.ENTREGUE, null));

        assertEquals(StatusPedido.ENTREGUE, p.status);
    }

    @Test
    void ENTREGAnormalCONTINUAdespachando() {
        // O conserto nao pode barrar a entrega de verdade, que e o caso comum.
        Pedido p = pedido(StatusPedido.PRONTO, false);

        assertDoesNotThrow(() -> service.alterarStatus(500L, StatusPedido.SAIU_PARA_ENTREGA, null));

        assertEquals(StatusPedido.SAIU_PARA_ENTREGA, p.status);
    }

    @Test
    void pedidoANTESdaV48_semTIPO_contaComoENTREGA() {
        // `tipoEntrega` nulo cai na deducao pelo texto da observacao (ver Pedido.ehRetirada). Sem
        // texto de retirada, e entrega -- o comportamento de antes, para nao barrar pedido antigo.
        Pedido p = pedido(StatusPedido.PRONTO, false);
        p.tipoEntrega = null;

        assertDoesNotThrow(() -> service.alterarStatus(500L, StatusPedido.SAIU_PARA_ENTREGA, null));
    }

    @Test
    void retiradaREGISTRADAsoNAobservacaoTAMBEMeRECUSADA() {
        // Pedido anterior a V48 em que a retirada existia apenas como texto. O `ehRetirada` le os dois
        // caminhos, e o conserto tem que valer para os dois -- senao o pedido antigo segue mandando
        // "saiu para entrega" para quem esta no balcao.
        Pedido p = pedido(StatusPedido.PRONTO, false);
        p.tipoEntrega = null;
        p.observacao = "RETIRADA NO BALCAO";

        assertThrows(ResponseStatusException.class,
                () -> service.alterarStatus(500L, StatusPedido.SAIU_PARA_ENTREGA, null));
    }
}
