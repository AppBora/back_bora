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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Pedido de retirada da 99Food fecha pelo endpoint certo.
 *
 * <p>No Open Delivery um pedido de retirada vem com {@code type = TAKEOUT}, sem o objeto
 * {@code delivery}. Ele nao tem despacho — ninguem sai para entregar — e NAO fecha pelo
 * {@code delivered} dos pedidos normais: a especificacao so aceita {@code pickedUp}, que significa
 * "o cliente veio buscar". Mandar o verbo errado faz a 99 recusar, e o pedido fica eternamente aberto
 * no lado deles enquanto o lojista ja entregou a comida.</p>
 *
 * <p>O comportamento existia desde 22/09 mas nao tinha teste nenhum. O e-mail do time de engenharia da
 * 99Food em 05/10/2026 confirmou o endereco exato
 * ({@code /v4/opendelivery/v1/orders/{orderId}/pickedUp}), entao este teste checa a URL de verdade,
 * subindo um servidor no lugar da 99 e vendo o que o Bora chama.</p>
 */
class RetiradaNaNoveNoveTest {

    private HttpServer servidor;
    private final List<String> chamadas = new ArrayList<>();
    private String corpoDoPedido;
    private OpenDeliveryClient cliente;

    @BeforeEach
    void subirAFalsa99() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            String caminho = troca.getRequestURI().getPath();
            chamadas.add(troca.getRequestMethod() + " " + caminho);
            byte[] resp = ("GET".equals(troca.getRequestMethod()) ? corpoDoPedido : "{}")
                    .getBytes(StandardCharsets.UTF_8);
            // Sem o Content-Type, o RestClient nao converte a resposta em Map, detalhePedido engole a
            // excecao e devolve vazio — e todo pedido pareceria "entrega normal". Foi o que aconteceu
            // na primeira versao deste teste, e quase virou um falso alarme de regressao.
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, resp.length);
            try (OutputStream o = troca.getResponseBody()) { o.write(resp); }
        });
        servidor.start();
        String base = "http://127.0.0.1:" + servidor.getAddress().getPort() + "/v4/opendelivery";
        cliente = new OpenDeliveryClient(base, mock(CredenciaisMarketplace.class),
                mock(IntegracaoCanalRepository.class), new MarketplaceHttp());
    }

    @AfterEach
    void derrubar() {
        if (servidor != null) servidor.stop(0);
    }

    /** Integracao com token ainda valido, para o teste nao depender do /oauth/token. */
    private IntegracaoCanal integracao() {
        IntegracaoCanal i = new IntegracaoCanal();
        i.lojaId = 18L;
        i.canal = "NOVE_NOVE";
        i.accessToken = "token-de-teste";
        i.tokenExpiraEm = OffsetDateTime.now().plusHours(1);
        return i;
    }

    private List<String> posts() {
        return chamadas.stream().filter(c -> c.startsWith("POST ")).toList();
    }

    @Test
    void retiradaEntregue_fechaNoPickedUp_eNaoNoDelivered() {
        corpoDoPedido = "{\"id\":\"ped-1\",\"type\":\"TAKEOUT\",\"takeout\":{\"mode\":\"PICKUP_AREA\"}}";

        cliente.enviarStatus(integracao(), "ped-1", "ENTREGUE");

        assertEquals(List.of("POST /v4/opendelivery/v1/orders/ped-1/pickedUp"), posts(),
                "retirada fecha em pickedUp, no endereco que a 99 publicou; delivered seria recusado");
    }

    @Test
    void retiradaNaoDespacha_porqueNinguemSaiParaEntregar() {
        corpoDoPedido = "{\"id\":\"ped-2\",\"type\":\"TAKEOUT\"}";

        cliente.enviarStatus(integracao(), "ped-2", "SAIU_PARA_ENTREGA");

        assertEquals(List.of(), posts(),
                "pedido de retirada nao tem despacho: mandar dispatch e erro na 99");
    }

    @Test
    void entregaPelaPropriaLoja_continuaFechandoNoDelivered() {
        corpoDoPedido = "{\"id\":\"ped-3\",\"type\":\"DELIVERY\",\"delivery\":{\"deliveredBy\":\"MERCHANT\"}}";

        cliente.enviarStatus(integracao(), "ped-3", "ENTREGUE");

        assertEquals(List.of("POST /v4/opendelivery/v1/orders/ped-3/delivered"), posts(),
                "pedido normal da loja nao pode virar pickedUp");
    }

    @Test
    void entregaPelaPropria99_naoRecebeFechamentoNenhum() {
        corpoDoPedido = "{\"id\":\"ped-4\",\"type\":\"DELIVERY\",\"delivery\":{\"deliveredBy\":\"MARKETPLACE\"}}";

        cliente.enviarStatus(integracao(), "ped-4", "ENTREGUE");

        assertEquals(List.of(), posts(),
                "quem entrega e a 99; quem fecha o pedido e ela, nao a loja");
    }

    @Test
    void prontoParaRetirar_avisaAMesmaCoisaDeSempre() {
        corpoDoPedido = "{\"id\":\"ped-5\",\"type\":\"TAKEOUT\"}";

        cliente.enviarStatus(integracao(), "ped-5", "PRONTO");

        assertEquals(List.of("POST /v4/opendelivery/v1/orders/ped-5/readyForPickup"), posts(),
                "o aviso de pronto vale para retirada tambem — e o que chama o cliente ao balcao");
    }
}
