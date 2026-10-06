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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Depois de autorizar no iFood, o Bora descobre sozinho qual e a loja.
 *
 * <p>O vinculo terminava com o Merchant ID em branco, e o painel dizia "Conectado — os pedidos
 * chegam sozinhos". So que o polling sai na primeira linha quando esse id falta: nao busca nada, nao
 * da erro e nao escreve no log. O card ficava verde com a "ultima sync" em branco para sempre, e o
 * lojista esperaria pedido que nunca chega. Visto na tela em 06/10/2026, ligando uma loja de teste.</p>
 *
 * <p>Com varias lojas o Bora NAO adivinha. Vincular a loja errada e pior que perguntar: os pedidos de
 * outro estabelecimento cairiam no painel desta loja.</p>
 */
class MerchantIdDescobertoSozinhoTest {

    private HttpServer servidor;
    private String respostaDasLojas = "[]";
    private final List<String> chamadas = new ArrayList<>();
    private IfoodClient cliente;

    @BeforeEach
    void subirIfoodFalso() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            String caminho = troca.getRequestURI().getPath();
            chamadas.add(troca.getRequestMethod() + " " + caminho);
            String corpo = caminho.endsWith("/token")
                    ? "{\"accessToken\":\"tok\",\"refreshToken\":\"ref\",\"expiresIn\":21600}"
                    : respostaDasLojas;
            byte[] r = corpo.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, r.length);
            try (OutputStream o = troca.getResponseBody()) { o.write(r); }
        });
        servidor.start();
        String base = "http://127.0.0.1:" + servidor.getAddress().getPort();
        cliente = new IfoodClient(base, credenciais(), mock(IntegracaoCanalRepository.class), new MarketplaceHttp());
    }

    private CredenciaisMarketplace credenciais() {
        CredenciaisMarketplace c = mock(CredenciaisMarketplace.class);
        org.mockito.Mockito.when(c.clientId("IFOOD")).thenReturn("app-id");
        org.mockito.Mockito.when(c.clientSecret("IFOOD")).thenReturn("app-secret");
        return c;
    }

    @AfterEach
    void derrubar() {
        if (servidor != null) servidor.stop(0);
    }

    private IntegracaoCanal integracaoAutorizando() {
        IntegracaoCanal i = new IntegracaoCanal();
        i.lojaId = 17L;
        i.canal = "IFOOD";
        i.codeVerifier = "verificador-de-teste";
        return i;
    }

    @Test
    void umaLojaSo_preencheSozinho() {
        respostaDasLojas = "[{\"id\":\"a092eec4-f8e4-4300-b135-2073fb5ceed3\",\"name\":\"Pizzaria\"}]";

        cliente.concluirVinculo(integracaoAutorizando(), "WXTC-PWSW");

        assertTrue(chamadas.contains("GET /merchant/v1.0/merchants"),
                "o Bora tem que perguntar ao iFood quais lojas o token enxerga: " + chamadas);
    }

    @Test
    void umaLojaSo_oIdFicaGravado() {
        respostaDasLojas = "[{\"id\":\"a092eec4-f8e4-4300-b135-2073fb5ceed3\",\"name\":\"Pizzaria\"}]";
        IntegracaoCanal i = integracaoAutorizando();

        cliente.concluirVinculo(i, "WXTC-PWSW");

        assertEquals("a092eec4-f8e4-4300-b135-2073fb5ceed3", i.merchantId,
                "sem este id o polling nao busca pedido nenhum, calado");
        assertEquals("CONECTADO", i.status);
    }

    @Test
    void variasLojas_naoAdivinha() {
        respostaDasLojas = "[{\"id\":\"loja-a\"},{\"id\":\"loja-b\"}]";
        IntegracaoCanal i = integracaoAutorizando();

        cliente.concluirVinculo(i, "WXTC-PWSW");

        assertNull(i.merchantId,
                "com duas lojas, escolher errado joga pedido de outro estabelecimento neste painel");
        assertEquals("CONECTADO", i.status, "o vinculo vale: falta so dizer qual e a loja");
    }

    @Test
    void oLojistaJaTinhaInformado_naoSobrescreve() {
        respostaDasLojas = "[{\"id\":\"outra-loja\"}]";
        IntegracaoCanal i = integracaoAutorizando();
        i.merchantId = "a-que-o-lojista-digitou";

        cliente.concluirVinculo(i, "WXTC-PWSW");

        assertEquals("a-que-o-lojista-digitou", i.merchantId, "a escolha do lojista manda");
        assertFalse(chamadas.contains("GET /merchant/v1.0/merchants"),
                "nem precisa perguntar se o id ja esta la");
    }

    @Test
    void oIfoodForaDoAr_naoDerrubaOVinculo() {
        servidor.removeContext("/");
        servidor.createContext("/", troca -> {
            String caminho = troca.getRequestURI().getPath();
            if (caminho.endsWith("/token")) {
                byte[] r = "{\"accessToken\":\"tok\",\"refreshToken\":\"ref\",\"expiresIn\":21600}"
                        .getBytes(StandardCharsets.UTF_8);
                troca.getResponseHeaders().add("Content-Type", "application/json");
                troca.sendResponseHeaders(200, r.length);
                try (OutputStream o = troca.getResponseBody()) { o.write(r); }
            } else {
                troca.sendResponseHeaders(500, -1); // a consulta de lojas falha
                troca.close();
            }
        });
        IntegracaoCanal i = integracaoAutorizando();

        cliente.concluirVinculo(i, "WXTC-PWSW");

        assertEquals("CONECTADO", i.status,
                "o token ja e valido; perder o vinculo por causa da consulta de lojas seria pior");
        assertNull(i.merchantId);
    }
}
