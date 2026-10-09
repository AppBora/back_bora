package br.com.bora.controller;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Loja;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.service.WhatsAppSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O robo de WhatsApp responde o que promete, e so para quem a Meta mandou.
 *
 * <p>Ele e a primeira coisa que o cliente final da loja toca: manda "oi" no numero da acaiteria e
 * quem responde e este codigo. Um erro aqui nao aparece em log de erro — aparece como cliente sem
 * resposta, ou como a loja mandando mensagem para quem ela nunca falou.</p>
 *
 * <p>Os testes passam pelo {@code receber()} de verdade, com corpo cru e assinatura, e olham <b>o
 * texto que sai</b>. Isso so e possivel porque o envio e o {@link WhatsAppSender} injetado: a
 * versao anterior tinha uma copia privada que batia direto em {@code graph.facebook.com}.</p>
 */
class RoboDoWhatsAppTest {

    private static final long LOJA = 18L;
    private static final String CLIENTE = "5515998887777";
    private static final String LINK = "https://borahapp.com.br/cardapio.html?loja=18";
    private static final String APP_SECRET = "segredo-do-aplicativo-meta-para-teste";

    private IntegracaoCanalRepository integracoes;
    private WhatsAppSender envio;
    private LojaRepository lojas;
    private WhatsAppController controller;
    private IntegracaoCanal zap;

    @BeforeEach
    void montar() {
        integracoes = mock(IntegracaoCanalRepository.class);
        envio = mock(WhatsAppSender.class);
        lojas = mock(LojaRepository.class);

        zap = new IntegracaoCanal();
        zap.lojaId = LOJA;
        zap.canal = "WHATSAPP";
        zap.ativo = true;
        zap.clientId = "123456789";          // Phone Number ID
        zap.clientSecret = "token-da-meta";
        zap.webhookToken = "segredo-do-webhook";
        when(integracoes.findByLojaIdAndCanal(LOJA, "WHATSAPP")).thenReturn(Optional.of(zap));

        Loja loja = new Loja();
        loja.nome = "Açaí Zirá - Montreal";
        when(lojas.findById(LOJA)).thenReturn(Optional.of(loja));

        controller = new WhatsAppController(integracoes, lojas, envio, APP_SECRET);
    }

    // ---------------------------------------------------------------- o envelope da Meta

    /** O corpo cru, como a Meta manda: o robo assina o texto, nao um Map remontado. */
    private String corpoCom(String texto) {
        return "{\"entry\":[{\"changes\":[{\"value\":{\"messages\":[{"
                + "\"from\":\"" + CLIENTE + "\",\"type\":\"text\",\"text\":{\"body\":\"" + texto + "\"}}]}}]}]}";
    }

