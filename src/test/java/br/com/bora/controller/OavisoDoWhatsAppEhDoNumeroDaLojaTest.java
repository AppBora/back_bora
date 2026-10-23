package br.com.bora.controller;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Loja;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.service.WhatsAppSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O aviso do WhatsApp e atendido pela loja do numero que recebeu a mensagem — nao pela loja do endereco.
 *
 * <h2>Item 5.9, e o que medir corrigiu na propria varredura</h2>
 * <p>A varredura dizia que o App Secret unico "quebra na <b>segunda</b> loja que ligar o robo".
 * <b>Nao quebra:</b> o {@code lojaId} vem do caminho da URL ({@code /whatsapp-webhook/{lojaId}}), nao
 * da assinatura, entao cada loja tem o seu endereco e as duas funcionam. O App Secret e da plataforma
 * de proposito — e um aplicativo Meta so, com os numeros das lojas dentro. Ha um teste disso aqui,
 * para a conclusao errada nao voltar.</p>
 *
 * <h2>O furo que existia de verdade</h2>
 * <p>A assinatura prova que o corpo veio da Meta, e mais nada: ela nao amarra o corpo a loja do
 * endereco. Um corpo <b>legitimo</b> da loja A, reenviado para o endereco da loja B, era aceito como
 * evento da B — e o robo responderia ao cliente da A com o nome e o cardapio da B. Quem tem o corpo
 * assinado e qualquer um no caminho que o tenha visto uma vez; o id da loja esta na URL publica do
 * cardapio, entao achar o endereco da B nao e obstaculo.</p>
 *
 * <p>A Meta manda em {@code value.metadata.phone_number_id} <b>qual numero</b> recebeu a mensagem, e
 * esse id e justamente o que a integracao da loja guarda no {@code clientId}.</p>
 */
class OavisoDoWhatsAppEhDoNumeroDaLojaTest {

    private static final long LOJA_A = 18L;
    private static final long LOJA_B = 25L;
    private static final String NUMERO_A = "111111111";
    private static final String NUMERO_B = "999999999";
    private static final String CLIENTE = "5515998887777";
    private static final String APP_SECRET = "segredo-do-aplicativo-meta-para-teste";

    private IntegracaoCanalRepository integracoes;
    private WhatsAppSender envio;
    private WhatsAppController controller;
    private IntegracaoCanal zapA;
    private IntegracaoCanal zapB;

    @BeforeEach
    void montar() {
        integracoes = mock(IntegracaoCanalRepository.class);
        envio = mock(WhatsAppSender.class);
        LojaRepository lojas = mock(LojaRepository.class);

        zapA = zap(LOJA_A, NUMERO_A);
        zapB = zap(LOJA_B, NUMERO_B);
        when(integracoes.findByLojaIdAndCanal(LOJA_A, "WHATSAPP")).thenReturn(Optional.of(zapA));
        when(integracoes.findByLojaIdAndCanal(LOJA_B, "WHATSAPP")).thenReturn(Optional.of(zapB));

        Loja a = new Loja();
        a.nome = "Acai Zira - Montreal";
        Loja b = new Loja();
        b.nome = "Hamburgueria do Centro";
        when(lojas.findById(LOJA_A)).thenReturn(Optional.of(a));
        when(lojas.findById(LOJA_B)).thenReturn(Optional.of(b));

        controller = new WhatsAppController(integracoes, lojas, envio, APP_SECRET);
    }

    private IntegracaoCanal zap(long lojaId, String phoneNumberId) {
        IntegracaoCanal i = new IntegracaoCanal();
        i.lojaId = lojaId;
        i.canal = "WHATSAPP";
        i.ativo = true;
        i.clientId = phoneNumberId;      // Phone Number ID da Meta
        i.clientSecret = "token-da-loja-" + lojaId;
        i.webhookToken = "verify-" + lojaId;
        return i;
    }

    // ---------------------------------------------------------------- o envelope da Meta

    /** Corpo cru com metadata, como a Meta manda de verdade. */
    private String corpo(String phoneNumberId, String texto) {
        String metadata = phoneNumberId == null ? ""
                : "\"metadata\":{\"display_phone_number\":\"5515000000000\","
                        + "\"phone_number_id\":\"" + phoneNumberId + "\"},";
        return "{\"entry\":[{\"changes\":[{\"value\":{" + metadata
                + "\"messages\":[{\"from\":\"" + CLIENTE + "\",\"type\":\"text\","
                + "\"text\":{\"body\":\"" + texto + "\"}}]}}]}]}";
    }

