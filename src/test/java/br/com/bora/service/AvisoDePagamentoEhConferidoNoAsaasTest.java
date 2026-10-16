package br.com.bora.service;

import br.com.bora.entity.Loja;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * A tela de Integrações pergunta ao ASAAS se o aviso de pagamento existe.
 *
 * <p><b>O que aconteceu de verdade (15/10/2026):</b> a loja 18 tinha {@code asaas_webhook_token}
 * gravado no nosso banco e a subconta dela tinha <b>zero</b> webhooks no Asaas. A tela lia só a nossa
 * coluna, mostrava "ativo" e <b>escondia</b> o botão de religar. Nesse estado o cliente paga, o
 * dinheiro entra na conta do lojista, nenhum aviso chega, e em 30 minutos o
 * {@link CobradorDePixService} cancela o pedido devolvendo estoque e cashback.</p>
 *
 * <p>A coluna só prova que a criação do webhook deu certo <b>um dia</b>. O Asaas interrompe sozinho
 * um webhook que falha várias vezes, e o lojista pode apagá-lo no painel dele: nos dois casos a nossa
 * coluna continua lá, intacta e mentindo.</p>
 *
 * <p>O teste sobe um servidor HTTP de mentira no lugar do Asaas — sem rede, sem dependência nova.</p>
 */
class AvisoDePagamentoEhConferidoNoAsaasTest {

    private static final Long LOJA = 18L;
    private static final String URL_PUBLICA = "https://borahapp.com.br";

    private HttpServer servidor;
    private final AtomicReference<String> respostaDosWebhooks = new AtomicReference<>("{\"data\":[]}");
    private int chamadas;

    @BeforeEach
    void subirAsaasDeMentira() throws IOException {
        chamadas = 0;
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/webhooks", troca -> {
            chamadas++;
            byte[] corpo = respostaDosWebhooks.get().getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, corpo.length);
            try (OutputStream os = troca.getResponseBody()) { os.write(corpo); }
        });
        servidor.start();
    }

    @AfterEach
    void derrubar() {
        if (servidor != null) servidor.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    private Loja lojaComSubconta() {
        Loja l = new Loja();
        l.setId(LOJA);
        l.setNome("Zirá Açaíteria");
        l.asaasApiKey = "chave-da-subconta";
        l.asaasStatus = "ATIVO";
        l.asaasWebhookToken = "token-gravado-no-nosso-banco";
        return l;
    }

    private Map<String, Object> telaDe(Loja loja) {
        LojaRepository lojas = mock(LojaRepository.class);
        when(lojas.findById(LOJA)).thenReturn(Optional.of(loja));
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        AsaasSubcontaService s = new AsaasSubcontaService(baseUrl(), "chave-mae", URL_PUBLICA, "",
                lojas, mock(UsuarioRepository.class), ctx);
        return s.status();
    }

    /** Resposta do Asaas com um webhook apontando para a URL desta loja. */
    private String webhookNosso(boolean ligado, boolean interrompido) {
        return "{\"data\":[{\"id\":\"wh_1\",\"url\":\"" + URL_PUBLICA + "/public/pix-webhook/" + LOJA
                + "\",\"enabled\":" + ligado + ",\"interrupted\":" + interrompido + "}]}";
    }

    @Test
    void semWebhookNoAsaas_aTelaACUSA_mesmoComOTokenGravado() {
        respostaDosWebhooks.set("{\"data\":[]}");

        Map<String, Object> tela = telaDe(lojaComSubconta());

        assertEquals(false, tela.get("webhookOk"),
                "era exatamente este o estado da loja 18 em producao: token no banco, nada no Asaas");
        assertEquals(true, tela.get("webhookConferido"));
        assertEquals(1, chamadas, "a tela precisa PERGUNTAR, nao deduzir da propria coluna");
    }

    @Test
    void comWebhookLigado_aTelaDizQueEstaOk() {
        respostaDosWebhooks.set(webhookNosso(true, false));

        assertEquals(true, telaDe(lojaComSubconta()).get("webhookOk"));
    }

    @Test
    void webhookINTERROMPIDOpeloAsaasContaComoDesligado() {
        // O Asaas interrompe o envio depois de varias falhas seguidas. O webhook continua cadastrado,
        // e so olhar a lista acharia que esta tudo bem -- mas nenhum aviso sai de la.
        respostaDosWebhooks.set(webhookNosso(true, true));

        assertEquals(false, telaDe(lojaComSubconta()).get("webhookOk"));
    }

    @Test
    void webhookDESLIGADOcontaComoDesligado() {
        respostaDosWebhooks.set(webhookNosso(false, false));

        assertEquals(false, telaDe(lojaComSubconta()).get("webhookOk"));
    }

    @Test
    void webhookDEOUTRALOJAnaoVale() {
        // Numa rede, a mesma conta Asaas pode ter webhook de outra loja. A URL carrega o id, e e ela
        // que decide: o aviso da loja 19 nunca vai confirmar pedido da loja 18.
        respostaDosWebhooks.set("{\"data\":[{\"id\":\"wh_9\",\"url\":\"" + URL_PUBLICA
                + "/public/pix-webhook/19\",\"enabled\":true,\"interrupted\":false}]}");

        assertEquals(false, telaDe(lojaComSubconta()).get("webhookOk"));
    }

    @Test
    void asaasForaDoAr_naoACUSAfalhaQueNinguemMediu() throws IOException {
        servidor.stop(0);   // derruba o Asaas de mentira no meio

        Map<String, Object> tela = telaDe(lojaComSubconta());

        assertEquals(true, tela.get("webhookOk"), "sem conseguir perguntar, vale a coluna");
        assertEquals(false, tela.get("webhookConferido"), "mas fica registrado que nao deu para conferir");
    }

    @Test
    void lojaSemSubconta_nemPerguntaAoAsaas() {
        Loja sem = new Loja();
        sem.setId(LOJA);
        sem.setNome("Zirá Centro");

        Map<String, Object> tela = telaDe(sem);

        assertEquals(false, tela.get("provisionada"));
        assertEquals(false, tela.get("webhookOk"));
        assertEquals(0, chamadas, "sem conta nao ha o que perguntar");
    }
}
