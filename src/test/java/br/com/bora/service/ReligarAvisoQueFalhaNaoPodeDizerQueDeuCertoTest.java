package br.com.bora.service;

import br.com.bora.entity.Loja;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * "Religar aviso de pagamento" que FALHA no Asaas nao pode responder sucesso.
 *
 * <p><b>O defeito.</b> {@code criarWebhookPix} apaga os webhooks antigos ANTES de criar o novo, e
 * engole a falha da criacao num {@code catch} que so escreve no log. Quem decidia se deu certo era
 * esta guarda:</p>
 *
 * <pre>  if (loja.asaasWebhookToken == null || loja.asaasWebhookToken.isBlank()) -> 502</pre>
 *
 * <p>Ela funciona na PRIMEIRA vez, quando o campo esta vazio. No "religar" o campo tem o token
 * VELHO: a criacao falha, o token velho continua ali, a guarda nao dispara e o lojista recebe 200.
 * E o webhook antigo ja foi apagado do Asaas. Ou seja: o clique que existe para CONSERTAR o aviso de
 * pagamento era capaz de APAGA-LO e dizer que deu certo.</p>
 *
 * <p>O estrago e o mesmo de 15/10/2026, que o {@link AvisoDePagamentoEhConferidoNoAsaasTest}
 * registra: o cliente paga, o dinheiro entra na conta do lojista, nenhum aviso chega, e em 30
 * minutos o {@link CobradorDePixService} cancela o pedido devolvendo estoque e cashback.</p>
 *
 * <p>A guarda media a coisa errada -- "o campo tem algo" em vez de "a criacao de agora funcionou".
 * O conserto e exigir que o token tenha MUDADO.</p>
 *
 * <p>Sobe um Asaas de mentira em 127.0.0.1, como o teste irmao: sem rede, sem dependencia nova.</p>
 */
class ReligarAvisoQueFalhaNaoPodeDizerQueDeuCertoTest {

    private static final Long LOJA = 18L;
    private static final String URL_PUBLICA = "https://borahapp.com.br";
    private static final String TOKEN_VELHO = "token-velho-de-32-caracteres-aqu";

    private HttpServer servidor;

    /** O POST de criacao responde isto. 201 = Asaas aceitou; 500 = recusou. */
    private volatile int respostaDoPost = 201;
    /** O webhook ainda esta cadastrado no Asaas de mentira? */
    private volatile boolean existeNoAsaas = true;

    private volatile int apagados;
    private volatile int criacoesTentadas;

