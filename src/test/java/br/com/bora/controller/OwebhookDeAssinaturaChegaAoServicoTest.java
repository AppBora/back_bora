package br.com.bora.controller;

import br.com.bora.service.AssinaturaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O evento de ASSINATURA do Asaas chega ao servico — e o dia do pagamento e o mesmo nos dois lados.
 *
 * <h2>Os dois defeitos que isto fecha (achados pelo QA em 26/10/2026)</h2>
 *
 * <p><b>1. O ramo {@code SUBSCRIPTION_DELETED} era inalcancavel pela porta HTTP.</b> O controller lia
 * o {@code subscription} so de DENTRO do objeto {@code payment}. Evento de assinatura nao tem
 * {@code payment}: o corpo traz a assinatura no topo. Entao o id chegava nulo, o
 * {@code processarWebhook} saia na primeira linha e este endpoint respondia <b>200 "received"</b>.
 * Todo o bloco escrito para o acidente da Zira era codigo morto, e falhava calado.</p>
 *
 * <p>Isso anulava uma decisao pendente do dono: marcar os eventos {@code SUBSCRIPTION_*} no painel do
 * Asaas nao teria efeito nenhum, e nao daria erro para avisar.</p>
 *
 * <p><b>2. O dia do pagamento era gravado em UTC.</b> O {@code dia()} usava
 * {@code ZoneId.systemDefault()} — no container, UTC — enquanto a guarda contra pagamento duplicado
 * procura o dia em America/Sao_Paulo. As tres primeiras horas do dia ficavam FORA da janela: o Asaas
 * confirmava pelo webhook, o dono registrava "recebi fora do Asaas" no mesmo dia, a guarda nao achava
 * o primeiro, e o faturamento contava o mesmo R$ 199 duas vezes. Esse faturamento e a base da nota
 * fiscal.</p>
 *
 * <h2>Por que os testes que existiam nao pegaram</h2>
 * <p>Os 20 testes que exercitam {@code SUBSCRIPTION_DELETED} chamam
 * {@code service.processarWebhook(...)} <b>direto</b>, nunca pelo controller — o padrao "teste verde
 * com cenario que nao existe". Estes aqui entram pela porta, com o corpo que o Asaas manda.</p>
 */
class OwebhookDeAssinaturaChegaAoServicoTest {

    private static final String TOKEN = "token-de-webhook-de-teste";
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    private AssinaturaService service;
    private AsaasWebhookController controller;

    @BeforeEach
    void montar() {
        service = mock(AssinaturaService.class);
        controller = new AsaasWebhookController(service, TOKEN);
    }

    /** O corpo que o Asaas manda num evento de ASSINATURA: `subscription` no topo, sem `payment`. */
    private Map<String, Object> corpoDeAssinatura(String evento, String subId) {
        Map<String, Object> sub = new HashMap<>();
        sub.put("id", subId);
        sub.put("status", "INACTIVE");
        sub.put("value", 298.00);
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("event", evento);
        corpo.put("subscription", sub);
        return corpo;
    }

    // ---------------------------------------------------------------- o id da assinatura
    @Test
    void SUBSCRIPTION_DELETED_chegaAoServicoComOid() {
        controller.receber(TOKEN, corpoDeAssinatura("SUBSCRIPTION_DELETED", "sub_cm8016j2p9r674d3"));

        verify(service).processarWebhook(eq("SUBSCRIPTION_DELETED"), eq("sub_cm8016j2p9r674d3"),
                any(), any(), any(), any());
    }

    @Test
    void oIDtambemEHlidoQuandoVEMcomoTEXTOsolto() {
        // Formato mais enxuto que o Asaas tambem usa. Aceitar os dois evita que esta correcao
        // precise voltar.
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("event", "SUBSCRIPTION_INACTIVATED");
        corpo.put("subscription", "sub_texto_solto");

        controller.receber(TOKEN, corpo);

        verify(service).processarWebhook(eq("SUBSCRIPTION_INACTIVATED"), eq("sub_texto_solto"),
                any(), any(), any(), any());
    }

    @Test
    void oPAGAMENTOcontinuaGANHANDOdaRAIZ() {
        // Evento de PAGAMENTO de uma assinatura traz os dois: o `payment.subscription` e o que
        // importa, e tem de continuar vencendo. Sem isto, o conserto do id quebraria o caminho que
        // ja funcionava.
        Map<String, Object> pay = new HashMap<>();
        pay.put("subscription", "sub_do_pagamento");
        pay.put("id", "pay_123");
        pay.put("value", 199.00);
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("event", "PAYMENT_RECEIVED");
        corpo.put("payment", pay);
        corpo.put("subscription", "sub_da_raiz");

        controller.receber(TOKEN, corpo);

        verify(service).processarWebhook(eq("PAYMENT_RECEIVED"), eq("sub_do_pagamento"),
                eq("pay_123"), any(), any(), any());
    }

