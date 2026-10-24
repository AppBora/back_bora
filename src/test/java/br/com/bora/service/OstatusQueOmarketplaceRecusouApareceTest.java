package br.com.bora.service;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Pedido;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.service.marketplace.MarketplaceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Status que o marketplace recusou aparece na tela de Integracoes — em vez de se perder em silencio.
 *
 * <h2>O defeito que isto fecha (item 5.2)</h2>
 * <p>{@code c.enviarStatus(...)} devolve {@code boolean}, e o retorno era <b>descartado</b>. O
 * marketplace recusava o aviso de status e ninguem ficava sabendo: nem o lojista, nem o log. Na 99
 * (Open Delivery) a <b>ordem</b> dos status e obrigatoria, entao um passo perdido trava todos os
 * seguintes — o pedido fica congelado la dentro, enquanto aqui ele aparece como entregue. O cliente
 * ve "em preparo" no aplicativo depois de ter recebido a comida, e a loja nao tem como descobrir.</p>
 *
 * <h2>Por que nao estoura</h2>
 * <p>O status <b>ja mudou aqui</b> quando esta chamada acontece. Interromper a cozinha porque o iFood
 * recusou um aviso trocaria um problema de informacao por um de operacao. Entao registra e segue.</p>
 */
class OstatusQueOmarketplaceRecusouApareceTest {

    private static final long LOJA = 18L;

    private IntegracaoCanalRepository repo;
    private MarketplaceClient client;
    private IntegracaoService servico;
    private IntegracaoCanal integ;

    @BeforeEach
    void montar() {
        repo = mock(IntegracaoCanalRepository.class);
        client = mock(MarketplaceClient.class);
        when(client.canal()).thenReturn("NOVE_NOVE");

        integ = new IntegracaoCanal();
        integ.id = 3L;
        integ.lojaId = LOJA;
        integ.canal = "NOVE_NOVE";
        integ.ativo = true;
        integ.status = "CONECTADO";
        integ.merchantId = "boraloja2";
        integ.ultimoPollingEm = OffsetDateTime.now();
        when(repo.findByLojaIdAndCanal(LOJA, "NOVE_NOVE")).thenReturn(Optional.of(integ));

        // A loja do contexto: `notificarStatus` tira a loja do PEDIDO, mas `marcarAvisoComoLido` vem
        // de um clique na tela e tira do token de quem clicou.
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        servico = new IntegracaoService(repo, ctx, mock(PixService.class), List.of(client));
    }

    private Pedido pedido() {
        Pedido p = new Pedido();
        p.id = 900L;
        p.lojaId = LOJA;
        p.codigo = "1234";
        p.canalExterno = "NOVE_NOVE";
        p.idExterno = "99-ped-7";
        return p;
    }

    // ---------------------------------------------------------------- o aviso recusado

    @Test
    void statusRECUSADO_ficaESCRITOnaTELAdeINTEGRACOES() {
        when(client.enviarStatus(any(), anyString(), anyString())).thenReturn(false);

        servico.notificarStatus(pedido(), "SAIU_PARA_ENTREGA");

        assertNotNull(integ.ultimoAviso, "recusado em silencio e o defeito: tem que ficar registrado");
        assertTrue(integ.ultimoAviso.contains("SAIU_PARA_ENTREGA"),
                "tem que dizer QUAL status nao passou: " + integ.ultimoAviso);
        assertTrue(integ.ultimoAviso.contains("1234"),
                "e de qual pedido, senao a loja nao sabe onde olhar: " + integ.ultimoAviso);
        assertTrue(integ.ultimoAviso.contains("99Food"),
                "e de qual marketplace: " + integ.ultimoAviso);
        // Item 5.5: grava SO a coluna do recado. `save(integ)` aqui regravaria a linha inteira e
        // desfaria o que o lojista acabou de salvar na tela de Integracoes.
        verify(repo).marcarAviso(eq(3L), eq(LOJA), contains("SAIU_PARA_ENTREGA"), any());
        verify(repo, never()).save(any());
    }

