package br.com.bora.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static br.com.bora.service.AsaasClient.EstadoDaAssinatura.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * "A assinatura ainda existe no Asaas?" — contra um servidor HTTP de verdade.
 *
 * <h2>Por que isto precisa existir</h2>
 * <p>Estes dois ramos são as decisões de maior consequência do conserto do item 7, e eram os únicos
 * sem cobertura: o teste do serviço dubla o {@code AsaasClient}, então mutei a resposta e a suíte
 * passou <b>verde</b>. Medido em 20/10/2026:</p>
 * <ul>
 *   <li>fazer erro de rede devolver {@code NAO_EXISTE} em vez de {@code INDISPONIVEL} — o
 *       {@code assinar} passaria a cancelar e recriar cobrança <b>no escuro</b> a cada falha de rede,
 *       que é como a assinatura da Zirá se perdeu;</li>
 *   <li>fazer {@code deleted: true} contar como {@code VIVA} — a loja da Zirá, cuja assinatura está
 *       deletada no Asaas, seria <b>recusada</b> e ficaria sem nenhuma forma de voltar a pagar.</li>
 * </ul>
 *
 * <p>Mock de cliente HTTP devolve o que o teste mandar. O que está em teste aqui é como o nosso
 * código LÊ a resposta do Asaas — então o servidor é de verdade, na porta local.</p>
 */
class EstadoDaAssinaturaNoAsaasTest {

    private HttpServer servidor;
    private final AtomicInteger chamadas = new AtomicInteger();