    @Test
    void eventoDEassinaturaSEMidNAOeENGOLIDOemSILENCIO() {
        // O servico descarta (nao ha o que fazer sem id), mas antes ninguem tinha como descobrir que
        // o aviso nao foi aplicado: 200 na resposta e nada no log. Agora fica registrado.
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("event", "SUBSCRIPTION_DELETED");

        Map<String, Object> r = controller.receber(TOKEN, corpo);

        // A resposta segue 200 de proposito: reenviar nao resolveria um corpo sem id.
        assertEquals(Boolean.TRUE, r.get("received"));
        verify(service).processarWebhook(eq("SUBSCRIPTION_DELETED"), isNull(),
                any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- o dia do pagamento
    @Test
    void oDIAdoPAGAMENTOehGRAVADOnoFUSOdeBRASILIA() {
        Map<String, Object> pay = new HashMap<>();
        pay.put("subscription", "sub_1");
        pay.put("id", "pay_1");
        pay.put("value", 199.00);
        pay.put("paymentDate", "2026-10-15");
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("event", "PAYMENT_RECEIVED");
        corpo.put("payment", pay);

        controller.receber(TOKEN, corpo);

        var captor = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(service).processarWebhook(any(), any(), any(), any(), any(), captor.capture());

        OffsetDateTime esperado = LocalDate.of(2026, 10, 15).atStartOfDay(SP).toOffsetDateTime();
        assertEquals(esperado, captor.getValue(),
                "gravado em outro fuso: a guarda contra pagamento duplicado procura o dia em "
                        + "Brasilia, e as tres primeiras horas do dia em UTC caem fora da janela — "
                        + "ai o mesmo R$ 199 e contado duas vezes no faturamento");
    }

    @Test
    void oDIAgravadoCAIdentroDAjanelaQUEaGUARDAusa() {
        // A prova do defeito inteiro, do jeito que ele acontecia: o instante gravado tem de estar
        // dentro de [dia 00:00 em SP, dia+1 00:00 em SP), que e exatamente o intervalo que o
        // `pagamentosDaLojaNoDia` consulta.
        Map<String, Object> pay = new HashMap<>();
        pay.put("subscription", "sub_1");
        pay.put("paymentDate", "2026-10-15");
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("event", "PAYMENT_RECEIVED");
        corpo.put("payment", pay);

        controller.receber(TOKEN, corpo);

        var captor = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(service).processarWebhook(any(), any(), any(), any(), any(), captor.capture());

        LocalDate dia = LocalDate.of(2026, 10, 15);
        OffsetDateTime de = dia.atStartOfDay(SP).toOffsetDateTime();
        OffsetDateTime ate = dia.plusDays(1).atStartOfDay(SP).toOffsetDateTime();
        OffsetDateTime gravado = captor.getValue();

        assertFalse(gravado.isBefore(de), "gravado ANTES da janela: " + gravado + " < " + de);
        assertTrue(gravado.isBefore(ate), "gravado DEPOIS da janela: " + gravado + " >= " + ate);
    }

    @Test
    void semDATAnenhuma_oDIAvemNULO_eNAOviraHOJE() {
        // Quem grava decide o que fazer com nulo. Inventar "hoje" aqui mentiria sobre quando o
        // dinheiro entrou.
        Map<String, Object> pay = new HashMap<>();
        pay.put("subscription", "sub_1");
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("event", "PAYMENT_RECEIVED");
        corpo.put("payment", pay);

        controller.receber(TOKEN, corpo);

        verify(service).processarWebhook(any(), any(), any(), any(), any(), isNull());
    }

    // ---------------------------------------------------------------- o que nao pode mudar
    @Test
    void semTOKENconfigurado_oWEBHOOKcontinuaRECUSANDO() {
        AsaasWebhookController semToken = new AsaasWebhookController(service, "");
        var e = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> semToken.receber("qualquer", corpoDeAssinatura("SUBSCRIPTION_DELETED", "sub_1")));
        assertEquals(503, e.getStatusCode().value(), "tem de falhar FECHADO, e o Asaas reenvia");
        verify(service, never()).processarWebhook(any(), any(), any(), any(), any(), any());
    }

    @Test
    void comTOKENerrado_recusaSEMchamarOservico() {
        var e = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.receber("errado", corpoDeAssinatura("SUBSCRIPTION_DELETED", "sub_1")));
        assertEquals(401, e.getStatusCode().value());
        verify(service, never()).processarWebhook(any(), any(), any(), any(), any(), any());
    }
}
