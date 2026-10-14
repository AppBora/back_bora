package br.com.bora.service.marketplace;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.service.PedidoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Aceite automático que o marketplace recusou é reenviado, e aceite que ele aceitou não é repetido.
 *
 * <p><b>O defeito que isto fecha:</b> o aceite era enviado e, se falhasse, o cliente do marketplace
 * engolia a excecao com um {@code log.warn}. O poller confirmava o evento ao iFood de qualquer jeito, e
 * o caminho "ja importado" saia sem tentar de novo. O pedido ficava no painel da loja, o iFood o
 * cancelava sozinho por falta de aceite — depois de a cozinha ter preparado — e ninguem ficava sabendo
 * de nada, porque o unico registro era uma linha de log que ninguem le.</p>
 *
 * <p><b>Por que precisou de coluna (V48):</b> o pedido fica RECEBIDO aqui ate alguem clicar, aceito ou
 * nao pelo marketplace. Retentar com base no status reconfirmaria todo pedido ainda nao tocado — que
 * foi o "confirm em dobro" visto no primeiro pedido real de iFood, em 19/09. Quem sabe se o aceite
 * passou e {@code pedido.aceite_enviado_em}, e so ela.</p>
 */
class AceiteQueFalhaNaoSePerdeTest {

    private static final Long LOJA = 18L;
    private static final String ORDEM = "ifood-ped-1";

    private PedidoService pedidos;
    private IntegracaoCanalRepository repo;
    private MarketplaceClient client;
    private MarketplacePoller poller;
    private IntegracaoCanal integ;

    @BeforeEach
    void montar() {
        pedidos = mock(PedidoService.class);
        repo = mock(IntegracaoCanalRepository.class);
        client = mock(MarketplaceClient.class);
        when(client.canal()).thenReturn("IFOOD");

        integ = new IntegracaoCanal();
        integ.id = 7L;
        integ.lojaId = LOJA;
        integ.canal = "IFOOD";
        integ.ativo = true;
        integ.autoAceitar = true;

        poller = new MarketplacePoller(java.util.List.of(client), repo,
                mock(br.com.bora.service.MarketplaceNormalizer.class), pedidos, mock(LojaRepository.class),
                mock(br.com.bora.security.RegraDeAcesso.class), true);
    }

    /** Chama o caminho "ja importado" do poller, que e onde o reenvio tem que acontecer. */
    private void eventoDoMesmoPedido(Pedido existente) {
        when(pedidos.buscarExterno(LOJA, "IFOOD", ORDEM)).thenReturn(Optional.of(existente));
        ReflectionTestUtils.invokeMethod(poller, "criarPedido", client, integ, ORDEM);
    }

    private Pedido pedido(OffsetDateTime aceiteEnviadoEm, StatusPedido status) {
        Pedido p = new Pedido();
        p.id = 900L;
        p.lojaId = LOJA;
        p.canalExterno = "IFOOD";
        p.idExterno = ORDEM;
        p.status = status;
        p.aceiteEnviadoEm = aceiteEnviadoEm;
        return p;
    }

    @Test
    void pedidoSemAceiteConfirmado_temOAceiteReenviado() {
        when(client.enviarStatus(any(), eq(ORDEM), eq(MarketplaceClient.ACEITE_INICIAL))).thenReturn(true);

        eventoDoMesmoPedido(pedido(null, StatusPedido.RECEBIDO));

        verify(client).enviarStatus(integ, ORDEM, MarketplaceClient.ACEITE_INICIAL);
        verify(pedidos).marcarAceiteEnviado(LOJA, 900L);
    }

    @Test
    void pedidoQueOMarketplaceJaAceitou_naoEhReconfirmado() {
        // Esta e a protecao contra o "confirm em dobro" de 19/09.
        eventoDoMesmoPedido(pedido(OffsetDateTime.now().minusMinutes(2), StatusPedido.RECEBIDO));

        verify(client, never()).enviarStatus(any(), anyString(), anyString());
        verify(pedidos, never()).marcarAceiteEnviado(anyLong(), anyLong());
    }

    @Test
    void pedidoQueALojaJaTocouNaMao_naoEhReconfirmado() {
        // Saiu de RECEBIDO: alguem aceitou pelo painel, e o painel ja empurra o status.
        eventoDoMesmoPedido(pedido(null, StatusPedido.EM_PREPARO));

        verify(client, never()).enviarStatus(any(), anyString(), anyString());
    }

    @Test
    void comAceiteAutomaticoDesligado_naoReenviaNada() {
        integ.autoAceitar = false;

        eventoDoMesmoPedido(pedido(null, StatusPedido.RECEBIDO));

        verify(client, never()).enviarStatus(any(), anyString(), anyString());
    }

    @Test
    void seOReenvioFalharDeNovo_aMarcaNaoEhGravadaEOErroApareceNaTela() {
        when(client.enviarStatus(any(), anyString(), anyString())).thenReturn(false);

        eventoDoMesmoPedido(pedido(null, StatusPedido.RECEBIDO));

        // Sem a marca, o proximo evento do mesmo pedido tenta outra vez.
        verify(pedidos, never()).marcarAceiteEnviado(anyLong(), anyLong());
        verify(repo).save(argThat(i -> i.ultimoErro != null && i.ultimoErro.contains(ORDEM)));
        assertNotNull(integ.ultimoErro, "a tela de Integracoes mostra `ultimo_erro`; o lojista precisa ver");
    }
}