    private static String assinar(String corpo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder hex = new StringBuilder("sha256=");
            for (byte b : mac.doFinal(corpo.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Entrega o corpo no endereco de uma loja, com assinatura valida da Meta. */
    private Map<String, String> entregarEm(long lojaDoEndereco, String corpo) {
        return controller.receber(lojaDoEndereco, corpo, assinar(corpo));
    }

    // ---------------------------------------------------------------- o furo de verdade

    @Test
    void corpoDAlojaA_reenviadoNOenderecoDAlojaB_eIGNORADO() {
        // O corpo e legitimo e a assinatura confere: o que nao confere e a LOJA.
        Map<String, String> r = entregarEm(LOJA_B, corpo(NUMERO_A, "oi"));

        assertEquals("ignored", r.get("status"));
        verify(envio, never()).enviar(any(), anyString(), anyString());
    }

    @Test
    void eOclienteDAlojaAnaoRECEBEoCARDAPIOdaB() {
        // O dano concreto, dito por extenso: sem a conferencia, o cliente da acaiteria receberia o
        // nome e o link da hamburgueria -- com o numero comercial da hamburgueria.
        entregarEm(LOJA_B, corpo(NUMERO_A, "menu"));

        verify(envio, never()).enviar(eq(zapB), anyString(), anyString());
        verify(envio, never()).enviar(any(), eq(CLIENTE), contains("Hamburgueria"));
    }

    // ---------------------------------------------------------------- e o que tem que continuar funcionando

    @Test
    void oAVISOdoPROPRIOnumero_eATENDIDO() {
        entregarEm(LOJA_A, corpo(NUMERO_A, "oi"));

        verify(envio).enviar(eq(zapA), eq(CLIENTE), contains("Acai Zira"));
    }

    @Test
    void asDUASlojasFUNCIONAM_cadaUMAnoSEUendereco() {
        // A conclusao que a varredura errou: o App Secret unico NAO quebra a segunda loja, porque o
        // lojaId vem do caminho da URL. Se alguem "consertar" isso com um segredo por loja, este
        // teste continua passando -- e e para isso que ele serve: a premissa fica escrita.
        entregarEm(LOJA_A, corpo(NUMERO_A, "oi"));
        entregarEm(LOJA_B, corpo(NUMERO_B, "oi"));

        verify(envio).enviar(eq(zapA), eq(CLIENTE), contains("Acai Zira"));
        verify(envio).enviar(eq(zapB), eq(CLIENTE), contains("Hamburgueria"));
    }

    @Test
    void avisoSEMmetadata_aindaEatendido() {
        // Ainda existem avisos da Meta sem metadata. Recusar o que nao se entende deixaria o robo
        // mudo -- pior que o furo, porque atinge todo cliente de boa-fe.
        entregarEm(LOJA_A, corpo(null, "oi"));

        verify(envio).enviar(eq(zapA), eq(CLIENTE), anyString());
    }

    @Test
    @Timeout(10)
    void metadataEMformatoINESPERADOnaoCALAoROBO() {
        // A conferencia do numero nao pode ser um jeito novo de o robo emudecer. Se a Meta mudar o
        // formato do metadata, a mensagem do cliente tem que continuar sendo respondida.
        //
        // Este teste nasceu de um mutante que SOBREVIVEU: a primeira versao usava "entry" torto, e aí
        // o caminho principal estoura no mesmo lugar -- o catch de fora devolve 200 de qualquer jeito,
        // e trocar o catch de dentro por um `throw` nao mudava nada de observavel. Com so o metadata
        // torto, o caminho principal funciona: aí da para medir a diferenca.
        String torto = "{\"entry\":[{\"changes\":[{\"value\":{"
                + "\"metadata\":\"isto devia ser um objeto\","
                + "\"messages\":[{\"from\":\"" + CLIENTE + "\",\"type\":\"text\","
                + "\"text\":{\"body\":\"oi\"}}]}}]}]}";

        Map<String, String> r = entregarEm(LOJA_A, torto);

        assertEquals("ok", r.get("status"), "tem que devolver 200, nao estourar: a Meta reenvia");
        verify(envio).enviar(eq(zapA), eq(CLIENTE), anyString());
    }

    @Test
    @Timeout(10)
    void corpoINTEIROemFORMATOinesperadoDEVOLVE200() {
        // A Meta REENVIA o que falha. Estourar aqui viraria uma fila de reentrega crescendo sozinha.
        Map<String, String> r = entregarEm(LOJA_A, "{\"entry\":\"isto devia ser uma lista\"}");

        assertNotNull(r.get("status"), "tem que devolver 200 com alguma resposta, nao estourar");
        verify(envio, never()).enviar(any(), anyString(), anyString());
    }

    @Test
    void semASSINATURAnadaPASSA_mesmoCOMoNUMEROcerto() {
        // A conferencia do numero SOMA com a assinatura; nao substitui. O phone_number_id anda no
        // corpo, entao sozinho ele nao prova nada: qualquer um escreveria o numero certo.
        Map<String, String> r = controller.receber(LOJA_A, corpo(NUMERO_A, "oi"), "sha256=00");

        assertEquals("ignored", r.get("status"));
        verify(envio, never()).enviar(any(), anyString(), anyString());
    }
}