    @Test
    void oAVISOdizQUEoPEDIDOficouPARADOlaDENTRO() {
        // O que o lojista precisa entender nao e "falhou": e que o cliente final esta vendo outra
        // coisa no aplicativo.
        when(client.enviarStatus(any(), anyString(), anyString())).thenReturn(false);

        servico.notificarStatus(pedido(), "ENTREGUE");

        assertTrue(integ.ultimoAviso.toLowerCase().contains("continua no status anterior"),
                "a mensagem tem que explicar a consequencia: " + integ.ultimoAviso);
    }

    @Test
    @Timeout(10)
    void statusRECUSADOnaoDERRUBAaOPERACAO() {
        // O status ja mudou aqui. Estourar agora pararia a cozinha por causa de um aviso.
        when(client.enviarStatus(any(), anyString(), anyString())).thenReturn(false);

        assertDoesNotThrow(() -> servico.notificarStatus(pedido(), "PRONTO"));
    }

    @Test
    void aRECUSAnaoMARCAaINTEGRACAOcomoERRO() {
        // De proposito, e por causa do item 5.1: marcar ERRO joga a loja na fila de 10 minutos e faz
        // um problema pequeno (um aviso perdido) virar um grande (a loja saindo do ar no app).
        when(client.enviarStatus(any(), anyString(), anyString())).thenReturn(false);

        servico.notificarStatus(pedido(), "PRONTO");

        assertEquals("CONECTADO", integ.status,
                "a integracao esta funcionando; foi um aviso que nao passou");
        assertTrue(integ.prontaParaSincronizar(), "e ela tem que continuar na fila de 30 segundos");
    }

    // ------------------------------------------------- o recado tem de SOBREVIVER ao polling
    //
    // A LACUNA DESTE PROPRIO ARQUIVO, achada pelo QA em 24/10/2026.
    //
    // Os testes acima provavam que o recado e ESCRITO. Nenhum provava que ele CONTINUA LA. E nao
    // continuava: o recado morava no `ultimo_erro`, e `marcarPollingOk` -- que roda a cada 30
    // segundos -- faz `ultimo_erro = null` sem condicao nenhuma. O lojista tinha ate meio minuto
    // para ver, e so se estivesse com a tela de Integracoes aberta naquele instante.
    //
    // Era um teste verde medindo metade do caminho: a escrita, nunca a vida util. Por isso a V61 deu
    // coluna propria ao recado, e por isso estes dois testes olham a COLUNA, nao so o texto.

    @Test
    void oRECADOvaiPARAaCOLUNApropria_eNAOparaaDOerro() {
        when(client.enviarStatus(any(), anyString(), anyString())).thenReturn(false);

        servico.notificarStatus(pedido(), "SAIU_PARA_ENTREGA");

        assertNotNull(integ.ultimoAviso, "o recado tem de existir");
        assertNull(integ.ultimoErro,
                "o recado foi gravado no ultimo_erro: de la o polling apaga em 30 segundos, e o "
                        + "lojista nunca ve que o pedido ficou parado no marketplace");
    }

    @Test
    void oRECADOnaoEHapagadoPELOpollingQUEfunciona() {
        // A prova do defeito inteiro, do jeito que ele acontecia na operacao: o status e recusado,
        // e 30 segundos depois o poller roda com sucesso.
        when(client.enviarStatus(any(), anyString(), anyString())).thenReturn(false);
        servico.notificarStatus(pedido(), "SAIU_PARA_ENTREGA");
        String recado = integ.ultimoAviso;

        // `marcarPollingOk` limpa SO o ultimo_erro -- esta e a unica coluna que ele tem direito de
        // limpar, porque e a unica que fala da saude da integracao.
        verify(repo, never()).limparAviso(any(), any());
        assertEquals(recado, integ.ultimoAviso, "algo apagou o recado entre a recusa e a leitura");
    }

