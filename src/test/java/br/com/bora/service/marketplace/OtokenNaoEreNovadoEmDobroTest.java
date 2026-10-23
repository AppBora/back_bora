package br.com.bora.service.marketplace;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.repository.IntegracaoCanalRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Duas instâncias não renovam o token do iFood ao mesmo tempo.
 *
 * <h2>O defeito que isto fecha (item 9.11)</h2>
 * <p>A trava que serializava a renovação era {@code synchronized} num objeto da JVM. Trava de JVM não
 * atravessa processo — e o {@code application.yml} planeja uma segunda instância.</p>
 *
 * <p>No iFood isso é grave de um jeito específico: ele <b>invalida o refresh token anterior</b> quando
 * emite o novo. Duas instâncias renovando ao mesmo tempo emitem dois tokens; o que gravar por último
 * vence, e o outro já matou o primeiro — a loja precisa <b>reautorizar do zero</b>. E não é uma loja:
 * todas renovam no mesmo ciclo de polling, então o estrago é simultâneo em todas, silencioso, e só
 * aparece quando os pedidos param de chegar.</p>
 *
 * <p><b>O que medir corrigiu no diagnóstico:</b> na 99 (Open Delivery) o risco <b>não existe</b> — lá
 * o token sai de {@code client_credentials}, que não invalida o anterior, e o cliente nem guarda
 * refresh token. Por isso o arrendamento está só no iFood, e não nos dois.</p>
 *
 * <p>É <b>arrendamento</b> (lease) e não {@code select for update} de propósito: a renovação fala com
 * o iFood, e segurar lock de banco durante chamada HTTP é exatamente o defeito dos itens 9.3, 9.4 e
 * 9.5.</p>
 */
class OtokenNaoEreNovadoEmDobroTest {

    private HttpServer servidor;
    private IntegracaoCanalRepository repo;
    private IfoodClient cliente;
    private final AtomicInteger renovacoesNoIfood = new AtomicInteger();

