package br.com.bora.service.marketplace;

import br.com.bora.dto.InboundOrder;
import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Pedido;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.service.MarketplaceNormalizer;
import br.com.bora.service.PedidoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pedido de TESTE do marketplace entra marcado, e nao como venda indistinguivel.
 *
 * <h2>O defeito que isto fecha (item 5.7)</h2>
 * <p>{@code isTest} nao aparecia em nenhum {@code .java} do projeto — conferido em 22/10/2026. Pedido
 * de teste entrava igual a pedido de verdade: baixava estoque, somava no faturamento, entrava no CMV.
 * E pedido de teste acontece justamente na <b>homologacao</b>, que e quando o lojista esta comecando
 * e olhando os numeros pela primeira vez — o pior momento para o relatorio mentir.</p>
 *
 * <h2>O que este teste NAO afirma</h2>
 * <p>Que o pedido de teste sai do faturamento. <b>Ele continua contando</b>: tirar exige coluna nova
 * em {@code pedido} e e decisao do dono. O que esta medido aqui e o que foi feito: ele passa a dizer
 * o que e, na observacao que a cozinha e o card mostram.</p>
 */
class PedidoDeTesteEntraMarcadoTest {

    private static final Long LOJA = 18L;
    private static final String ORDEM = "ifood-ped-teste";

    private PedidoService pedidos;
    private MarketplaceNormalizer normalizer;
    private MarketplaceClient client;
    private MarketplacePoller poller;
    private IntegracaoCanal integ;

    @BeforeEach
    void montar() {
        pedidos = mock(PedidoService.class);
        normalizer = mock(MarketplaceNormalizer.class);
        client = mock(MarketplaceClient.class);
        when(client.canal()).thenReturn("IFOOD");
        when(pedidos.buscarExterno(anyLong(), anyString(), anyString())).thenReturn(Optional.empty());

        Pedido criado = new Pedido();
        criado.id = 4242L;
        when(pedidos.criarExterno(anyLong(), anyString(), anyString(), any())).thenReturn(criado);

        integ = new IntegracaoCanal();
        integ.id = 7L;
        integ.lojaId = LOJA;
        integ.canal = "IFOOD";
        integ.ativo = true;

        poller = new MarketplacePoller(List.of(client), mock(IntegracaoCanalRepository.class),
                normalizer, pedidos, mock(LojaRepository.class),
                mock(br.com.bora.security.RegraDeAcesso.class), true);
    }

    /** O detalhe que o marketplace devolveu, e a observacao que o normalizador tirou dele. */
    private InboundOrder importar(Map<String, Object> detalhe, String observacaoOriginal) {
        when(client.detalhePedido(integ, ORDEM)).thenReturn(detalhe);
        when(normalizer.normalizar(eq("IFOOD"), any())).thenReturn(new InboundOrder(
                ORDEM, "Cliente", "11999990000", "Rua X, 1", "Centro", "DINHEIRO",
                observacaoOriginal, new BigDecimal("50.00"), List.of(), BigDecimal.ZERO, "1234"));

        ReflectionTestUtils.invokeMethod(poller, "criarPedido", client, integ, ORDEM);

        ArgumentCaptor<InboundOrder> captor = ArgumentCaptor.forClass(InboundOrder.class);
        verify(pedidos).criarExterno(eq(LOJA), eq("IFOOD"), anyString(), captor.capture());
        return captor.getValue();
    }

    private Map<String, Object> detalhe(String campo, Object valor) {
        Map<String, Object> d = new HashMap<>();
        d.put("id", ORDEM);
        if (campo != null) d.put(campo, valor);
        return d;
    }

    // =============================================================== o pedido de teste

    @Test
    void oISTESTdoIFOOD_viraAVISOnaOBSERVACAO() {
        InboundOrder entrou = importar(detalhe("isTest", true), null);

        assertTrue(entrou.observacao().contains("PEDIDO DE TESTE"),
                "a observacao foi: " + entrou.observacao());
        assertTrue(entrou.observacao().contains("IFOOD"),
                "tem que dizer de QUAL canal, senao a loja nao sabe onde cancelar: "
                        + entrou.observacao());
        assertTrue(entrou.observacao().contains("NAO PREPARE"),
                "o aviso tem que dizer o que fazer, nao so o que e: " + entrou.observacao());
    }

    @Test
    void oAVISOvemNAfrente_eNAOapagaAobservacaoDOcliente() {
        // Se o aviso fosse para o fim, ele sumiria no card truncado -- e apagar a observacao original
        // seria trocar um defeito por outro: "sem cebola" e informacao de cozinha.
        InboundOrder entrou = importar(detalhe("isTest", true), "sem cebola, por favor");

        assertTrue(entrou.observacao().startsWith("***"),
                "o aviso tem que vir primeiro: " + entrou.observacao());
        assertTrue(entrou.observacao().contains("sem cebola, por favor"),
                "a observacao do cliente nao pode se perder: " + entrou.observacao());
    }

    @Test
    void oTESTEcomoTEXTO_tambemEreconhecido() {
        // Payload de Open Delivery manda "true" em texto; o iFood manda booleano. Os dois aparecem.
        assertTrue(importar(detalhe("isTest", "true"), null).observacao().contains("PEDIDO DE TESTE"));
    }

    @Test
    void osOUTROSnomesDOcampo_tambemCONTAM() {
        for (String campo : List.of("test", "testOrder")) {
            montar();
            assertTrue(importar(detalhe(campo, true), null).observacao().contains("PEDIDO DE TESTE"),
                    "o campo " + campo + " nao foi reconhecido");
        }
    }

    // =============================================================== o pedido de verdade

    @Test
    void pedidoNORMAL_naoGANHAavisoNENHUM() {
        // O risco do outro lado: marcar venda de verdade como teste faria a loja CANCELAR um pedido
        // pago. Isso e pior que o defeito original.
        InboundOrder entrou = importar(detalhe(null, null), "sem cebola");

        assertEquals("sem cebola", entrou.observacao());
    }

    @Test
    void comISTESTfalso_naoGANHAaviso() {
        assertNull(importar(detalhe("isTest", false), null).observacao());
    }

    @Test
    void comISTESTemTEXTOfalso_naoGANHAaviso() {
        assertNull(importar(detalhe("isTest", "false"), null).observacao());
    }

    @Test
    void oPEDIDOdeTESTEnaoEreCUSADO() {
        // De proposito: a homologacao do iFood EXIGE que a integracao aceite e faca o pedido de teste
        // andar. Recusar passaria o defeito para outro lugar -- a loja nao conseguiria homologar.
        importar(detalhe("isTest", true), null);

        verify(pedidos).criarExterno(eq(LOJA), eq("IFOOD"), anyString(), any());
    }
}