    private static String assinar(String corpo, String segredo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder hex = new StringBuilder("sha256=");
            for (byte b : mac.doFinal(corpo.getBytes(StandardCharsets.UTF_8))) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private Map<String, String> entregar(String corpo) {
        return controller.receber(LOJA, corpo, assinar(corpo, APP_SECRET));
    }

    private String respostaPara(String texto) {
        entregar(corpoCom(texto));
        ArgumentCaptor<String> saiu = ArgumentCaptor.forClass(String.class);
        verify(envio).enviar(eq(zap), eq(CLIENTE), saiu.capture());
        return saiu.getValue();
    }

    // ---------------------------------------------------------------- quem pode falar com o robo

    @Test
    void semAssinaturaDaMeta_oRoboFicaCalado() {
        // Sem isto, qualquer um fazia POST aqui com o "from" que quisesse e o robo respondia para
        // esse numero, com o token da loja: relay de spam saindo do numero comercial dela.
        assertEquals("ignored", controller.receber(LOJA, corpoCom("oi"), null).get("status"));
        verify(envio, never()).enviar(any(), any(), any());
    }

    @Test
    void assinaturaDeOutroSegredo_oRoboFicaCalado() {
        String corpo = corpoCom("oi");

        assertEquals("ignored", controller.receber(LOJA, corpo, assinar(corpo, "segredo-do-atacante")).get("status"));
        verify(envio, never()).enviar(any(), any(), any());
    }

    @Test
    void assinaturaDeOutroCorpo_oRoboFicaCalado() {
        // Corpo trocado depois de assinado: a assinatura tem que cobrir o conteudo, nao a rota.
        assertEquals("ignored",
                controller.receber(LOJA, corpoCom("oi"), assinar(corpoCom("outra coisa"), APP_SECRET)).get("status"));
        verify(envio, never()).enviar(any(), any(), any());
    }

    @Test
    void semAppSecretConfigurado_oRoboFicaCalado_emVezDeAbrirAPorta() {
        var semSegredo = new WhatsAppController(integracoes, lojas, envio, "");
        String corpo = corpoCom("oi");

        assertEquals("ignored", semSegredo.receber(LOJA, corpo, assinar(corpo, APP_SECRET)).get("status"));
        verify(envio, never()).enviar(any(), any(), any());
    }

    // ---------------------------------------------------------------- o menu

    @Test
    void mensagemQualquer_abreOMenuComAsTresOpcoes() {
        String r = respostaPara("oi");

        assertTrue(r.contains("Açaí Zirá - Montreal"), "o robo se apresenta com o nome da loja");
        assertTrue(r.contains("*1*") && r.contains("*2*") && r.contains("*3*"), "as tres opcoes: " + r);
        assertTrue(r.contains(LINK), "o menu ja oferece o link, para quem nao quer navegar");
    }

    @Test
    void lojaSemNomeCadastrado_naoMandaNullParaOCliente() {
        LojaRepository vazio = mock(LojaRepository.class);
        when(vazio.findById(LOJA)).thenReturn(Optional.empty());
        String corpo = corpoCom("oi");
        new WhatsAppController(integracoes, vazio, envio, APP_SECRET)
                .receber(LOJA, corpo, assinar(corpo, APP_SECRET));

        ArgumentCaptor<String> saiu = ArgumentCaptor.forClass(String.class);
        verify(envio).enviar(any(), any(), saiu.capture());
        assertFalse(saiu.getValue().contains("null"), "cliente nao pode receber 'null': " + saiu.getValue());
        assertTrue(saiu.getValue().contains("nossa loja"));
    }

    // ---------------------------------------------------------------- opcao 1: cardapio

    @Test
    void opcaoUm_mandaOLinkDoCardapioDaLojaCerta() {
        assertTrue(respostaPara("1").contains(LINK));
    }

    @Test
    void pedirCardapioPorExtenso_vaiParaOMesmoLugar() {
        for (String texto : List.of("cardapio", "quero fazer um pedido", "quero pedir", "CARDAPIO POR FAVOR")) {
            clearInvocations(envio);
            assertTrue(respostaPara(texto).contains(LINK), "deveria mandar o cardapio para: " + texto);
        }
    }

    // ---------------------------------------------------------------- opcao 2: horario

    @Test
    void opcaoDois_naoInventaHorario_mandaConferirNoCardapio() {
        String r = respostaPara("2");

        assertTrue(r.contains(LINK), "o horario de verdade esta no cardapio, nao no robo");
        // O robo nao tem acesso ao horario da loja. Se um dia escrever um horario fixo aqui, a loja
        // passa a prometer um horario que nao e o dela.
        assertFalse(r.matches("(?s).*\\b\\d{1,2}\\s*[h:]\\s*\\d{0,2}\\b.*"), "nao pode cravar horario: " + r);
        assertFalse(r.toLowerCase().contains("24 horas"), "nem prometer 24h: " + r);
    }

    @Test
    void perguntarSeEstaAberto_caiNoHorario() {
        for (String texto : List.of("horario", "ta aberto?", "voces funciona hoje?")) {
            clearInvocations(envio);
            assertTrue(respostaPara(texto).contains(LINK), "deveria responder horario para: " + texto);
        }
    }

    // ---------------------------------------------------------------- opcao 3: gente

    @Test
    void opcaoTres_naoPrometePrazoQueNinguemCumpre() {
        // Ninguem na loja e avisado por sistema nenhum: nao ha chamado, sino nem tela. O que e
        // verdade e que a mensagem esta no WhatsApp da loja e alguem le quando puder.
        String r = respostaPara("3");

        assertFalse(r.contains("instantes"), "prometer prazo sem ter como cumprir: " + r);
        assertTrue(r.contains("assim que puder"), r);
    }

    @Test
    void reclamacaoDePedido_vaiParaGente_naoParaOCardapio() {
        // "pedido" era testado antes de "atendente": a mensagem que mais precisa de uma pessoa
        // recebia o link do cardapio.
        for (String texto : List.of("meu pedido nao chegou", "quero falar sobre meu pedido",
                "veio errado", "ta muito atrasado", "quero reclamar")) {
            clearInvocations(envio);
            String r = respostaPara(texto);
            assertFalse(r.contains(LINK), "não é hora de mandar cardápio para: " + texto);
            assertTrue(r.contains("alguém da loja"), texto + " -> " + r);
        }
    }

    // ---------------------------------------------------------------- quando nao deve responder

    @Test
    void integracaoDesligada_oRoboFicaCalado() {
        zap.ativo = false;

        assertEquals("ignored", entregar(corpoCom("oi")).get("status"));
        verify(envio, never()).enviar(any(), any(), any());
    }

    @Test
    void lojaSemTokenConfigurado_oRoboFicaCalado() {
        zap.clientSecret = null;

        assertEquals("ignored", entregar(corpoCom("oi")).get("status"));
        verify(envio, never()).enviar(any(), any(), any());
    }

    @Test
    void avisoDeEntrega_naoEMensagemDeCliente_naoResponde() {
        // A Meta manda "statuses" (entregue, lido) no mesmo webhook. Responder isso seria o robo
        // conversando com o proprio recibo.
        String corpo = "{\"entry\":[{\"changes\":[{\"value\":{\"statuses\":[{\"status\":\"delivered\"}]}}]}]}";

        assertEquals("ok", entregar(corpo).get("status"));
        verify(envio, never()).enviar(any(), any(), any());
    }

    @Test
    void corpoQuebrado_respondeOkParaAMetaNaoReenviar() {
        // Qualquer 4xx/5xx faz a Meta repetir o mesmo aviso por horas.
        assertEquals("ok", entregar("{\"entry\":\"isto nao e uma lista\"}").get("status"));
        verify(envio, never()).enviar(any(), any(), any());
    }

    @Test
    void mensagemDeAudio_naoQuebra_eCaiNoMenu() {
        String corpo = "{\"entry\":[{\"changes\":[{\"value\":{\"messages\":[{"
                + "\"from\":\"" + CLIENTE + "\",\"type\":\"audio\",\"audio\":{\"id\":\"abc\"}}]}}]}]}";

        assertEquals("ok", entregar(corpo).get("status"));
        ArgumentCaptor<String> saiu = ArgumentCaptor.forClass(String.class);
        verify(envio).enviar(any(), eq(CLIENTE), saiu.capture());
        assertTrue(saiu.getValue().contains("*1*"), "audio sem texto deve cair no menu: " + saiu.getValue());
    }

    // ---------------------------------------------------------------- verificacao do webhook

    @Test
    void metaVerificaOWebhook_devolveODesafio() {
        assertEquals("desafio-123",
                controller.verificar(LOJA, "subscribe", "segredo-do-webhook", "desafio-123"));
    }

    @Test
    void verifyTokenErrado_naoDevolveDesafio() {
        var e = assertThrows(ResponseStatusException.class,
                () -> controller.verificar(LOJA, "subscribe", "chute", "desafio-123"));

        assertEquals(403, e.getStatusCode().value());
    }

    @Test
    void verifyTokenAusente_naoDevolveDesafio() {
        assertThrows(ResponseStatusException.class,
                () -> controller.verificar(LOJA, "subscribe", null, "desafio-123"));
    }
}
