package br.com.bora.service;

import br.com.bora.dto.InboundOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pedido REAL da loja de teste do iFood (19/09/2026, consultado pela ferramenta de suporte do Bora).
 * Antes da reescrita ele aparecia como R$ 10,00 (só o preço base dos itens); o certo é R$ 27,00.
 */
class MarketplaceNormalizerIfoodTest {

    @SuppressWarnings("unchecked")
    private InboundOrder pedidoReal() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/ifood/pedido-real-loja-teste.json")) {
            Map<String, Object> raw = new ObjectMapper().readValue(in, Map.class);
            return new MarketplaceNormalizer().normalizar("IFOOD", raw);
        }
    }

    @Test
    void totalTaxaENumeroDoPedido() throws Exception {
        InboundOrder o = pedidoReal();
        assertEquals(0, new BigDecimal("27").compareTo(o.total()), "total do pedido");
        assertEquals(0, new BigDecimal("5").compareTo(o.taxaEntrega()), "taxa de entrega");
        assertEquals("1022", o.numeroExibicao());
        assertEquals("dace8b15-e2be-408e-9b98-91b4e72e029f", o.clienteIdExterno());
    }

    @Test
    void itensComComplementosEPrecoCheio() throws Exception {
        InboundOrder o = pedidoReal();
        assertEquals(2, o.itens().size());
        InboundOrder.InboundItem combo = o.itens().get(1);
        assertEquals(0, new BigDecimal("16").compareTo(combo.precoUnitario()), "combo com complementos");
        assertTrue(combo.nome().contains("Complemento 1 - Segundo Nível"), combo.nome());
        assertTrue(combo.nome().contains("Customização 1 do Complemento 4"), combo.nome());
    }

    @Test
    void doisCartoesComBandeira() throws Exception {
        InboundOrder o = pedidoReal();
        assertEquals("Pago online: Crédito Visa R$ 17,00 + Pago online: Crédito Master R$ 10,00", o.pagamento());
    }

    @Test
    void observacaoTelefoneEEntrega() throws Exception {
        InboundOrder o = pedidoReal();
        assertTrue(o.observacao().contains("Pedido iFood #1022"), o.observacao());
        assertTrue(o.observacao().contains("Entrega pela loja"), o.observacao());
        assertTrue(o.observacao().contains("Entrega: Este pedido foi gerado automaticamente"), o.observacao());
        assertTrue(o.observacao().contains("Taxa de entrega R$ 5,00"), o.observacao());
        assertEquals("0800 705 3040 · código 94013236", o.clienteTelefone());
        assertEquals("Rua TESTE, 999999, Complemento TESTE, TESTE", o.endereco());
    }
}
