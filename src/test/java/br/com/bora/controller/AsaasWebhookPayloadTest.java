package br.com.bora.controller;

import br.com.bora.service.AssinaturaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * O aviso do Asaas traz o identificador da cobrança, o valor e a data — e tudo isso era jogado fora:
 * só o número da assinatura seguia adiante. Sem esses três campos não há faturamento do mês nem nota
 * fiscal da mensalidade.
 */
class AsaasWebhookPayloadTest {

    private AssinaturaService service;
    private AsaasWebhookController controller;

    @BeforeEach
    void montar() {
        service = mock(AssinaturaService.class);
        controller = new AsaasWebhookController(service, "");
    }

    private Map<String, Object> aviso(Map<String, Object> pagamento) {
        Map<String, Object> p = new HashMap<>();
        p.put("event", "PAYMENT_CONFIRMED");
        p.put("payment", pagamento);
        return p;
    }

    private Object[] capturado() {
        ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BigDecimal> valor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<OffsetDateTime> quando = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(service).processarWebhook(eq("PAYMENT_CONFIRMED"), eq("sub_0001"),
                id.capture(), valor.capture(), quando.capture());
        return new Object[]{id.getValue(), valor.getValue(), quando.getValue()};
    }

    @Test
    void avisoCompletoChegaInteiroNoServico() {
        Map<String, Object> pag = new HashMap<>();
        pag.put("subscription", "sub_0001");
        pag.put("id", "pay_abc");
        pag.put("value", 199.0);
        pag.put("paymentDate", "2026-10-01");

        controller.receber(null, aviso(pag));

        Object[] c = capturado();
        assertEquals("pay_abc", c[0]);
        assertEquals(0, new BigDecimal("199.00").compareTo((BigDecimal) c[1]));
        assertEquals(1, ((OffsetDateTime) c[2]).getDayOfMonth());
        assertEquals(10, ((OffsetDateTime) c[2]).getMonthValue());
    }

    @Test
    void semPaymentDate_valeAConfirmedDate() {
        Map<String, Object> pag = new HashMap<>();
        pag.put("subscription", "sub_0001");
        pag.put("id", "pay_abc");
        pag.put("value", "199.00");
        pag.put("confirmedDate", "2026-09-30 00:00:00");

        controller.receber(null, aviso(pag));

        OffsetDateTime quando = (OffsetDateTime) capturado()[2];
        assertEquals(30, quando.getDayOfMonth());
        assertEquals(9, quando.getMonthValue());
    }

    @Test
    void valorOuDataEstranhos_naoDerrubamOWebhook() {
        Map<String, Object> pag = new HashMap<>();
        pag.put("subscription", "sub_0001");
        pag.put("id", "pay_abc");
        pag.put("value", "cento e noventa e nove");
        pag.put("paymentDate", "ontem");

        assertDoesNotThrow(() -> controller.receber(null, aviso(pag)));

        Object[] c = capturado();
        assertNull(c[1], "valor ilegivel vira nulo e quem grava usa o valor da assinatura");
        assertNull(c[2], "data ilegivel vira nula e quem grava usa a hora de agora");
    }

    @Test
    void tokenErradoNemChegaNoServico() {
        AsaasWebhookController comToken = new AsaasWebhookController(service, "segredo");
        Map<String, Object> pag = new HashMap<>();
        pag.put("subscription", "sub_0001");
        pag.put("id", "pay_abc");

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> comToken.receber("errado", aviso(pag)));
        verify(service, never()).processarWebhook(any(), any(), any(), any(), any());
    }
}