    @BeforeEach
    void subir() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        chamadas.set(0);
    }

    @AfterEach
    void descer() {
        if (servidor != null) servidor.stop(0);
    }

    /** Responde `codigo` e `corpo` em qualquer caminho, e conta as chamadas. */
    private void responder(int codigo, String corpo) {
        servidor.createContext("/", troca -> {
            chamadas.incrementAndGet();
            byte[] b = corpo.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(codigo, b.length);
            try (OutputStream os = troca.getResponseBody()) { os.write(b); }
        });
        servidor.start();
    }

    private AsaasClient clienteApontandoParaOServidor() {
        return new AsaasClient("http://127.0.0.1:" + servidor.getAddress().getPort(), "$aact_chave");
    }

    // ------------------------------------------------------------------ existe

    @Test
    void assinaturaQueEXISTEeNAOestaDeletada_ehVIVA() {
        responder(200, "{\"id\":\"sub_1\",\"status\":\"ACTIVE\",\"deleted\":false}");

        assertEquals(VIVA, clienteApontandoParaOServidor().estadoDaAssinatura("sub_1"));
    }

    @Test
    void semOcampoDeleted_ehVIVA() {
        // O Asaas nem sempre manda o campo. Ausente significa "nao deletada".
        responder(200, "{\"id\":\"sub_1\",\"status\":\"ACTIVE\"}");

        assertEquals(VIVA, clienteApontandoParaOServidor().estadoDaAssinatura("sub_1"));
    }

    // ------------------------------------------------------------------ nao existe

    @Test
    void deletedTRUE_ehNAOEXISTE_eEsteEhOcasoDaZira() {
        // A assinatura da Zira esta assim: o Asaas responde 200 com deleted:true. Se isto contasse
        // como VIVA, o assinar recusaria por "fatura em aberto" e ela ficaria SEM NENHUMA forma de
        // voltar a pagar.
        responder(200, "{\"id\":\"sub_1\",\"status\":\"ACTIVE\",\"deleted\":true}");

        assertEquals(NAO_EXISTE, clienteApontandoParaOServidor().estadoDaAssinatura("sub_1"));
    }

    @Test
    void quatroZeroQuatro_ehNAOEXISTE() {
        responder(404, "{\"errors\":[{\"code\":\"invalid_action\",\"description\":\"nao encontrado\"}]}");

        assertEquals(NAO_EXISTE, clienteApontandoParaOServidor().estadoDaAssinatura("sub_1"));
    }

    @Test
    void idVazioOuNulo_ehNAOEXISTE_eNaoChamaOasaas() {
        responder(200, "{\"id\":\"sub_1\"}");
        AsaasClient c = clienteApontandoParaOServidor();

        assertEquals(NAO_EXISTE, c.estadoDaAssinatura(null));
        assertEquals(NAO_EXISTE, c.estadoDaAssinatura("  "));

        assertEquals(0, chamadas.get(), "nao ha o que consultar: nao gastar uma ida a rede");
    }

    // ------------------------------------------------------------------ nao sei

    @Test
    void erroDoServidor_ehINDISPONIVEL_eNAOehNAOEXISTE() {
        // A distincao que vale dinheiro. Tratar 500 como "nao existe" faria o assinar cancelar e
        // recriar cobranca a cada soluco do Asaas.
        responder(500, "{\"errors\":[{\"description\":\"interno\"}]}");

        assertEquals(INDISPONIVEL, clienteApontandoParaOServidor().estadoDaAssinatura("sub_1"));
    }

    @Test
    void chaveRecusada_ehINDISPONIVEL() {
        responder(401, "{\"errors\":[{\"description\":\"token invalido\"}]}");

        assertEquals(INDISPONIVEL, clienteApontandoParaOServidor().estadoDaAssinatura("sub_1"));
    }

    @Test
    void servidorNoAR_masRespostaIMPRESTAVEL_ehINDISPONIVEL() {
        responder(200, "isto nao e json");

        assertEquals(INDISPONIVEL, clienteApontandoParaOServidor().estadoDaAssinatura("sub_1"));
    }

    @Test
    void servidorFORAdoAr_ehINDISPONIVEL() throws IOException {
        int porta = servidor.getAddress().getPort();
        servidor.stop(0);   // ninguem atende nesta porta
        servidor = null;

        AsaasClient c = new AsaasClient("http://127.0.0.1:" + porta, "$aact_chave");

        assertEquals(INDISPONIVEL, c.estadoDaAssinatura("sub_1"));
    }

    @Test
    void semChaveConfigurada_ehINDISPONIVEL_eNAOehNAOEXISTE() {
        // Ambiente sem Asaas (um dev local) nao pode fazer o sistema concluir que a assinatura morreu
        // e sair recriando cobranca.
        AsaasClient semChave = new AsaasClient("http://127.0.0.1:1", "");

        assertEquals(INDISPONIVEL, semChave.estadoDaAssinatura("sub_1"));
    }

    // ------------------------------------------------------------------ a fatura em aberto

    @Test
    void faturaEmAberto_devolveOlinkDePagamento() {
        responder(200, "{\"data\":[{\"id\":\"pay_9\",\"value\":199.0,\"dueDate\":\"2026-10-10\","
                + "\"invoiceUrl\":\"https://asaas.com/i/pay_9\"}]}");

        var f = clienteApontandoParaOServidor().faturaAbertaDaAssinatura("sub_1");

        assertNotNull(f);
        assertEquals("pay_9", f.id());
        assertEquals("https://asaas.com/i/pay_9", f.link(), "e o link que o lojista usa para pagar");
        assertEquals("2026-10-10", f.vencimento());
        assertEquals(199.0, f.valor());
    }

    @Test
    void semFaturaAberta_devolveNulo() {
        responder(200, "{\"data\":[]}");

        assertNull(clienteApontandoParaOServidor().faturaAbertaDaAssinatura("sub_1"));
    }

    @Test
    void faturaSemLink_naoEstoura() {
        // Nem toda cobranca tem invoiceUrl. A tela so deixa de mostrar o botao.
        responder(200, "{\"data\":[{\"id\":\"pay_9\",\"value\":199.0}]}");

        var f = clienteApontandoParaOServidor().faturaAbertaDaAssinatura("sub_1");

        assertNotNull(f);
        assertNull(f.link());
    }

    @Test
    void faturaComErroNoAsaas_devolveNulo_eNAOestoura() {
        // Esta consulta so enfeita a tela; nao decide nada. Quem decide e o estadoDaAssinatura.
        responder(500, "{\"errors\":[{\"description\":\"interno\"}]}");

        assertNull(clienteApontandoParaOServidor().faturaAbertaDaAssinatura("sub_1"));
    }
}
