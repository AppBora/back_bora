package br.com.bora.service.marketplace;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.repository.IntegracaoCanalRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Token recusado pelo canal (401) é jogado fora na hora — e o 403 não.
 *
 * <h2>O defeito que isto fecha (confirmado em 10/11/2026)</h2>
 * <p>O token fica em cache até o {@code tokenExpiraEm}: <b>1 hora</b> na 99Food e <b>6 horas</b> no
 * iFood. Se o canal <b>revogar</b> o token antes disso — credencial trocada no portal, aplicativo
 * suspenso, rotação do lado deles —, toda chamada volta 401 e o {@code catch} do polling apenas
 * gravava a hora e a mensagem. O {@code vigente()} continuava dizendo que o token servia, e a rodada
 * seguinte mandava <b>o mesmo token morto</b>, de 30 em 30 segundos, até o vencimento.</p>
 *
 * <p>Na prática: a loja some do aplicativo do marketplace por até seis horas, nenhum pedido entra, e
 * a tela de Integrações continua dizendo que está tudo certo. É ameaça direta ao 99Food das 5 lojas
 * da Zirá, que passou a funcionar em 10/11/2026.</p>
 *
 * <h2>Por que o 403 fica de fora, e isso é parte do conserto</h2>
 * <p>403 quer dizer "o token vale, mas este recurso não é seu". Jogar o token fora ali faria o
 * sistema reautenticar a cada 30 segundos, para sempre, numa loja que nunca vai funcionar —
 * martelando o endpoint de token do canal. E <b>não é hipótese</b>: a loja 1 responde exatamente
 * 403 em produção hoje, por ser loja de teste não vinculada.</p>
 *
 * <h2>Como isto é medido</h2>
 * <p>Com um servidor HTTP <b>de verdade</b> (o mesmo padrão do {@code IfoodSaiDoErroSozinhoTest}),
 * que responde o status pedido. Mock do {@code RestClient} provaria só que eu sei escrever mock: o
 * que importa é que uma resposta 401 que atravessa a pilha HTTP inteira chegue ao {@code catch}
 * reconhecível.</p>
 */
class OtokenRECUSADOehJOGADOforaTest {

    private HttpServer servidor;
    private IntegracaoCanalRepository repo;
    private OpenDeliveryClient noveNove;
    private IfoodClient ifood;
    private int statusDoRecurso = 200;