    @BeforeEach
    void subirAsaasDeMentira() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/webhooks", troca -> {
            String metodo = troca.getRequestMethod();
            int status;
            String corpo;
            if ("GET".equals(metodo)) {
                status = 200;
                corpo = existeNoAsaas
                        ? "{\"data\":[{\"id\":\"wh_1\",\"url\":\"" + URL_PUBLICA + "/public/pix-webhook/"
                                + LOJA + "\",\"enabled\":true,\"interrupted\":false}]}"
                        : "{\"data\":[]}";
            } else if ("DELETE".equals(metodo)) {
                apagados++;
                existeNoAsaas = false;      // o apagar e de verdade, como no Asaas
                status = 200;
                corpo = "{\"deleted\":true}";
            } else {                        // POST: a criacao
                criacoesTentadas++;
                status = respostaDoPost;
                if (status < 300) existeNoAsaas = true;
                corpo = status < 300 ? "{\"id\":\"wh_2\"}" : "{\"errors\":[{\"code\":\"invalid\"}]}";
            }
            byte[] b = corpo.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(status, b.length);
            try (OutputStream os = troca.getResponseBody()) { os.write(b); }
        });
        servidor.start();
    }

    @AfterEach
    void derrubar() {
        if (servidor != null) servidor.stop(0);
    }

    private Loja lojaQueJaTemToken() {
        Loja l = new Loja();
        l.setId(LOJA);
        l.setNome("Zirá - Montreal");
        l.asaasApiKey = "chave-da-subconta";
        l.asaasStatus = "ATIVO";
        l.asaasWebhookToken = TOKEN_VELHO;
        return l;
    }

    private AsaasSubcontaService servicoPara(Loja loja) {
        LojaRepository lojas = mock(LojaRepository.class);
        when(lojas.findById(LOJA)).thenReturn(Optional.of(loja));
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        return new AsaasSubcontaService("http://127.0.0.1:" + servidor.getAddress().getPort(),
                "chave-mae", URL_PUBLICA, "", lojas, mock(UsuarioRepository.class), ctx);
    }

    // ------------------------------------------------------------------ o defeito

    @Test
    void religarQueFALHAnoAsaasNAOpodeResponderSUCESSO() {
        respostaDoPost = 500;                       // o Asaas recusa a criacao
        Loja loja = lojaQueJaTemToken();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> servicoPara(loja).repararWebhook(),
                "a criacao falhou e o webhook antigo JA FOI APAGADO: responder 200 aqui deixa a loja "
                        + "sem aviso de pagamento nenhum, e o lojista achando que consertou");
        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatusCode());

        // E o que importa para quem le a tela: o estado real, nao a nossa coluna.
        assertEquals(1, apagados, "o antigo foi apagado antes da criacao -- e dai que vem a gravidade");
        assertEquals(1, criacoesTentadas);
        assertFalse(existeNoAsaas, "nao sobrou webhook nenhum no Asaas: e exatamente isto que a "
                + "resposta tem de contar");
    }

    @Test
    void religarQueFALHAnaoDEIXAotokenVELHOvalendoComoNOVO() {
        respostaDoPost = 500;
        Loja loja = lojaQueJaTemToken();

        assertThrows(ResponseStatusException.class, () -> servicoPara(loja).repararWebhook());

        // A transacao desfaz a gravacao, mas o objeto em memoria conta o que o codigo fez: o token
        // velho nao pode ter sido promovido a "token novo e bom".
        assertEquals(TOKEN_VELHO, loja.asaasWebhookToken,
                "o token velho continua velho -- ele nao vale para o webhook que acabou de ser apagado");
    }

    // ------------------------------------------------------------------ controle positivo

    @Test
    void religarQueDACERTOtrocaOtokenPORumNOVO() {
        respostaDoPost = 201;
        Loja loja = lojaQueJaTemToken();

        Map<String, Object> tela = servicoPara(loja).repararWebhook();

        assertNotEquals(TOKEN_VELHO, loja.asaasWebhookToken, "religar tem de gerar token NOVO");
        assertEquals(32, loja.asaasWebhookToken.length(), "UUID sem hifens");
        assertEquals(1, apagados, "o antigo sai antes, senao ele chega com token velho e leva 401 "
                + "nosso -- e falha repetida interrompe TODOS os webhooks da conta");
        assertEquals(1, criacoesTentadas);
        assertTrue(existeNoAsaas);
        assertEquals(true, tela.get("webhookOk"), "a tela confere no Asaas, e agora ha webhook la");
    }

    // ------------------------------------------------------------------ teste do teste

    @Test
    void oAsaasDEmentiraESTAsendoCHAMADO() {
        // Sem isto, um erro de URL faria os testes acima passarem sem nunca tocar no fluxo: o
        // `catch` de criarWebhookPix engole falha de rede igual a recusa do Asaas. Ja aconteceu
        // de um teste medir nada por causa disso.
        respostaDoPost = 201;
        servicoPara(lojaQueJaTemToken()).repararWebhook();

        assertTrue(apagados >= 1, "nenhum DELETE chegou: o teste nao esta falando com o Asaas de mentira");
        assertTrue(criacoesTentadas >= 1, "nenhum POST chegou: o teste nao esta exercitando a criacao");
    }

    @Test
    void semSubcontaNAOtentaNADAnoAsaas() {
        Loja l = lojaQueJaTemToken();
        l.asaasApiKey = null;

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> servicoPara(l).repararWebhook());

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertEquals(0, criacoesTentadas, "sem chave da subconta nao ha o que chamar");
    }
}
