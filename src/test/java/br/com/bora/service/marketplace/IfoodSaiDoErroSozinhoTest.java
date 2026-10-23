package br.com.bora.service.marketplace;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.repository.IntegracaoCanalRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * O iFood que voltou a funcionar sai do ERRO sozinho — e o token renovado que nao gravou avisa o que e.
 *
 * <h2>Item 5.1: a loja presa na fila de 10 minutos</h2>
 * <p>O polling bem-sucedido limpava a mensagem de erro e deixava o <b>status</b> em {@code "ERRO"}.
 * Integracao em ERRO nao passa pelo {@code prontaParaSincronizar()}: ela so e tentada pelo caminho
 * espacado, uma vez a cada 10 minutos ({@code IntegracaoCanal.ESPERA_APOS_ERRO_MIN}). Entao bastava
 * <b>uma</b> falha qualquer — o iFood fora do ar por um minuto — para a loja ficar no ciclo de 10
 * minutos <b>para sempre</b>, mesmo com o polling voltando a funcionar na tentativa seguinte.</p>
 *
 * <p>E o polling e o batimento que mantem a loja <b>online</b> no aplicativo do iFood. De 30 segundos
 * para 10 minutos nao e "um pouco mais lento": e o restaurante aparecendo e desaparecendo do app, e o
 * pedido chegando minutos depois de o cliente ter feito. A 99 ({@code OpenDeliveryClient}) ja fazia o
 * reset desde o inicio; o iFood ficou de fora.</p>
 *
 * <h2>Item 5.4: o token que o iFood ja invalidou</h2>
 * <p>O iFood invalida o refresh token anterior quando emite o novo. Se a gravacao aqui falhar, o token
 * novo se perde e o antigo ja esta morto — a integracao para e nao ha conserto automatico. Nao da para
 * salvar o estado; da para dizer exatamente o que aconteceu e o que fazer.</p>
 */
class IfoodSaiDoErroSozinhoTest {

    private HttpServer servidor;
    private IntegracaoCanalRepository repo;
    private IfoodClient cliente;
    private int statusDoPolling = 200;
    private String corpoDoPolling = "[]";