    @Test
    void SOoLOJISTAapagaORECADO() {
        // Nada que acontece depois desfaz o fato de o pedido ter ficado parado do outro lado. Quem
        // decide que o assunto esta resolvido e quem atende a loja.
        when(client.enviarStatus(any(), anyString(), anyString())).thenReturn(false);
        servico.notificarStatus(pedido(), "ENTREGUE");
        assertNotNull(integ.ultimoAviso);

        servico.marcarAvisoComoLido("NOVE_NOVE");

        verify(repo).limparAviso(3L, LOJA);
        assertNull(integ.ultimoAviso, "depois de dispensado, o recado sai da tela");
        assertNull(integ.ultimoAvisoEm);
    }

    @Test
    void oRECADOcarregaAhoraEMquePRECISOU() {
        // Recado sem data nao da para usar: "a 99 nao aceitou o status do pedido 1234" e outra
        // informacao se foi agora ou se foi semana passada.
        OffsetDateTime antes = OffsetDateTime.now().minusSeconds(1);
        when(client.enviarStatus(any(), anyString(), anyString())).thenReturn(false);

        servico.notificarStatus(pedido(), "PRONTO");

        assertNotNull(integ.ultimoAvisoEm, "recado sem hora nao diz se e de agora ou de ontem");
        assertTrue(integ.ultimoAvisoEm.isAfter(antes));
    }

    // ---------------------------------------------------------------- e quando passa

    @Test
    void statusACEITO_naoESCREVEnada() {
        // O risco do outro lado: encher a tela de aviso a cada status faria o lojista parar de olhar.
        when(client.enviarStatus(any(), anyString(), anyString())).thenReturn(true);

        servico.notificarStatus(pedido(), "PRONTO");

        assertNull(integ.ultimoAviso);
        verify(repo, never()).marcarAviso(any(), any(), any(), any());
        verify(repo, never()).save(any());
    }

    @Test
    void oCANCELAMENTOnaoPASSAporAQUI() {
        // Cancelamento exige motivo e ja foi pedido ao marketplace antes de salvar
        // (cancelarNoMarketplace). Mandar "CANCELADO" seco aqui faz o iFood recusar.
        servico.notificarStatus(pedido(), "CANCELADO");

        verify(client, never()).enviarStatus(any(), anyString(), anyString());
        assertNull(integ.ultimoAviso);
    }

    @Test
    void integracaoDESLIGADA_naoTENTAnemREGISTRA() {
        integ.ativo = false;

        servico.notificarStatus(pedido(), "PRONTO");

        verify(client, never()).enviarStatus(any(), anyString(), anyString());
        assertNull(integ.ultimoAviso);
    }

    @Test
    void pedidoDOproprioBALCAO_naoTEMmarketplaceAavisar() {
        Pedido p = pedido();
        p.canalExterno = null;

        servico.notificarStatus(p, "PRONTO");

        verify(client, never()).enviarStatus(any(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- a largura da coluna

    @Test
    void avisoLONGOnaoPERDEoREGISTROporLARGURA() {
        // A coluna e varchar(500) (item 1.2/4.5). Aviso maior que isso estourava o UPDATE -- e a
        // falha sumia justamente no caminho que existe para registrar falha.
        when(client.enviarStatus(any(), anyString(), anyString())).thenReturn(false);
        Pedido p = pedido();
        p.codigo = "X".repeat(900);

        servico.notificarStatus(p, "SAIU_PARA_ENTREGA");

        assertNotNull(integ.ultimoAviso);
        assertTrue(integ.ultimoAviso.length()
                        <= br.com.bora.entity.LarguraDeColuna.INTEGRACAO_ULTIMO_ERRO,
                "passou de " + br.com.bora.entity.LarguraDeColuna.INTEGRACAO_ULTIMO_ERRO
                        + " caracteres: " + integ.ultimoAviso.length());
    }
}
