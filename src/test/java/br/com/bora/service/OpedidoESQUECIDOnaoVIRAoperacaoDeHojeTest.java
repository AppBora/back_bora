package br.com.bora.service;

import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O pedido esquecido nao se passa por operacao de hoje.
 *
 * <h2>O defeito (achado pelo QA em 26/10/2026)</h2>
 * <p>No bloco de operacao do Dashboard, "Na cozinha" e "Saiu p/ entrega" eram {@code count} por
 * status <b>sem janela nenhuma</b>: contavam desde que a loja abriu. Pedido que o lojista esqueceu de
 * avancar soma ali para sempre, e o numero que deveria dizer "o que esta acontecendo agora" passa a
 * dizer "o que ja passou e ninguem fechou".</p>
 *
 * <p><b>Esta acontecendo:</b> medido na producao em 27/10/2026, somente leitura, os QUATRO pedidos
 * abertos do sistema inteiro sao de dias anteriores — a loja 1 tem um em SAIU_PARA_ENTREGA desde
 * <b>19/09</b>, 38 dias. O painel dela diz "Saiu p/ entrega: 1" todo dia por causa dele.</p>
 *
 * <h2>A escolha: avisar, nao esconder</h2>
 * <p>Filtrar o antigo fora trocaria um numero inflado por um numero que mente para baixo — e o pedido
 * esquecido deixaria de ter qualquer lugar onde aparecer. O contador continua contando tudo, e ao
 * lado dele vem quantos sao de dias anteriores. E a mesma escolha do {@code itensSemCusto} no
 * relatorio: o numero sai, e para de parecer o que nao e.</p>
 */
class OpedidoESQUECIDOnaoVIRAoperacaoDeHojeTest {

    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    private static final Long LOJA = 1L;

    private PedidoRepository repo;
    private PedidoService service;