    @BeforeEach
    void subirIfoodFalso() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            String caminho = troca.getRequestURI().getPath();
            boolean token = caminho.endsWith("/token");
            String corpo = token
                    ? "{\"accessToken\":\"tok-novo\",\"refreshToken\":\"ref-novo\",\"expiresIn\":21600}"
                    : corpoDoPolling;
            int status = token ? 200 : statusDoPolling;
            byte[] r = corpo.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(status, r.length);
            try (OutputStream o = troca.getResponseBody()) { o.write(r); }
        });
        servidor.start();

        repo = mock(IntegracaoCanalRepository.class);
        CredenciaisMarketplace cred = mock(CredenciaisMarketplace.class);
        when(cred.clientId("IFOOD")).thenReturn("app-id");
        when(cred.clientSecret("IFOOD")).thenReturn("app-secret");

        cliente = new IfoodClient("http://127.0.0.1:" + servidor.getAddress().getPort(),
                cred, repo, new MarketplaceHttp());
    }

    @AfterEach
    void derrubar() {
        if (servidor != null) servidor.stop(0);
    }

    /** Loja conectada de verdade, com token vigente (o polling nao precisa renovar). */
    private IntegracaoCanal loja(String status) {
        IntegracaoCanal i = new IntegracaoCanal();
        i.id = 7L;
        i.lojaId = 18L;
        i.canal = "IFOOD";
        i.ativo = true;
        i.status = status;
        i.merchantId = "merchant-da-loja";
        i.accessToken = "tok";
        i.refreshToken = "ref";
        i.tokenExpiraEm = OffsetDateTime.now().plusHours(5);
        i.ultimoErro = "o erro de ontem";
        return i;
    }

    // =============================================================== 5.1

    @Test
    void pollingQUEfuncionou_tiraALOJAdoERRO() {
        IntegracaoCanal i = loja("ERRO");

        cliente.polling(i);

        assertEquals("CONECTADO", i.status,
                "o polling funcionou: a loja tem que sair do ERRO, senao fica na fila de 10 minutos");
        assertNull(i.ultimoErro, "e a mensagem de erro antiga tem que sair da tela");
        verify(repo).save(i);
    }

    @Test
    void eELAvoltaPARAaFILAde30SEGUNDOS() {
        // O que importa de verdade nao e o texto do status: e por qual porta o ciclo de polling a
        // enxerga depois. Esta e a propriedade; o status e so como ela esta escrita hoje.
        IntegracaoCanal i = loja("ERRO");
        assertFalse(i.prontaParaSincronizar(), "antes: so o caminho espacado a alcanca");

        cliente.polling(i);

        assertTrue(i.prontaParaSincronizar(),
                "depois de voltar a funcionar ela tem que entrar pelo caminho normal, de 30 segundos");
        assertFalse(i.mereceNovaTentativa(), "e sair da fila de retentativa espacada");
    }

    @Test
    void lojaQUEjaESTAVAbem_continuaIGUAL() {
        IntegracaoCanal i = loja("CONECTADO");

        cliente.polling(i);

        assertEquals("CONECTADO", i.status);
        assertTrue(i.prontaParaSincronizar());
    }

    @Test
    void pollingQUEfalhou_naoPROMOVEninguem() {
        // O contrario do defeito: promover a loja quando a chamada falhou esconderia o problema.
        statusDoPolling = 500;
        IntegracaoCanal i = loja("ERRO");

        cliente.polling(i);

        assertEquals("ERRO", i.status, "falhou de novo: nao pode virar CONECTADO");
        assertNotNull(i.ultimoErro, "e o erro novo tem que ficar registrado");
        assertFalse(i.prontaParaSincronizar());
    }

    @Test
    void aguardandoAUTORIZACAOnaoEpromovida() {
        // Aqui falta o lojista digitar o codigo no portal. Promover para CONECTADO diria no painel que
        // os pedidos vao chegar sozinhos, e eles nao vao.
        IntegracaoCanal i = loja("AGUARDANDO_AUTORIZACAO");

        cliente.polling(i);

        assertEquals("AGUARDANDO_AUTORIZACAO", i.status);
    }

    // =============================================================== 5.4

    @Test
    void tokenRENOVADOeNAOgravado_dizOqueFAZERemVEZdeERROdeBANCO() {
        IntegracaoCanal i = loja("CONECTADO");
        i.tokenExpiraEm = OffsetDateTime.now().minusMinutes(1);   // obriga a renovar
        when(repo.save(any())).thenThrow(new RuntimeException("conexao com o banco caiu"));

        ResponseStatusException erro = assertThrows(ResponseStatusException.class,
                () -> cliente.tokenValido(i));

        assertTrue(erro.getReason().contains("REAUTORIZAR") || erro.getReason().contains("Reautorize"),
                "a mensagem tem que dizer o que a loja precisa fazer, porque nao ha conserto "
                        + "automatico. Foi: " + erro.getReason());
        assertTrue(erro.getReason().contains("iFood"), "e de qual integracao: " + erro.getReason());
        assertEquals("ERRO", i.status, "a integracao parou de verdade: tem que constar como ERRO");
    }

    @Test
    void aFALHAnaGRAVACAOnaoTENTAgravarDEnovoNOcatch() {
        // Pegadinha que custou o conserto: `falha(..., i)` gravaria no repo DENTRO do catch de um
        // repo.save que acabou de estourar -- a segunda excecao substituiria a mensagem util por um
        // erro de banco cru, que e exatamente o que o item 5.4 reclama. Por isso passa `null`.
        IntegracaoCanal i = loja("CONECTADO");
        i.tokenExpiraEm = OffsetDateTime.now().minusMinutes(1);
        when(repo.save(any())).thenThrow(new RuntimeException("conexao com o banco caiu"));

        ResponseStatusException erro = assertThrows(ResponseStatusException.class,
                () -> cliente.tokenValido(i));

        assertFalse(erro.getReason().contains("conexao com o banco"),
                "a mensagem crua do banco nao ajuda o lojista: " + erro.getReason());
        verify(repo, times(1)).save(any());
    }

    @Test
    void tokenRENOVADOeGRAVADOcomSUCESSO_naoAVISAnada() {
        IntegracaoCanal i = loja("CONECTADO");
        i.tokenExpiraEm = OffsetDateTime.now().minusMinutes(1);

        String tok = cliente.tokenValido(i);

        assertEquals("tok-novo", tok);
        assertEquals("CONECTADO", i.status, "deu tudo certo: nao pode cair em ERRO");
        assertEquals("ref-novo", i.refreshToken);
    }
}
