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
 * Dois itens da lista de pre-producao da 99 (Before Going Live, documentacao deles, lida em 06/10/2026).
 *
 * <p><b>validateCode</b> confere o codigo que o cliente mostra na entrega ou na retirada. Quem sabe se
 * esta certo e a 99: o cliente recebe o codigo no aplicativo e o entregador digita. A especificacao
 * avisa que este caminho e SINCRONO — nao gera evento no polling nem no webhook —, entao a resposta
 * precisa voltar para a tela na hora, e codigo errado nao pode virar erro de sistema.</p>
 *
 * <p><b>merchantUpdate</b> avisa que a loja saiu do ar. A documentacao e explicita: so vale para
 * fechamento inesperado, sem data para voltar; nao serve para feriado nem para o fecha-e-abre do dia
 * a dia. A suspensao pela plataforma e esse caso. Sem o aviso, a 99 continua mandando pedido que
 * ninguem vai preparar, e quem espera comida que nao vem e o cliente final.</p>
 */
class HomologacaoDaNoveNoveTest {

    private HttpServer servidor;
    private final List<String> chamadas = new ArrayList<>();
    private final List<String> corpos = new ArrayList<>();
    private int respostaDoCodigo = 200;
    private OpenDeliveryClient cliente;

    @BeforeEach
    void subirAFalsa99() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            String completo = troca.getRequestURI().getPath()
                    + (troca.getRequestURI().getQuery() == null ? "" : "?" + troca.getRequestURI().getQuery());
            chamadas.add(troca.getRequestMethod() + " " + completo);
            corpos.add(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int codigo = completo.contains("validateCode") ? respostaDoCodigo : 200;
            // O polling devolve LISTA de eventos; responder "{}" aqui fazia a conversao estourar e o
            // teste acusava um defeito que nao existia.
            String corpo = completo.contains("events:polling") ? "[]" : "{}";
            byte[] r = corpo.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(codigo, r.length);
            try (OutputStream o = troca.getResponseBody()) { o.write(r); }
        });
        servidor.start();
        String base = "http://127.0.0.1:" + servidor.getAddress().getPort() + "/v4/opendelivery";
        cliente = new OpenDeliveryClient(base, mock(CredenciaisMarketplace.class),
                mock(IntegracaoCanalRepository.class), new MarketplaceHttp());
    }

    @AfterEach
    void derrubar() { if (servidor != null) servidor.stop(0); }

    private IntegracaoCanal integracao() {
        IntegracaoCanal i = new IntegracaoCanal();
        i.lojaId = 18L;
        i.canal = "NOVE_NOVE";
        i.merchantId = "boraloja2";
        i.status = "CONECTADO";
        i.accessToken = "token-de-teste";
        i.tokenExpiraEm = OffsetDateTime.now().plusHours(1);
        return i;
    }

    @Test
    void codigoCerto_vaiNoEnderecoDaEspecificacao() {
        assertTrue(cliente.validarCodigoDeEntrega(integracao(), "ped-1", "4821"));
        assertEquals(List.of("POST /v4/opendelivery/v1/orders/ped-1/validateCode?deliveryCode=4821"),
                chamadas, "o codigo vai na query, no endereco que a 99 publicou");
    }

    @Test
    void codigoErrado_eRespostaDeNegocio_naoErroDeSistema() {
        respostaDoCodigo = 400;

        assertFalse(cliente.validarCodigoDeEntrega(integracao(), "ped-2", "0000"),
                "codigo errado e 'nao confere', e a tela precisa dizer isso ao entregador");
    }

    @Test
    void codigoEmBranco_nemChegaNa99() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> cliente.validarCodigoDeEntrega(integracao(), "ped-3", "   "));
        assertTrue(chamadas.isEmpty(), "nao gasta chamada para conferir o que o entregador nem digitou");
    }

    @Test
    void lojaSuspensa_avisaAoNoveNoveQueFechou() {
        cliente.avisarQueFechou(integracao(), false);

        assertEquals(List.of("POST /v4/opendelivery/v1/merchantUpdate"), chamadas);
        assertTrue(corpos.get(0).contains("UNAVAILABLE"),
                "sem isto a 99 segue mandando pedido para loja que nao vai preparar: " + corpos);
    }

    @Test
    void lojaReativada_avisaQueVoltou() {
        cliente.avisarQueFechou(integracao(), true);

        assertTrue(corpos.get(0).contains("AVAILABLE") && !corpos.get(0).contains("UNAVAILABLE"),
                "voltar tambem precisa ser avisado, senao a loja fica fora do ar por engano: " + corpos);
    }

    @Test
    void lojaQueNemEstaConectada_naoGastaChamada() {
        IntegracaoCanal solta = new IntegracaoCanal();
        solta.lojaId = 99L;
        solta.canal = "NOVE_NOVE"; // sem merchantId e sem status CONECTADO

        cliente.avisarQueFechou(solta, false);

        assertTrue(chamadas.isEmpty());
    }

    @Test
    void pollingQueVoltaAFuncionar_tiraALojaDoErroSozinho() {
        IntegracaoCanal i = integracao();
        i.status = "ERRO";
        i.ultimoErro = "A 99Food recusou a credencial";

        cliente.polling(i);

        assertEquals("CONECTADO", i.status,
                "deu certo de novo: a integracao se levanta sozinha, sem ninguem clicar em Conectar");
        assertNull(i.ultimoErro, "o erro antigo nao pode ficar na tela depois de resolvido");
    }
}
