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

        servico = new IntegracaoService(repo, mock(AuthContext.class), mock(PixService.class),
                List.of(client));
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

        assertNotNull(integ.ultimoErro, "recusado em silencio e o defeito: tem que ficar registrado");
        assertTrue(integ.ultimoErro.contains("SAIU_PARA_ENTREGA"),
                "tem que dizer QUAL status nao passou: " + integ.ultimoErro);
        assertTrue(integ.ultimoErro.contains("1234"),
                "e de qual pedido, senao a loja nao sabe onde olhar: " + integ.ultimoErro);
        assertTrue(integ.ultimoErro.contains("99Food"),
                "e de qual marketplace: " + integ.ultimoErro);
        verify(repo).save(integ);
    }

    @Test
    void oAVISOdizQUEoPEDIDOficouPARADOlaDENTRO() {
        // O que o lojista precisa entender nao e "falhou": e que o cliente final esta vendo outra
        // coisa no aplicativo.
        when(client.enviarStatus(any(), anyString(), anyString())).thenReturn(false);

        servico.notificarStatus(pedido(), "ENTREGUE");

        assertTrue(integ.ultimoErro.toLowerCase().contains("continua no status anterior"),
                "a mensagem tem que explicar a consequencia: " + integ.ultimoErro);
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

    // ---------------------------------------------------------------- e quando passa

    @Test
    void statusACEITO_naoESCREVEnada() {
        // O risco do outro lado: encher a tela de aviso a cada status faria o lojista parar de olhar.
        when(client.enviarStatus(any(), anyString(), anyString())).thenReturn(true);

        servico.notificarStatus(pedido(), "PRONTO");

        assertNull(integ.ultimoErro);
        verify(repo, never()).save(any());
    }

    @Test
    void oCANCELAMENTOnaoPASSAporAQUI() {
        // Cancelamento exige motivo e ja foi pedido ao marketplace antes de salvar
        // (cancelarNoMarketplace). Mandar "CANCELADO" seco aqui faz o iFood recusar.
        servico.notificarStatus(pedido(), "CANCELADO");

        verify(client, never()).enviarStatus(any(), anyString(), anyString());
        assertNull(integ.ultimoErro);
    }

    @Test
    void integracaoDESLIGADA_naoTENTAnemREGISTRA() {
        integ.ativo = false;

        servico.notificarStatus(pedido(), "PRONTO");

        verify(client, never()).enviarStatus(any(), anyString(), anyString());
        assertNull(integ.ultimoErro);
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

        assertNotNull(integ.ultimoErro);
        assertTrue(integ.ultimoErro.length()
                        <= br.com.bora.entity.LarguraDeColuna.INTEGRACAO_ULTIMO_ERRO,
                "passou de " + br.com.bora.entity.LarguraDeColuna.INTEGRACAO_ULTIMO_ERRO
                        + " caracteres: " + integ.ultimoErro.length());
    }
}
