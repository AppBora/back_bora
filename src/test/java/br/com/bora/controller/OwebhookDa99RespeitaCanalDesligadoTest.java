package br.com.bora.controller;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Loja;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.security.RegraDeAcesso;
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
 * O webhook da 99 respeita o canal desligado no painel.
 *
 * <h2>O defeito (achado pelo QA em 26/10/2026, medido por mim em 27/10)</h2>
 * <p>O lojista desliga um canal em Integrações com {@code PUT /api/integracoes/{canal}} e
 * {@code ativo: false} ({@code IntegracaoService:240}); a linha <b>fica</b> no banco, com o
 * {@code merchantId}. O poller respeita esse {@code ativo} — o {@code conectadas} filtra por
 * {@code findByAtivoTrue}. <b>Este webhook não conferia</b>: pedido continuava entrando pela porta de
 * trás, com o canal desligado na tela, e o lojista não tinha como entender por quê.</p>
 *
 * <h2>503 e não 2xx</h2>
 * <p>Pelo mesmo motivo da loja suspensa, que já era tratada assim: um 2xx diz "aceito" por uma loja
 * que decidiu não receber, e aí o cliente final está esperando comida que ninguém vai preparar. Com
 * 503 a 99 reenvia e depois expira do lado dela, que é o que acontece com restaurante fechado.</p>
 *
 * <h2>Não está acontecendo hoje</h2>
 * <p>Medido na produção em 27/10/2026: nenhuma loja tem o canal da 99 ({@code NOVE_NOVE}) desligado
 * com {@code merchant_id} preenchido — as duas que o têm estão ativas. É a armadilha, não o estrago.
 * A loja 18 tem o <b>iFood</b> nesse estado ({@code ativo = f} com merchant), e o iFood entra por
 * polling, que já respeita o {@code ativo}.</p>
 */
class OwebhookDa99RespeitaCanalDesligadoTest {

    private static final Long LOJA = 18L;
    private static final String MERCHANT = "app-shop-123";

    private IntegracaoCanalRepository integracoes;
    private OpenDeliveryClient client;
    private LojaRepository lojas;
    private RegraDeAcesso regra;
    private MarketplacePoller poller;
    private OpenDeliveryWebhookController controller;

    @BeforeEach
    void montar() {
        integracoes = mock(IntegracaoCanalRepository.class);
        client = mock(OpenDeliveryClient.class);
        lojas = mock(LojaRepository.class);
        regra = mock(RegraDeAcesso.class);
        poller = mock(MarketplacePoller.class);

        when(client.canal()).thenReturn("NOVE_NOVE");
        when(client.assinaturaValida(any(), any(), any())).thenReturn(true);
        when(regra.podeOperar(any())).thenReturn(true);
        Loja l = new Loja();
        l.id = LOJA;
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));

        controller = new OpenDeliveryWebhookController(integracoes, client, lojas, regra, poller,
                new ObjectMapper(), mock(br.com.bora.service.PedidoService.class));
    }

    private IntegracaoCanal integracao(boolean ativo) {
        IntegracaoCanal i = new IntegracaoCanal();
        i.id = 9L;
        i.lojaId = LOJA;
        i.canal = "NOVE_NOVE";
        i.ativo = ativo;
        i.merchantId = MERCHANT;
        when(integracoes.findFirstByCanalAndMerchantId("NOVE_NOVE", MERCHANT))
                .thenReturn(Optional.of(i));
        return i;
    }

    private static final String EVENTO = "{\"eventId\":\"e1\",\"orderId\":\"o1\",\"eventType\":\"PLACED\"}";

    // ---------------------------------------------------------------- a premissa
    @Test
    void PREMISSA_comOcanalLIGADOoEVENTOehPROCESSADO() {
        // Sem isto, um webhook que recusa tudo faria o teste abaixo passar por motivo errado.
        integracao(true);

        assertDoesNotThrow(() -> controller.novoEvento(MERCHANT, "assinatura", EVENTO));

        verify(poller).tratarEvento(eq(client), any(), any());
    }

    // ---------------------------------------------------------------- o conserto
    @Test
    void comOcanalDESLIGADOoEVENTOehRECUSADO() {
        integracao(false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.novoEvento(MERCHANT, "assinatura", EVENTO));

        assertEquals(503, e.getStatusCode().value(),
                "tem de ser 503: um 2xx diz 'aceito' por uma loja que desligou o canal, e o cliente "
                        + "final fica esperando comida que ninguem vai preparar");
        verify(poller, never()).tratarEvento(any(), any(), any());
    }

    @Test
    void ativoNULOtambemEHdesligado() {
        // A coluna nasce `false`, mas linha antiga pode ter nulo. Nulo nao e permissao.
        IntegracaoCanal i = integracao(true);
        i.ativo = null;

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.novoEvento(MERCHANT, "assinatura", EVENTO));

        assertEquals(503, e.getStatusCode().value());
        verify(poller, never()).tratarEvento(any(), any(), any());
    }

    @Test
    void aRECUSAacontecePOISdaASSINATURA() {
        // A ordem importa: recusar antes de conferir a assinatura contaria a quem nao provou ser a 99
        // qual loja tem o canal desligado.
        integracao(false);
        when(client.assinaturaValida(any(), any(), any())).thenReturn(false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.novoEvento(MERCHANT, "assinatura", EVENTO));

        assertEquals(403, e.getStatusCode().value(), "assinatura invalida tem de vir primeiro");
    }

    // ---------------------------------------------------------------- o que nao pode mudar
    @Test
    void lojaQUEnaoPODEoperarCONTINUArecusada() {
        integracao(true);
        when(regra.podeOperar(any())).thenReturn(false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.novoEvento(MERCHANT, "assinatura", EVENTO));

        assertEquals(503, e.getStatusCode().value());
    }

    @Test
    void merchantDESCONHECIDOcontinua404() {
        when(integracoes.findFirstByCanalAndMerchantId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.novoEvento("nao-existe", "assinatura", EVENTO));

        assertEquals(404, e.getStatusCode().value());
    }

    // ---------------------------------------------------------------- o rastreio NAO ganha a guarda
    @Test
    void oRASTREIOcontinuaPASSANDOcomOcanalDESLIGADO() {
        // De propósito, e é a parte que exige cuidado: este aviso só FECHA um pedido que já entrou
        // antes. Travá-lo deixaria o pedido pendurado para sempre no painel — desligar o canal hoje
        // não pode congelar o pedido de ontem.
        integracao(false);
        var pedidos = mock(br.com.bora.service.PedidoService.class);
        controller = new OpenDeliveryWebhookController(integracoes, client, lojas, regra, poller,
                new ObjectMapper(), pedidos);

        String rastreio = "{\"orderId\":\"o1\",\"event\":{\"type\":\"DELIVERED\"}}";
        assertDoesNotThrow(() -> controller.eventoDeRastreio(MERCHANT, "assinatura", rastreio));

        verify(pedidos).avancarPorMarketplace(eq(LOJA), eq("NOVE_NOVE"), eq("o1"),
                eq(br.com.bora.entity.StatusPedido.ENTREGUE));
    }
}
