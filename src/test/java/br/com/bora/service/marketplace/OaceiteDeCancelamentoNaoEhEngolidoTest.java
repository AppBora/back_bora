package br.com.bora.service.marketplace;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.service.MarketplaceNormalizer;
import br.com.bora.service.PedidoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O cliente pede para cancelar na 99: ou o canal recebe a resposta, ou nada muda aqui.
 *
 * <h2>O defeito que isto fecha (pente fino de 29/10/2026)</h2>
 * <p>{@code OpenDeliveryClient.responderPedidoDeCancelamento} era {@code void} e <b>descartava</b> o
 * retorno do {@code post}. O {@code post} captura a falha e devolve {@code false} — o javadoc dele
 * diz, com estas palavras, "false na falha, para o chamador saber que ha o que reenviar" — e o
 * chamador nao olhava. Como nada estourava, o poller seguia em frente: cancelava o pedido no Bora
 * (devolvendo estoque e cashback) e CONFIRMAVA o evento na 99.</p>
 *
 * <p>Resultado: pedido cancelado aqui e <b>ativo la</b>, cliente esperando a comida e cobrado. Sem
 * aviso na tela e sem retentativa — o evento ja tinha sido confirmado, e o canal nao reenvia o que
 * foi confirmado.</p>
 *
 * <p>O caminho irmao — cancelamento pedido pela LOJA, {@code enviarCancelamento} — relanca de
 * proposito, e o comentario dele explica exatamente este risco. O que faltava era o mesmo cuidado no
 * caminho em que quem pede e o CLIENTE.</p>
 *
 * <h2>O que cada teste amarra</h2>
 * <ol>
 *   <li>falha no aceite: NAO cancela aqui, e estoura (para o evento nao ser confirmado);</li>
 *   <li>falha na recusa: estoura tambem — senao o canal nunca sabe que a loja negou;</li>
 *   <li>sucesso: cancela aqui, como sempre;</li>
 *   <li>canal que nao implementa a resposta (o iFood) segue cancelando — o padrao da interface e
 *       {@code true}, e nao pode ter virado {@code false} na troca de {@code void} por
 *       {@code boolean}.</li>
 * </ol>
 */
class OaceiteDeCancelamentoNaoEhEngolidoTest {

    private static final Long LOJA = 18L;
    private static final String ORDEM = "99-ped-7";

    private PedidoService pedidos;
    private MarketplaceClient client;
    private MarketplacePoller poller;
    private IntegracaoCanal integ;

    @BeforeEach
    void montar() {
        pedidos = mock(PedidoService.class);
        client = mock(MarketplaceClient.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        when(client.canal()).thenReturn("NOVENOVE");
        // E a 99 que PERGUNTA se pode cancelar; o padrao da interface e `false`.
        when(client.ehPedidoDeCancelamento(anyString())).thenReturn(true);

        Pedido p = new Pedido();
        p.id = 991L;
        p.lojaId = LOJA;
        p.status = StatusPedido.EM_PREPARO;   // a cozinha ainda pode parar => o poller ACEITA
        when(pedidos.buscarExterno(eq(LOJA), anyString(), anyString())).thenReturn(Optional.of(p));

        integ = new IntegracaoCanal();
        integ.id = 9L;
        integ.lojaId = LOJA;
        integ.canal = "NOVENOVE";
        integ.ativo = true;

        poller = new MarketplacePoller(List.of(client), mock(IntegracaoCanalRepository.class),
                mock(MarketplaceNormalizer.class), pedidos, mock(LojaRepository.class),
                mock(br.com.bora.security.RegraDeAcesso.class), true);
    }

    private void oClientePedeParaCancelar() {
        Map<String, Object> ev = new HashMap<>();
        ev.put("eventId", "ev-99");
        ev.put("eventType", "ORDER_CANCELLATION_REQUEST");
        ev.put("orderId", ORDEM);
        poller.tratarEvento(client, integ, ev);
    }

    @Test
    void aFALHAnoACEITEnaoCANCELAoPEDIDOaqui() {
        when(client.responderPedidoDeCancelamento(any(), anyString(), anyBoolean(), any()))
                .thenReturn(false);

        // Estourar e o que impede o evento de entrar em `paraConfirmar`: assim a 99 reenvia.
        assertThrows(IllegalStateException.class, this::oClientePedeParaCancelar,
                "a falha do aceite foi engolida: o evento vai ser confirmado e nunca mais volta");

        verify(pedidos, never()).cancelarPorMarketplace(any(), anyString(), anyString(), any());
    }

    @Test
    void aFALHAnaRECUSAtambemESTOURA() {
        Pedido saiu = new Pedido();
        saiu.id = 992L;
        saiu.lojaId = LOJA;
        saiu.status = StatusPedido.SAIU_PARA_ENTREGA;   // ja despachado => o poller NEGA
        when(pedidos.buscarExterno(eq(LOJA), anyString(), anyString())).thenReturn(Optional.of(saiu));
        when(client.responderPedidoDeCancelamento(any(), anyString(), anyBoolean(), any()))
                .thenReturn(false);

        assertThrows(IllegalStateException.class, this::oClientePedeParaCancelar,
                "a recusa falhou e foi engolida: a 99 nunca fica sabendo que a loja negou");

        verify(pedidos, never()).cancelarPorMarketplace(any(), anyString(), anyString(), any());
    }

    @Test
    void comORESPOSTAnoCANALoPEDIDOeCANCELADOaqui() {
        when(client.responderPedidoDeCancelamento(any(), anyString(), anyBoolean(), any()))
                .thenReturn(true);

        oClientePedeParaCancelar();

        verify(pedidos).cancelarPorMarketplace(eq(LOJA), eq("NOVENOVE"), eq(ORDEM), any());
    }

    @Test
    void oCANALqueNAOimplementaARESPOSTAsegueCANCELANDO() {
        // O iFood nao sobrescreve `responderPedidoDeCancelamento`. O padrao da interface tem de ser
        // `true`: se a troca de `void` por `boolean` tivesse deixado `false`, este caminho pararia de
        // cancelar e passaria a estourar em todo pedido de cancelamento.
        MarketplaceClient semResposta = mock(MarketplaceClient.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        when(semResposta.canal()).thenReturn("IFOOD");
        when(semResposta.ehPedidoDeCancelamento(anyString())).thenReturn(true);

        Map<String, Object> ev = new HashMap<>();
        ev.put("id", "ev-ifood");
        ev.put("fullCode", "ORDER_CANCELLATION_REQUEST");
        ev.put("orderId", ORDEM);
        poller.tratarEvento(semResposta, integ, ev);

        verify(pedidos).cancelarPorMarketplace(eq(LOJA), eq("NOVENOVE"), eq(ORDEM), any());
    }
}
