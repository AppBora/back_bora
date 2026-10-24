package br.com.bora.controller;

import br.com.bora.service.AssinaturaService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * O webhook do Asaas falhava ABERTO — item 18 da lista de 23/10.
 *
 * <h2>O que era</h2>
 * <p>A conferência do token era {@code if (token != null && !token.isBlank() && !token.equals(header))}.
 * Lida de trás para frente: <b>sem token configurado, qualquer um podia postar qualquer corpo</b>,
 * e a API aceitava calada. Hoje a variável está definida em produção — eu conferi —, mas ela é de
 * ambiente, e ambiente some: basta um deploy sem ela, ou um arquivo de env trocado, para a porta
 * abrir sozinha, sem erro e sem log.</p>
 *
 * <h2>Por que isso é grave</h2>
 * <p>O que entra por esta porta <b>libera e corta acesso de loja</b> a partir de evento de
 * pagamento. Quem conseguisse postar aqui manteria a própria loja no ar de graça, ou tiraria a de
 * um concorrente do ar. E não ficaria rastro: um webhook aceito é indistinguível de um legítimo.</p>
 *
 * <h2>Por que 503, e não 401</h2>
 * <p>Falta de configuração é problema NOSSO, não de quem chamou. E o Asaas <b>reenvia</b> o que
 * recebe 5xx: com 503 nada se perde, o pagamento entra quando a variável voltar, e o erro aparece
 * no painel do Asaas, que é onde alguém olha. Com 401 ele poderia desistir, e aí o dinheiro entrou
 * e o acesso não.</p>
 */
class RecusaWebhookSemTokenTest {

    private static final String TOKEN = "segredo-do-webhook";

    private Map<String, Object> aviso() {
        Map<String, Object> pag = new HashMap<>();
        pag.put("id", "pay_123");
        pag.put("subscription", "sub_1");
        pag.put("value", "199.00");
        Map<String, Object> p = new HashMap<>();
        p.put("event", "PAYMENT_CONFIRMED");
        p.put("payment", pag);
        return p;
    }

    // ---------------------------------------------------------------- a premissa
    @Test
    void semTokenConfigurado_recusa_eNaoProcessaNada() {
        AssinaturaService service = mock(AssinaturaService.class);
        AsaasWebhookController c = new AsaasWebhookController(service, "");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> c.receber(null, aviso()),
                "sem token configurado o webhook aceitou o corpo: a porta está aberta");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatusCode(),
                "precisa ser 5xx para o Asaas reenviar; com 4xx ele pode desistir");
        verifyNoInteractions(service);
    }

    @Test
    void semTokenConfigurado_recusa_mesmoQuemMandaUmHeaderQualquer() {
        // O atacante chuta um header. Antes isso passava, porque a comparação nem acontecia.
        AssinaturaService service = mock(AssinaturaService.class);
        AsaasWebhookController c = new AsaasWebhookController(service, "");

        assertThrows(ResponseStatusException.class, () -> c.receber("qualquer-coisa", aviso()));
        verifyNoInteractions(service);
    }

    @Test
    void tokenNulo_tambemRecusa() {
        // `@Value("${asaas.webhook-token:}")` dá string vazia, mas nulo é possível em teste e em
        // qualquer montagem manual — e o resultado tem de ser o mesmo.
        AssinaturaService service = mock(AssinaturaService.class);
        AsaasWebhookController c = new AsaasWebhookController(service, null);

        assertThrows(ResponseStatusException.class, () -> c.receber(null, aviso()));
        verifyNoInteractions(service);
    }

    @Test
    void tokenSoComEspacos_contaComoAusente() {
        AssinaturaService service = mock(AssinaturaService.class);
        AsaasWebhookController c = new AsaasWebhookController(service, "   ");

        assertThrows(ResponseStatusException.class, () -> c.receber("   ", aviso()));
        verifyNoInteractions(service);
    }

    // ---------------------------------------------------------------- o caminho de todo dia
    @Test
    void comTokenCerto_processa() {
        AssinaturaService service = mock(AssinaturaService.class);
        AsaasWebhookController c = new AsaasWebhookController(service, TOKEN);

        Map<String, Object> r = c.receber(TOKEN, aviso());

        assertEquals(true, r.get("received"));
        verify(service).processarWebhook(eq("PAYMENT_CONFIRMED"), eq("sub_1"), eq("pay_123"),
                any(), any(), any());
    }

    @Test
    void comTokenErrado_recusa_com401() {
        // Aqui o problema É de quem chamou: 401 está certo, e o Asaas não deve reenviar.
        AssinaturaService service = mock(AssinaturaService.class);
        AsaasWebhookController c = new AsaasWebhookController(service, TOKEN);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> c.receber("errado", aviso()));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void comTokenConfigurado_eSemHeader_recusa() {
        AssinaturaService service = mock(AssinaturaService.class);
        AsaasWebhookController c = new AsaasWebhookController(service, TOKEN);

        assertThrows(ResponseStatusException.class, () -> c.receber(null, aviso()));
        verifyNoInteractions(service);
    }
}
