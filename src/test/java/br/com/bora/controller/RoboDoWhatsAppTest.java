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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O robo de WhatsApp responde o que promete.
 *
 * <p>Ate aqui o robo nao tinha teste nenhum, e ele e a primeira coisa que o cliente final da loja
 * toca: manda "oi" no numero da acaiteria e quem responde e este codigo. Um erro aqui nao aparece em
 * log de erro — aparece como cliente sem resposta.</p>
 *
 * <p>Estes testes passam pelo {@code receber()} de verdade e olham <b>o texto que sai</b>, porque o
 * que importa para o cliente e a mensagem, nao o caminho interno. Isso so e possivel porque o envio
 * agora e o {@link WhatsAppSender} injetado: antes o controller tinha uma copia privada que batia
 * direto em {@code graph.facebook.com}, e qualquer teste viraria chamada de rede de verdade.</p>
 */
class RoboDoWhatsAppTest {

    private IntegracaoCanalRepository integracoes;
    private WhatsAppSender envio;
    private WhatsAppController controller;
    private IntegracaoCanal zap;

    private static final long LOJA = 18L;
    private static final String CLIENTE = "5515998887777";
    private static final String LINK = "https://borahapp.com.br/cardapio.html?loja=18";

    @BeforeEach
    void montar() {
        integracoes = mock(IntegracaoCanalRepository.class);
        envio = mock(WhatsAppSender.class);
        LojaRepository lojas = mock(LojaRepository.class);

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

        controller = new WhatsAppController(integracoes, lojas, envio);
    }

    /** O envelope que a Meta manda de verdade, com uma mensagem de texto dentro. */
    private Map<String, Object> mensagem(String texto) {
        return Map.of("entry", List.of(Map.of("changes", List.of(Map.of("value", Map.of(
                "messages", List.of(Map.of(
                        "from", CLIENTE,
                        "type", "text",
                        "text", Map.of("body", texto)))))))));
    }

    private String respostaPara(String texto) {
        controller.receber(LOJA, mensagem(texto));
        ArgumentCaptor<String> saiu = ArgumentCaptor.forClass(String.class);
        verify(envio).enviar(eq(zap), eq(CLIENTE), saiu.capture());
        return saiu.getValue();
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
        new WhatsAppController(integracoes, vazio, envio).receber(LOJA, mensagem("oi"));

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
        for (String texto : List.of("cardapio", "cardápio", "quero fazer um pedido", "quero pedir",
                "CARDAPIO POR FAVOR", "  Cardápio  ")) {
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
        // passa a prometer um horario que nao e o dela — por isso o teste trava o comportamento atual.
        assertFalse(r.matches("(?s).*\\b\\d{1,2}h\\d{0,2}\\b.*"), "nao pode cravar horario: " + r);
    }

    @Test
    void perguntarSeEstaAberto_caiNoHorario() {
        for (String texto : List.of("horario", "horário", "ta aberto?", "voces funciona hoje?")) {
            clearInvocations(envio);
            assertTrue(respostaPara(texto).contains("🕒"), "deveria responder horario para: " + texto);
        }
    }

    // ---------------------------------------------------------------- opcao 3: atendente

    @Test
    void opcaoTres_prometeAtendenteHumano() {
        assertTrue(respostaPara("3").contains("atendente humano"));
    }

    /**
     * Este teste existe para registrar uma promessa que o sistema NAO cumpre: o robo diz que um
     * humano vai responder, e ninguem na loja e avisado — nao abre chamado, nao toca sino, nao
     * aparece em tela nenhuma. Quem escolhe a opcao 3 fica esperando. Enquanto for assim, que pelo
     * menos esteja escrito aqui.
     */
    @Test
    void opcaoTres_naoAvisaNinguemNaLoja_promessaEmAberto() {
        controller.receber(LOJA, mensagem("3"));

        verify(envio, times(1)).enviar(any(), any(), any()); // so a resposta ao cliente
        verifyNoMoreInteractions(envio);                      // nada vai para a loja
    }

    // ---------------------------------------------------------------- quando nao deve responder

    @Test
    void integracaoDesligada_oRoboFicaCalado() {
        zap.ativo = false;

        assertEquals("ignored", controller.receber(LOJA, mensagem("oi")).get("status"));
        verify(envio, never()).enviar(any(), any(), any());
    }

    @Test
    void lojaSemTokenConfigurado_oRoboFicaCalado() {
        zap.clientSecret = null;

        assertEquals("ignored", controller.receber(LOJA, mensagem("oi")).get("status"));
        verify(envio, never()).enviar(any(), any(), any());
    }

    @Test
    void avisoDeEntrega_naoEMensagemDeCliente_naoResponde() {
        // A Meta manda "statuses" (entregue, lido) no mesmo webhook. Responder isso seria o robo
        // conversando com o proprio recibo.
        Map<String, Object> statuses = Map.of("entry", List.of(Map.of("changes", List.of(Map.of(
                "value", Map.of("statuses", List.of(Map.of("status", "delivered"))))))));

        assertEquals("ok", controller.receber(LOJA, statuses).get("status"));
        verify(envio, never()).enviar(any(), any(), any());
    }

    @Test
    void corpoQuebrado_respondeOkParaAMetaNaoReenviar() {
        // Qualquer 4xx/5xx faz a Meta repetir o mesmo aviso por horas.
        assertEquals("ok", controller.receber(LOJA, Map.of("entry", "isto nao e uma lista")).get("status"));
        verify(envio, never()).enviar(any(), any(), any());
    }

    @Test
    void mensagemDeAudio_naoQuebra_eCaiNoMenu() {
        Map<String, Object> audio = Map.of("entry", List.of(Map.of("changes", List.of(Map.of(
                "value", Map.of("messages", List.of(Map.of(
                        "from", CLIENTE, "type", "audio",
                        "audio", Map.of("id", "abc")))))))));

        assertEquals("ok", controller.receber(LOJA, audio).get("status"));
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
}
