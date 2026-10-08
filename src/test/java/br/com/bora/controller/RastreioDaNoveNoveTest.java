package br.com.bora.controller;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.service.PedidoService;
import br.com.bora.service.marketplace.MarketplacePoller;
import br.com.bora.service.marketplace.OpenDeliveryClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O aviso do entregador da 99 fecha o pedido no painel.
 *
 * <p>Em pedido que a 99 entrega, quem sabe onde o motoboy esta e ela — o Bora manda
 * {@code readyForPickup} e para por ali. Sem este webhook o pedido ficava em PRONTO para sempre: o
 * lojista nunca via "saiu" nem "entregue", e o quadro do dia enchia de pedido que ja chegou na casa
 * do cliente.</p>
 *
 * <p>A especificacao manda responder <b>200 com corpo vazio</b>; qualquer outra coisa faz a 99
 * reenviar. Por isso ate evento desconhecido responde 200: reenviar um aviso que nao sabemos tratar
 * so enche a fila deles.</p>
 */
class RastreioDaNoveNoveTest {

    private IntegracaoCanalRepository integracoes;
    private OpenDeliveryClient client;
    private PedidoService pedidos;
    private OpenDeliveryWebhookController controller;

    @BeforeEach
    void montar() {
        integracoes = mock(IntegracaoCanalRepository.class);
        client = mock(OpenDeliveryClient.class);
        pedidos = mock(PedidoService.class);

        IntegracaoCanal i = new IntegracaoCanal();
        i.lojaId = 18L;
        i.canal = "NOVE_NOVE";
        i.merchantId = "zira-acaiteria";
        when(client.canal()).thenReturn("NOVE_NOVE");
        when(integracoes.findFirstByCanalAndMerchantId("NOVE_NOVE", "zira-acaiteria"))
                .thenReturn(Optional.of(i));
        when(client.assinaturaValida(any(), any(), any())).thenReturn(true);

        controller = new OpenDeliveryWebhookController(integracoes, client, mock(LojaRepository.class),
                new br.com.bora.security.RegraDeAcesso(false), mock(MarketplacePoller.class),
                new ObjectMapper(), pedidos);
    }

    private String evento(String tipo) {
        return "{\"deliveryId\":\"d-1\",\"orderId\":\"ped-99\",\"merchant\":{\"id\":\"zira-acaiteria\"},"
                + "\"event\":{\"type\":\"" + tipo + "\",\"datetime\":\"2026-10-08T10:00:00Z\"}}";
    }

    @Test
    void entregadorPegouOPedido_ovirouSaiuParaEntrega() {
        var r = controller.eventoDeRastreio("zira-acaiteria", "assinatura", evento("PICKED_UP"));

        assertEquals(200, r.getStatusCode().value(), "a 99 reenvia se nao for 200");
        verify(pedidos).avancarPorMarketplace(18L, "NOVE_NOVE", "ped-99", StatusPedido.SAIU_PARA_ENTREGA);
    }

    @Test
    void entregou_fechaOPedido() {
        controller.eventoDeRastreio("zira-acaiteria", "assinatura", evento("DELIVERED"));

        verify(pedidos).avancarPorMarketplace(18L, "NOVE_NOVE", "ped-99", StatusPedido.ENTREGUE);
    }

    @Test
    void logisticaCancelou_cancelaNoBora() {
        controller.eventoDeRastreio("zira-acaiteria", "assinatura", evento("CANCELLED"));

        verify(pedidos).cancelarPorMarketplace(eq(18L), eq("NOVE_NOVE"), eq("ped-99"), anyString());
    }

    @Test
    void eventoQueNaoConhecemos_respondeOkEmVezDeFazerA99Reenviar() {
        var r = controller.eventoDeRastreio("zira-acaiteria", "assinatura", evento("DRIVER_ARRIVED"));

        assertEquals(200, r.getStatusCode().value());
        verify(pedidos, never()).avancarPorMarketplace(any(), any(), any(), any());
        verify(pedidos, never()).cancelarPorMarketplace(any(), any(), any(), any());
    }

    @Test
    void assinaturaInvalida_naoMexeEmNada() {
        when(client.assinaturaValida(any(), any(), any())).thenReturn(false);

        var e = assertThrows(ResponseStatusException.class,
                () -> controller.eventoDeRastreio("zira-acaiteria", "falsa", evento("DELIVERED")));

        assertEquals(403, e.getStatusCode().value());
        verify(pedidos, never()).avancarPorMarketplace(any(), any(), any(), any());
    }

    @Test
    void eventoSemPedido_naoQuebraENaoPedeReenvio() {
        var r = controller.eventoDeRastreio("zira-acaiteria", "assinatura",
                "{\"event\":{\"type\":\"DELIVERED\"}}");

        assertEquals(200, r.getStatusCode().value());
        verify(pedidos, never()).avancarPorMarketplace(any(), any(), any(), any());
    }
}
