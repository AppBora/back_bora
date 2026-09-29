package br.com.bora.service;

import br.com.bora.dto.InboundOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 99Food no padrão Open Delivery. Até 29/09/2026 a 99 só era testada pelos scripts contra o mock, e o
 * QA avisou que isso prova pouco: o mock foi escrito por nós. Aqui os dois pedidos seguem o schema
 * oficial v1.7.1 (Order: type, items[].options, otherFees, discounts[].sponsorshipValues, total,
 * payments, delivery/takeout) — os mesmos campos que a checklist de homologação da 99 manda mostrar
 * na tela: valor dos itens, entrega, descontos e de quem, quanto já foi pago, quanto falta, quem
 * entrega, observação e número do pedido.
 */
class MarketplaceNormalizerNoveNoveTest {

    @SuppressWarnings("unchecked")
    private InboundOrder pedido(String arquivo) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/opendelivery/" + arquivo)) {
            Map<String, Object> raw = new ObjectMapper().readValue(in, Map.class);
            return new MarketplaceNormalizer().normalizar("NOVE_NOVE", raw);
        }
    }

    // ---------------------------------------------------------------- entrega pela loja

    @Test
    void totalTaxaENumeroDoPedido() throws Exception {
        InboundOrder o = pedido("pedido-entrega-loja.json");
        assertEquals(0, new BigDecimal("61").compareTo(o.total()), "total do pedido");
        assertEquals(0, new BigDecimal("7").compareTo(o.taxaEntrega()), "taxa de entrega");
        // A 99 chama o numero do pedido de "order_index" na checklist, mas no Open Delivery ele vem
        // em displayId — confirmado por escrito pelo time deles em 22/09/2026.
        assertEquals("4321", o.numeroExibicao());
        assertEquals("c-99-0001", o.clienteIdExterno());
    }

    @Test
    void itemLevaComplementoEObservacao() throws Exception {
        InboundOrder o = pedido("pedido-entrega-loja.json");
        assertEquals(1, o.itens().size());
        InboundOrder.InboundItem item = o.itens().get(0);
        assertTrue(item.nome().contains("Pizza Calabresa"), item.nome());
        assertTrue(item.nome().contains("Borda Catupiry"), "o complemento pago tem que aparecer: " + item.nome());
        assertTrue(item.nome().contains("obs: sem cebola"), "a observacao do item tem que aparecer: " + item.nome());
        // 47 do item + 12 da borda: quem cobra so o preco base entrega comida de graca.
        assertEquals(0, new BigDecimal("59").compareTo(item.precoUnitario()), "preco com o complemento");
    }

    @Test
    void dinheiroNaEntregaMostraOTroco() throws Exception {
        InboundOrder o = pedido("pedido-entrega-loja.json");
        assertEquals("Dinheiro na entrega — troco para R$ 100,00", o.pagamento());
    }

    @Test
    void observacaoTemEntregaDescontoTaxaEQuantoFalta() throws Exception {
        InboundOrder o = pedido("pedido-entrega-loja.json");
        String obs = o.observacao();
        assertTrue(obs.contains("Pedido 99 #4321"), obs);
        assertTrue(obs.contains("Entrega pela loja"), obs);
        assertTrue(obs.contains("Obs: Tocar a campainha duas vezes"), obs);
        assertTrue(obs.contains("Desconto R$ 5,00 (pago pela 99)"), "cupom da 99 x cupom da loja: " + obs);
        assertTrue(obs.contains("Taxa de entrega R$ 7,00"), obs);
        assertTrue(obs.contains("Ja pago R$ 0,00 · falta pagar R$ 61,00"), obs);
        assertEquals("Avenida T-2, 1922, Ap 301, em frente a praca", o.endereco());
        assertEquals("Setor Bueno", o.bairro());
    }

    // ---------------------------------------------------------------- retirada no balcao

    @Test
    void retiradaNaoMandaOEntregadorNemPedeEndereco() throws Exception {
        InboundOrder o = pedido("pedido-retirada.json");
        String obs = o.observacao();
        assertTrue(obs.contains("RETIRADA NO BALCÃO"), obs);
        assertTrue(obs.contains("cliente vem buscar às"), "a hora da retirada tem que aparecer: " + obs);
        assertFalse(obs.contains("Entrega pela loja"), "retirada nao pode virar entrega: " + obs);
        assertNull(o.endereco(), "pedido de retirada nao tem endereco de entrega");
        assertNull(o.bairro());
        assertNull(o.taxaEntrega(), "retirada nao cobra taxa de entrega");
    }

    @Test
    void retiradaPagaOnlineNaoCobraNadaNoBalcao() throws Exception {
        InboundOrder o = pedido("pedido-retirada.json");
        assertEquals("Pago online (99Food)", o.pagamento());
        assertEquals(0, new BigDecimal("24").compareTo(o.total()));
        assertEquals(0, new BigDecimal("12").compareTo(o.itens().get(0).precoUnitario()), "preco unitario de 2 itens");
    }
}
