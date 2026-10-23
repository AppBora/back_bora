package br.com.bora.service.marketplace;

import br.com.bora.entity.IntegracaoCanal;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O que o marketplace anda lá fora chega aqui — e o pedido para de congelar no painel.
 *
 * <h2>O defeito que isto fecha (item 5.6)</h2>
 * <p>De tudo o que o canal avisa, o Bora tratava <b>pedido novo</b> e <b>cancelamento</b>. Todo o
 * resto era confirmado ao marketplace e jogado fora. O caso que dói é a <b>entrega do próprio
 * marketplace</b>: quem sabe onde o motoboy está é o iFood, ele avisa que despachou e que concluiu, e
 * nada disso chegava aqui — o pedido ficava parado no painel, no status que a loja clicou por último,
 * <b>para sempre</b>. Ninguém na loja tem como saber que o pedido chegou ao cliente.</p>
 *
 * <p><b>O que medir corrigiu:</b> a 99 (Open Delivery) <b>já tinha isto</b> pelo webhook, para
 * PICKED_UP e DELIVERED. Quem ficou de fora foi o iFood, que anda por polling — e é por isso que o
 * conserto é no poller, e não num canal só.</p>
 */
class OcanalAvancaOpedidoAquiTest {

    private static final Long LOJA = 18L;
    private static final String ORDEM = "ifood-ped-1";

    private PedidoService pedidos;
    private MarketplaceClient client;
    private MarketplacePoller poller;
    private IntegracaoCanal integ;

    @BeforeEach
    void montar() {
        pedidos = mock(PedidoService.class);
        client = mock(MarketplaceClient.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        when(client.canal()).thenReturn("IFOOD");

        integ = new IntegracaoCanal();
        integ.id = 7L;
        integ.lojaId = LOJA;
        integ.canal = "IFOOD";
        integ.ativo = true;

        poller = new MarketplacePoller(List.of(client), mock(IntegracaoCanalRepository.class),
                mock(MarketplaceNormalizer.class), pedidos, mock(LojaRepository.class),
                mock(br.com.bora.security.RegraDeAcesso.class), true);
    }

    /** Um evento do canal, do jeito que o poller o recebe. */
    private void evento(String tipo) {
        Map<String, Object> ev = new HashMap<>();
        ev.put("id", "ev-1");
        ev.put("fullCode", tipo);
        ev.put("orderId", ORDEM);
        poller.tratarEvento(client, integ, ev);
    }

    // =============================================================== o avanco que faltava

    @Test
    void oIFOODdespachou_oPEDIDOsaiPARAentregaAQUI() {
        evento("DISPATCHED");

        verify(pedidos).avancarPorMarketplace(LOJA, "IFOOD", ORDEM, StatusPedido.SAIU_PARA_ENTREGA);
    }

    @Test
    void oIFOODconcluiu_oPEDIDOfICAentregueAQUI() {
        // Este e o caso que congelava o painel: entrega do iFood, ninguem na loja clica nada.
        evento("CONCLUDED");

        verify(pedidos).avancarPorMarketplace(LOJA, "IFOOD", ORDEM, StatusPedido.ENTREGUE);
    }

    @Test
    void oCODIGOabreviadoDOifoodTAMBEMconta() {
        // O iFood manda o tipo duas vezes: `fullCode` por extenso e `code` abreviado. Ja houve um
        // defeito exatamente nisso (todo PLACED chegava como "PLC" e o pedido era descartado).
        evento("DSP");
        evento("CON");
        // O RTP faltava aqui, e a rodada de mutacao mostrou: tirar "RTP" do classificador passava
        // verde, porque o teste da retirada mandava so a forma por extenso.
        evento("RTP");

        verify(pedidos).avancarPorMarketplace(LOJA, "IFOOD", ORDEM, StatusPedido.SAIU_PARA_ENTREGA);
        verify(pedidos).avancarPorMarketplace(LOJA, "IFOOD", ORDEM, StatusPedido.ENTREGUE);
        verify(pedidos).avancarPorMarketplace(LOJA, "IFOOD", ORDEM, StatusPedido.PRONTO);
    }

    @Test
    void prontoPARAretirada_viraPRONTOaqui() {
        evento("READY_TO_PICKUP");

        verify(pedidos).avancarPorMarketplace(LOJA, "IFOOD", ORDEM, StatusPedido.PRONTO);
    }

    @Test
    void osNOMESdaNOVEnoveTAMBEMsaoREconhecidos() {
        // A 99 ja tinha isto pelo webhook; pelo polling tem que valer o mesmo. Repetir e inofensivo:
        // o avancarPorMarketplace devolve false quando o pedido ja esta naquele status.
        evento("PICKED_UP");
        evento("DELIVERED");

        verify(pedidos).avancarPorMarketplace(LOJA, "IFOOD", ORDEM, StatusPedido.SAIU_PARA_ENTREGA);
        verify(pedidos).avancarPorMarketplace(LOJA, "IFOOD", ORDEM, StatusPedido.ENTREGUE);
    }

    // =============================================================== o que NAO pode virar avanco

    @Test
    void cancelamentoNAOentraPELOavanco() {
        when(pedidos.cancelarPorMarketplace(any(), any(), any(), any())).thenReturn(true);

        evento("CANCELLED");

        verify(pedidos).cancelarPorMarketplace(eq(LOJA), eq("IFOOD"), eq(ORDEM), any());
        verify(pedidos, never()).avancarPorMarketplace(any(), any(), any(), any());
    }

    @Test
    void pedidoNOVOnaoEavanco() {
        // PLACED cria o pedido. Se caisse no avanco, o pedido nunca seria importado.
        // O detalhe precisa vir preenchido: detalhe vazio e falha, e o poller estoura de proposito
        // (antes era um `return` silencioso e o pedido se perdia para sempre).
        when(client.detalhePedido(integ, ORDEM)).thenReturn(Map.of("id", ORDEM));
        when(pedidos.criarExterno(anyLong(), anyString(), anyString(), any()))
                .thenReturn(new br.com.bora.entity.Pedido());

        evento("PLACED");

        verify(pedidos).criarExterno(eq(LOJA), eq("IFOOD"), anyString(), any());
        verify(pedidos, never()).avancarPorMarketplace(any(), any(), any(), any());
    }

    @Test
    void eventoDESCONHECIDOnaoMEXEemNADA() {
        evento("SEPARATION_STARTED");
        evento("ASSIGN_DRIVER");

        verify(pedidos, never()).avancarPorMarketplace(any(), any(), any(), any());
        verify(pedidos, never()).cancelarPorMarketplace(any(), any(), any(), any());
    }

    @Test
    void oCLASSIFICADORnaoINVENTAstatus() {
        // A lista do classificador, direta: so estes avancam. Mutante que mapeie evento a mais cai
        // aqui -- e um mapa errado mandaria pedido para frente sem o canal ter dito nada.
        assertNull(client.statusQueOcanalAvancou("PLACED"));
        assertNull(client.statusQueOcanalAvancou("CONFIRMED"));
        assertNull(client.statusQueOcanalAvancou("CANCELLED"));
        assertNull(client.statusQueOcanalAvancou("ORDER_CANCELLATION_REQUEST"));
        assertNull(client.statusQueOcanalAvancou(null));
        assertNull(client.statusQueOcanalAvancou("qualquer coisa"));

        assertEquals(StatusPedido.SAIU_PARA_ENTREGA, client.statusQueOcanalAvancou("dispatched"),
                "e tem que funcionar em minusculas: canal nenhum promete caixa alta");
    }
}
