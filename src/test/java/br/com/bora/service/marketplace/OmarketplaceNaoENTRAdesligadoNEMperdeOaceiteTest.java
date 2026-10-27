package br.com.bora.service.marketplace;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.service.PedidoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Dois dos quatro achados de marketplace, medidos por mim em 27/10/2026 antes de consertar.
 *
 * <h2>1. O aceite recusado nunca era retentado</h2>
 * <p>O {@code reenviarAceiteSeFaltou} era chamado de <b>um unico lugar</b>: dentro do
 * {@code criarPedido}, isto e, so quando chegava outro evento do mesmo pedido. E no iFood o segundo
 * evento (CONFIRMED) chega <b>depois</b> que o aceite da certo — entao o caso que precisava de
 * retentativa era exatamente o que nao recebia nenhuma. O pedido fica RECEBIDO no painel, nao aceito
 * no canal, e o cliente do marketplace espera uma comida que a loja acha que aceitou.</p>
 *
 * <h2>2. O cancelamento recusado so ia para o log</h2>
 * <p>{@code CANCELLATION_REQUEST_FAILED} era tratado com um {@code log.warn} cujo proprio texto dizia
 * "precisa de gente olhando". Ninguem olha log de container. O pedido fica cancelado aqui e ativo la,
 * e a loja descobre quando o cliente reclama.</p>
 *
 * <h2>A JANELA DE TEMPO, que e o cuidado principal</h2>
 * <p>A producao tem um pedido RECEBIDO de <b>30/09/2026</b> com {@code aceite_enviado_em} nulo — nulo
 * porque a coluna nasceu na V48, em 14/10, e nao porque o aceite falhou. Uma retentativa sem janela
 * mandaria "confirmo" ao iFood para um pedido de um mes atras. <b>Isso seria pior que o defeito</b>, e
 * e o que esta classe mede com mais cuidado.</p>
 */
class OmarketplaceNaoENTRAdesligadoNEMperdeOaceiteTest {

    private static final Long LOJA = 18L;
    private static final String CANAL = "IFOOD";

    private PedidoService pedidos;
    private IntegracaoCanalRepository repo;
    private MarketplaceClient client;
    private br.com.bora.security.RegraDeAcesso regra;
    private MarketplacePoller poller;

    @BeforeEach
    void montar() {
        pedidos = mock(PedidoService.class);
        repo = mock(IntegracaoCanalRepository.class);
        client = mock(MarketplaceClient.class);
        when(client.canal()).thenReturn(CANAL);
        when(client.configurado(any())).thenReturn(true);
        when(client.polling(any())).thenReturn(List.of());
        when(client.enviarStatus(any(), anyString(), anyString())).thenReturn(true);
        // `conectadas` tambem exige que a LOJA possa operar. O dublê de boolean devolve false por
        // padrao, entao sem este stub a integracao nem entra no ciclo e o teste passaria verde
        // medindo nada -- foi o que a primeira execucao mostrou, em 5 dos 9.
        regra = mock(br.com.bora.security.RegraDeAcesso.class);
        when(regra.podeOperar(any())).thenReturn(true);
        poller = new MarketplacePoller(List.of(client), repo,
                mock(br.com.bora.service.MarketplaceNormalizer.class), pedidos,
                mock(br.com.bora.repository.LojaRepository.class), regra, true);
    }

    private IntegracaoCanal integracao(boolean autoAceitar) {
        IntegracaoCanal i = new IntegracaoCanal();
        i.id = 7L;
        i.lojaId = LOJA;
        i.canal = CANAL;
        i.ativo = true;
        i.autoAceitar = autoAceitar;
        i.merchantId = "m-123";
        // `conectadas` so devolve integracao `prontaParaSincronizar`, que exige merchantId e status
        // CONECTADO. Sem isto o ciclo nao chega no codigo que este teste mede -- e passaria verde
        // medindo nada.
        i.status = "CONECTADO";
        return i;
    }

    private Pedido pedido(long id, String idExterno, StatusPedido status, int minutosAtras) {
        Pedido p = new Pedido();
        p.id = id;
        p.lojaId = LOJA;
        p.canalExterno = CANAL;
        p.idExterno = idExterno;
        p.status = status;
        p.criadoEm = OffsetDateTime.now().minusMinutes(minutosAtras);
        p.aceiteEnviadoEm = null;
        return p;
    }