    @BeforeEach
    void subirCanalFalso() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            String caminho = troca.getRequestURI().getPath();
            boolean ehToken = caminho.contains("token");
            // O token SEMPRE é emitido: o 401 que interessa é o da chamada de recurso, feita com um
            // token que o canal aceitou emitir e depois revogou.
            String corpo = ehToken
                    ? "{\"access_token\":\"tok-novo\",\"accessToken\":\"tok-novo\","
                      + "\"refreshToken\":\"ref-novo\",\"expires_in\":3600,\"expiresIn\":21600}"
                    : "[]";
            int status = ehToken ? 200 : statusDoRecurso;
            byte[] r = corpo.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(status, r.length);
            try (OutputStream o = troca.getResponseBody()) { o.write(r); }
        });
        servidor.start();
        String base = "http://127.0.0.1:" + servidor.getAddress().getPort();

        repo = mock(IntegracaoCanalRepository.class);
        when(repo.tentarPegarAvezDeRenovar(anyLong(), anyLong(), any(), any())).thenReturn(1);
        CredenciaisMarketplace cred = mock(CredenciaisMarketplace.class);
        when(cred.clientId(anyString())).thenReturn("app-id");
        when(cred.clientSecret(anyString())).thenReturn("app-secret");

        noveNove = new OpenDeliveryClient(base, cred, repo, new MarketplaceHttp());
        ifood = new IfoodClient(base, cred, repo, new MarketplaceHttp());
    }

    @AfterEach
    void derrubar() {
        if (servidor != null) servidor.stop(0);
    }

    private IntegracaoCanal loja(String canal) {
        IntegracaoCanal i = new IntegracaoCanal();
        i.id = 7L;
        i.lojaId = 18L;
        i.canal = canal;
        i.ativo = true;
        i.status = "CONECTADO";
        i.merchantId = "a-loja-la";
        i.accessToken = "tok-que-o-canal-ja-revogou";
        i.refreshToken = "ref";
        // Vigente por muito tempo: é exatamente esta folga que fazia o token morto ser reusado.
        i.tokenExpiraEm = OffsetDateTime.now().plusHours(5);
        return i;
    }

    // ===================================================================== 99Food

    @Test
    void naNOVEnove_o401JOGAoTOKENfora() {
        statusDoRecurso = 401;
        IntegracaoCanal i = loja("NOVE_NOVE");

        noveNove.polling(i);

        assertNull(i.accessToken,
                "o canal recusou este token: mante-lo em cache faz a proxima rodada mandar o MESMO "
                        + "token morto, por ate uma hora");
        assertNull(i.tokenExpiraEm, "sem isto o vigente() continuaria dizendo que o token serve");
        verify(repo).esquecerTokenRecusado(eq(7L), eq(18L), any(), any());
        verify(repo, never()).marcarPollingFalhou(anyLong(), anyLong(), any(), any());
        verify(repo, never()).save(any());   // item 5.5: so as colunas, nunca a linha inteira
    }

    @Test
    void naNOVEnove_o403NAOjogaOtokenFORA() {
        // 403 = o token vale, o recurso e que nao e seu. E o caso REAL da loja 1 em producao.
        statusDoRecurso = 403;
        IntegracaoCanal i = loja("NOVE_NOVE");

        noveNove.polling(i);

        assertEquals("tok-que-o-canal-ja-revogou", i.accessToken,
                "403 nao e credencial recusada: jogar o token fora aqui faria reautenticar a cada 30 "
                        + "segundos para sempre, martelando o endpoint de token do canal");
        assertNotNull(i.tokenExpiraEm);
        verify(repo, never()).esquecerTokenRecusado(anyLong(), anyLong(), any(), any());
        verify(repo).marcarPollingFalhou(eq(7L), eq(18L), any(), any());
    }

    @Test
    void naNOVEnove_pollingQUEfuncionaNAOmexeNOtoken() {
        statusDoRecurso = 200;
        IntegracaoCanal i = loja("NOVE_NOVE");

        noveNove.polling(i);

        assertEquals("tok-que-o-canal-ja-revogou", i.accessToken);
        verify(repo, never()).esquecerTokenRecusado(anyLong(), anyLong(), any(), any());
        verify(repo).marcarPollingOk(eq(7L), eq(18L), any());
    }

    // ===================================================================== iFood

    @Test
    void noIFOOD_o401JOGAoTOKENfora_masGUARDAoREFRESH() {
        statusDoRecurso = 401;
        IntegracaoCanal i = loja("IFOOD");

        ifood.polling(i);

        assertNull(i.accessToken, "no iFood o cache dura SEIS horas: insistir tira a loja do app por "
                + "uma tarde inteira");
        assertNull(i.tokenExpiraEm);
        assertEquals("ref", i.refreshToken,
                "o refresh token NAO se joga fora: e por ele que a renovacao acontece sem novo login");
        verify(repo).esquecerTokenRecusado(eq(7L), eq(18L), any(), any());
        verify(repo, never()).marcarPollingFalhou(anyLong(), anyLong(), any(), any());
    }

    @Test
    void noIFOOD_o403NAOjogaOtokenFORA() {
        statusDoRecurso = 403;
        IntegracaoCanal i = loja("IFOOD");

        ifood.polling(i);

        assertEquals("tok-que-o-canal-ja-revogou", i.accessToken);
        verify(repo, never()).esquecerTokenRecusado(anyLong(), anyLong(), any(), any());
        verify(repo).marcarPollingFalhou(eq(7L), eq(18L), any(), any());
    }

    // ===================================================================== a regra sozinha

    @Test
    void aREGRAreconhece401EsoO401() {
        assertTrue(MarketplaceHttp.credencialRecusada(
                org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "nao autorizado",
                        org.springframework.http.HttpHeaders.EMPTY, new byte[0], null)));

        assertFalse(MarketplaceHttp.credencialRecusada(
                org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.FORBIDDEN, "proibido",
                        org.springframework.http.HttpHeaders.EMPTY, new byte[0], null)));

        assertFalse(MarketplaceHttp.credencialRecusada(new RuntimeException("rede caiu")));
        assertFalse(MarketplaceHttp.credencialRecusada(null));
    }

    @Test
    void aREGRAacha401AINDAqueENVOLVIDOemOUTRAexcecao() {
        // O RestClient costuma embrulhar a excecao de HTTP. Se a regra so olhasse a excecao de fora,
        // o conserto nao funcionaria no caminho real -- e o teste passaria do mesmo jeito.
        Throwable dentro = org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "nao autorizado",
                org.springframework.http.HttpHeaders.EMPTY, new byte[0], null);

        assertTrue(MarketplaceHttp.credencialRecusada(new IllegalStateException("falhou", dentro)));
    }
}