    @BeforeEach
    void subirIfoodFalso() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            if (troca.getRequestURI().getPath().endsWith("/token")) renovacoesNoIfood.incrementAndGet();
            byte[] r = "{\"accessToken\":\"tok-novo\",\"refreshToken\":\"ref-novo\",\"expiresIn\":21600}"
                    .getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, r.length);
            try (OutputStream o = troca.getResponseBody()) { o.write(r); }
        });
        servidor.start();

        repo = mock(IntegracaoCanalRepository.class);
        CredenciaisMarketplace cred = mock(CredenciaisMarketplace.class);
        when(cred.clientId("IFOOD")).thenReturn("app-id");
        when(cred.clientSecret("IFOOD")).thenReturn("app-secret");
        cliente = new IfoodClient("http://127.0.0.1:" + servidor.getAddress().getPort(),
                cred, repo, new MarketplaceHttp());
        renovacoesNoIfood.set(0);
    }

    @AfterEach
    void derrubar() {
        if (servidor != null) servidor.stop(0);
    }

    /** Integração com token vencido: toda chamada aqui tenta renovar. */
    private IntegracaoCanal comTokenVencido() {
        IntegracaoCanal i = new IntegracaoCanal();
        i.id = 7L;
        i.lojaId = 18L;
        i.canal = "IFOOD";
        i.ativo = true;
        i.status = "CONECTADO";
        i.merchantId = "merchant";
        i.accessToken = "tok-velho";
        i.refreshToken = "ref-velho";
        i.tokenExpiraEm = OffsetDateTime.now().minusMinutes(1);
        return i;
    }

    private void aVezEhMinha() {
        when(repo.tentarPegarAvezDeRenovar(anyLong(), anyLong(), any(), any())).thenReturn(1);
    }

    private void aVezEhDeOutroProcesso() {
        when(repo.tentarPegarAvezDeRenovar(anyLong(), anyLong(), any(), any())).thenReturn(0);
    }

    // =============================================================== com a vez

    @Test
    void comAvez_renovaEdevolveAvezDepois() {
        aVezEhMinha();
        IntegracaoCanal i = comTokenVencido();

        assertEquals("tok-novo", cliente.tokenValido(i));

        assertEquals(1, renovacoesNoIfood.get(), "tinha que renovar uma vez");
        verify(repo).devolverAvezDeRenovar(7L, 18L);
    }

    @Test
    void aVezEpedidaComOarrendamentoVENCIDO_eNAOcomAgoraSO() {
        // Se o parametro de vencimento fosse `agora`, a condicao `tokenRenovandoEm < agora` seria
        // verdadeira para qualquer marca ja gravada -- e o arrendamento nao travaria nada.
        aVezEhMinha();

        cliente.tokenValido(comTokenVencido());

        var agora = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        var venceu = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repo).tentarPegarAvezDeRenovar(eq(7L), eq(18L), agora.capture(), venceu.capture());
        assertTrue(venceu.getValue().isBefore(agora.getValue().minusMinutes(1)),
                "o corte tem que ser no passado, senao a marca de qualquer um conta como vencida");
    }

    @Test
    void aVezEdevolvidaMESMOquandoARENOVACAOfalha() {
        // Sem o finally, uma falha deixaria a marca gravada e NENHUM processo renovaria por 2
        // minutos -- a loja sairia do ar no iFood esperando um arrendamento vencer.
        aVezEhMinha();
        IntegracaoCanal i = comTokenVencido();
        i.refreshToken = null;   // renovar recusa de cara

        assertThrows(ResponseStatusException.class, () -> cliente.tokenValido(i));

        // A vez E pedida (ela e pedida ANTES de renovar, que e onde a recusa acontece) -- e o que
        // importa e que o `finally` a devolve. Eu tinha escrito o contrario neste teste, e o mock me
        // corrigiu: a ordem real das chamadas nao era a que eu imaginei.
        verify(repo).tentarPegarAvezDeRenovar(eq(7L), eq(18L), any(), any());
        verify(repo).devolverAvezDeRenovar(7L, 18L);
        assertEquals(0, renovacoesNoIfood.get(), "e nao falou com o iFood");
    }

    // =============================================================== sem a vez

    @Test
    @Timeout(20)
    void semAvez_naoRENOVA_eUSAoQUEoOUTROgravou() {
        // O caso que o item 9.11 descreve: outro processo esta renovando. Renovar em paralelo mataria
        // o token dele.
        aVezEhDeOutroProcesso();
        IntegracaoCanal i = comTokenVencido();

        // o outro processo termina enquanto este espera
        IntegracaoCanal jaRenovada = comTokenVencido();
        jaRenovada.accessToken = "tok-do-outro";
        jaRenovada.tokenExpiraEm = OffsetDateTime.now().plusHours(5);
        when(repo.findById(7L)).thenReturn(Optional.of(jaRenovada));

        assertEquals("tok-do-outro", cliente.tokenValido(i),
                "tem que usar o token que o outro processo gravou");
        assertEquals(0, renovacoesNoIfood.get(),
                "NAO pode ter falado com o iFood: isso mataria o token do outro");
        verify(repo, never()).devolverAvezDeRenovar(anyLong(), anyLong());
    }

    @Test
    @Timeout(20)
    void semAvez_eOoutroNAOterminou_falhaESTAchamadaEmVEZdeRENOVARemPARALELO() {
        // Falhar esta chamada custa um ciclo de polling (30 s). Renovar em paralelo custa a
        // reautorizacao de TODAS as lojas. A escolha e clara.
        aVezEhDeOutroProcesso();
        when(repo.findById(7L)).thenReturn(Optional.of(comTokenVencido()));   // continua vencido

        ResponseStatusException erro = assertThrows(ResponseStatusException.class,
                () -> cliente.tokenValido(comTokenVencido()));

        assertEquals(503, erro.getStatusCode().value());
        assertEquals(0, renovacoesNoIfood.get());
    }

    // =============================================================== o que nao muda

    @Test
    void tokenVIGENTEnaoPEDEvezNENHUMA() {
        // O caminho normal: 99,9% das chamadas. Pedir a vez aqui seria um UPDATE por requisicao.
        IntegracaoCanal i = comTokenVencido();
        i.tokenExpiraEm = OffsetDateTime.now().plusHours(5);

        assertEquals("tok-velho", cliente.tokenValido(i));

        verify(repo, never()).tentarPegarAvezDeRenovar(anyLong(), anyLong(), any(), any());
        verifyNoMoreInteractions(repo);
    }

    @Test
    void integracaoAINDAnaoGRAVADAnaoPEDEvez() {
        // Sem id nao ha linha a arrendar. Pedir a vez com id nulo estouraria no banco; e o caso
        // acontece no fluxo de vinculo, antes do primeiro save.
        aVezEhMinha();
        IntegracaoCanal i = comTokenVencido();
        i.id = null;

        assertEquals("tok-novo", cliente.tokenValido(i));

        verify(repo, never()).tentarPegarAvezDeRenovar(any(), any(), any(), any());
    }
}