    /** Roda um ciclo do poller para a integração dada. */
    private void umCiclo(IntegracaoCanal i) {
        when(repo.findByAtivoTrue()).thenReturn(List.of(i));
        poller.rodar();
    }

    // ---------------------------------------------------------------- a premissa
    @Test
    void PREMISSA_oCICLOalcancaAintegracao() {
        // Sem isto, um ciclo que nao chega no `processar` faz todo assert abaixo passar vazio: foi
        // exatamente o que aconteceu na primeira execucao deste arquivo, por falta do stub do
        // `podeOperar`.
        IntegracaoCanal i = integracao(true);
        when(pedidos.semAceiteConfirmado(eq(LOJA), eq(CANAL), any())).thenReturn(List.of());

        umCiclo(i);

        verify(client).polling(i);
    }

    // ---------------------------------------------------------------- 1. a retentativa
    @Test
    void oACEITEprezoEHretentadoSEMdependerDEeventoNOVO() {
        IntegracaoCanal i = integracao(true);
        Pedido preso = pedido(500L, "ext-500", StatusPedido.RECEBIDO, 5);
        when(pedidos.semAceiteConfirmado(eq(LOJA), eq(CANAL), any())).thenReturn(List.of(preso));

        umCiclo(i);

        verify(client).enviarStatus(i, "ext-500", MarketplaceClient.ACEITE_INICIAL);
        verify(pedidos).marcarAceiteEnviado(LOJA, 500L);
    }

    @Test
    void aRETENTATIVAacontecePOLLINGvazio() {
        // O ponto do conserto: nao depende de haver evento. Polling vazio e o caso normal.
        IntegracaoCanal i = integracao(true);
        when(client.polling(any())).thenReturn(List.of());
        when(pedidos.semAceiteConfirmado(eq(LOJA), eq(CANAL), any()))
                .thenReturn(List.of(pedido(500L, "ext-500", StatusPedido.RECEBIDO, 5)));

        umCiclo(i);

        verify(client).enviarStatus(any(), eq("ext-500"), eq(MarketplaceClient.ACEITE_INICIAL));
    }

    @Test
    void aJANELAehDEduasHORAS_eEHelaQUEprotegeOpedidoANTIGO() {
        // O cuidado principal. A consulta recebe o corte; se ele vier largo, o pedido de 30/09 da
        // producao entraria na retentativa.
        IntegracaoCanal i = integracao(true);
        when(pedidos.semAceiteConfirmado(eq(LOJA), eq(CANAL), any())).thenReturn(List.of());

        umCiclo(i);

        var corte = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(pedidos).semAceiteConfirmado(eq(LOJA), eq(CANAL), corte.capture());
        long minutos = java.time.Duration.between(corte.getValue(), OffsetDateTime.now()).toMinutes();
        assertTrue(minutos >= 119 && minutos <= 121,
                "a janela virou " + minutos + " minutos; com janela larga a retentativa alcanca "
                        + "pedido de semanas atras e manda aceite para o canal");
    }

    @Test
    void semAUTOaceitarNAOretenta() {
        // Loja que escolheu aceitar na mao nao pode ter o pedido aceito sozinho.
        IntegracaoCanal i = integracao(false);

        umCiclo(i);

        verify(pedidos, never()).semAceiteConfirmado(anyLong(), anyString(), any());
        verify(client, never()).enviarStatus(any(), anyString(), anyString());
    }

    @Test
    void oTETOporCICLOehRESPEITADO() {
        // Fila grande nao pode virar rajada de chamadas ao canal.
        IntegracaoCanal i = integracao(true);
        List<Pedido> muitos = new java.util.ArrayList<>();
        for (int n = 1; n <= 25; n++) muitos.add(pedido(600L + n, "ext-" + n, StatusPedido.RECEBIDO, 5));
        when(pedidos.semAceiteConfirmado(eq(LOJA), eq(CANAL), any())).thenReturn(muitos);

        umCiclo(i);

        verify(client, times(10)).enviarStatus(any(), anyString(), eq(MarketplaceClient.ACEITE_INICIAL));
    }