    @BeforeEach
    void montar() {
        repo = mock(PedidoRepository.class);
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        service = new PedidoService(repo, mock(br.com.bora.repository.PedidoItemRepository.class),
                mock(br.com.bora.repository.ProdutoRepository.class),
                mock(br.com.bora.repository.ClienteRepository.class),
                mock(br.com.bora.repository.LogStatusRepository.class),
                mock(PlanoService.class), mock(IntegracaoService.class),
                mock(br.com.bora.repository.TaxaEntregaRepository.class),
                mock(InsumoService.class), mock(FidelidadeService.class),
                mock(ComplementoService.class), ctx, mock(EstornoDaVendaService.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
    }

    /** O instante que separa hoje de ontem, no fuso da loja — e nao o do container. */
    private OffsetDateTime inicioDeHoje() {
        return LocalDate.now(ZONA).atStartOfDay(ZONA).toOffsetDateTime();
    }

    // ---------------------------------------------------------------- o conserto
    @Test
    void oRESUMOdizQUANTOSabertosSAOdeDIASanteriores() {
        when(repo.countByLojaIdAndStatus(LOJA, StatusPedido.EM_PREPARO)).thenReturn(3L);
        when(repo.countByLojaIdAndStatusInAndCriadoEmBefore(eq(LOJA), anySet(), any())).thenReturn(2L);

        Map<String, Object> r = service.resumo();

        assertEquals(3L, r.get("emPreparo"), "o numero verdadeiro de abertos tem de continuar saindo");
        assertEquals(2L, r.get("abertosDeOutrosDias"),
                "sem este numero, pedido esquecido se passa por operacao de hoje para sempre");
    }

    @Test
    void oCORTEehAmeiaNOITEdeSAOpaulo_eNAOaDOcontainer() {
        // O container roda em UTC. Cortar pela meia-noite dele erraria por 3 horas: das 21h a
        // meia-noite de Sao Paulo, pedido de HOJE seria contado como esquecido. Ja aconteceu neste
        // projeto o bastante para virar teste.
        when(repo.countByLojaIdAndStatusInAndCriadoEmBefore(eq(LOJA), anySet(), any())).thenReturn(0L);

        service.resumo();

        var corte = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repo).countByLojaIdAndStatusInAndCriadoEmBefore(eq(LOJA), anySet(), corte.capture());
        assertEquals(inicioDeHoje(), corte.getValue(),
                "o corte nao e a meia-noite de Sao Paulo");
    }

    @Test
    void oCONTADORporSTATUStambemDIZquantosSAOantigos() {
        // Para a tela poder dizer qual dos dois mosaicos esta inflado, e nao so que algum esta.
        when(repo.countByLojaIdAndStatusAndCriadoEmBefore(LOJA, StatusPedido.SAIU_PARA_ENTREGA, inicioDeHoje()))
                .thenReturn(1L);

        Map<String, Object> r = service.resumo();

        assertEquals(1L, r.get("saiuParaEntregaDeOutrosDias"));
        assertEquals(0L, r.get("emPreparoDeOutrosDias"));
    }

    @Test
    void oAVISOcobreTAMBEMoqueNAOtemMOSAICO() {
        // RECEBIDO, CONFIRMADO e PRONTO nao tem mosaico proprio no bloco de operacao. Sem entrar na
        // conta de "abertos de outros dias", o RECEBIDO de 30/09 da loja 1 nao apareceria em lugar
        // nenhum.
        service.resumo();

        var status = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(repo).countByLojaIdAndStatusInAndCriadoEmBefore(eq(LOJA), status.capture(), any());
        assertEquals(Set.of(StatusPedido.RECEBIDO, StatusPedido.CONFIRMADO, StatusPedido.EM_PREPARO,
                        StatusPedido.PRONTO, StatusPedido.SAIU_PARA_ENTREGA),
                Set.copyOf(status.getValue()),
                "o aviso tem de cobrir todo status aberto, nao so os dois com mosaico");
    }

    // ---------------------------------------------------------------- o que nao pode mudar
    @Test
    void oNUMEROdeABERTOSnaoEHfiltrado() {
        // Esconder o antigo trocaria um numero inflado por um que mente para baixo -- e o pedido
        // esquecido ficaria sem nenhum lugar onde aparecer.
        when(repo.countByLojaIdAndStatus(LOJA, StatusPedido.EM_PREPARO)).thenReturn(3L);
        when(repo.countByLojaIdAndStatus(LOJA, StatusPedido.SAIU_PARA_ENTREGA)).thenReturn(1L);

        Map<String, Object> r = service.resumo();

        assertEquals(3L, r.get("emPreparo"));
        assertEquals(1L, r.get("saiuParaEntrega"));
        // E o contador verdadeiro continua sendo o `countByLojaIdAndStatus`, sem corte de data: se
        // alguem trocar por uma versao com janela, o numero passa a mentir para baixo.
        verify(repo).countByLojaIdAndStatus(LOJA, StatusPedido.EM_PREPARO);
        verify(repo).countByLojaIdAndStatus(LOJA, StatusPedido.SAIU_PARA_ENTREGA);
    }

    @Test
    void oENTREGUESnaULTIMAhoraCONTINUAcomJANELAdeUMAhora() {
        when(repo.countByLojaIdAndEntregueEmAfter(eq(LOJA), any())).thenReturn(5L);

        assertEquals(5L, service.resumo().get("entreguesUltimaHora"));

        var corte = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repo).countByLojaIdAndEntregueEmAfter(eq(LOJA), corte.capture());
        long minutos = java.time.Duration.between(corte.getValue(), OffsetDateTime.now()).toMinutes();
        assertTrue(minutos >= 59 && minutos <= 61, "a janela virou " + minutos + " minutos");
    }

    @Test
    void lojaSEMpedidoABERTOnaoGANHAaviso() {
        // Aviso que aparece sempre vira ruido e o lojista para de ler.
        Map<String, Object> r = service.resumo();

        assertEquals(0L, r.get("abertosDeOutrosDias"));
        assertEquals(0L, r.get("emPreparo"));
    }
}