    @Test
    void oACEITErecusadoNAOgravaAmarca_eDEIXArecado() {
        // Sem a marca, o proximo ciclo tenta de novo -- e e assim que a retentativa funciona.
        IntegracaoCanal i = integracao(true);
        when(client.enviarStatus(any(), anyString(), anyString())).thenReturn(false);
        when(pedidos.semAceiteConfirmado(eq(LOJA), eq(CANAL), any()))
                .thenReturn(List.of(pedido(500L, "ext-500", StatusPedido.RECEBIDO, 5)));

        umCiclo(i);

        verify(pedidos, never()).marcarAceiteEnviado(anyLong(), anyLong());
        verify(repo).marcarAviso(eq(7L), eq(LOJA), contains("ext-500"), any());
    }

    // ---------------------------------------------------------------- 2. o cancelamento recusado
    @Test
    void oCANCELAMENTOrecusadoDEIXArecadoNAtela() {
        IntegracaoCanal i = integracao(true);
        when(client.tipoEvento(any())).thenReturn("CANCELLATION_REQUEST_FAILED");
        when(client.pedidoDoEvento(any())).thenReturn("ext-900");
        when(client.ehPedidoNovo(anyString())).thenReturn(false);
        when(client.ehCancelamento(anyString())).thenReturn(false);
        when(client.ehPedidoDeCancelamento(anyString())).thenReturn(false);
        when(client.statusQueOcanalAvancou(anyString())).thenReturn(null);

        poller.tratarEvento(client, i, Map.of("id", "e1"));

        var texto = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repo).marcarAviso(eq(7L), eq(LOJA), texto.capture(), any());
        assertTrue(texto.getValue().contains("ext-900"), "o recado nao diz qual pedido: " + texto.getValue());
        assertTrue(texto.getValue().toUpperCase().contains("RECUSOU"),
                "o recado nao diz o que aconteceu: " + texto.getValue());
        assertTrue(texto.getValue().toLowerCase().contains("ativo"),
                "o recado tem de dizer que o pedido segue ATIVO no canal: " + texto.getValue());
    }

    @Test
    void oCANCELAMENTOrecusadoNAOreabreOpedido() {
        // Reabrir sozinho um pedido que o lojista cancelou seria pior que avisar: quem decide se vai
        // preparar e a loja.
        IntegracaoCanal i = integracao(true);
        when(client.tipoEvento(any())).thenReturn("CANCELLATION_REQUEST_FAILED");
        when(client.pedidoDoEvento(any())).thenReturn("ext-900");
        when(client.ehPedidoNovo(anyString())).thenReturn(false);
        when(client.ehCancelamento(anyString())).thenReturn(false);
        when(client.ehPedidoDeCancelamento(anyString())).thenReturn(false);
        when(client.statusQueOcanalAvancou(anyString())).thenReturn(null);

        poller.tratarEvento(client, i, Map.of("id", "e1"));

        verify(pedidos, never()).avancarPorMarketplace(anyLong(), anyString(), anyString(), any());
        verify(pedidos, never()).criarExterno(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void oRECADOvaiPARAoAVISO_eNAOparaOultimoERRO() {
        // `ultimo_erro` e apagado pelo marcarPollingOk a cada 30 segundos: foi o defeito da v146. O
        // recado tem de ir para a coluna propria (V61).
        IntegracaoCanal i = integracao(true);
        when(client.tipoEvento(any())).thenReturn("CANCELLATION_REQUEST_FAILED");
        when(client.pedidoDoEvento(any())).thenReturn("ext-900");
        when(client.ehPedidoNovo(anyString())).thenReturn(false);
        when(client.ehCancelamento(anyString())).thenReturn(false);
        when(client.ehPedidoDeCancelamento(anyString())).thenReturn(false);
        when(client.statusQueOcanalAvancou(anyString())).thenReturn(null);

        poller.tratarEvento(client, i, Map.of("id", "e1"));

        verify(repo).marcarAviso(anyLong(), anyLong(), anyString(), any());
        verify(repo, never()).marcarErro(anyLong(), anyLong(), anyString());
    }
}
