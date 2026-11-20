package br.com.bora.service;

import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Apagar pedido TEM de virar evento na fila.
 *
 * <h2>O defeito, achado na varredura de 18/11/2026</h2>
 * <p>{@code LimpezaDeTestesService} tinha <b>zero</b> chamadas a {@code eventos.dePedido}, enquanto o
 * {@code PedidoService} tem <b>seis</b>. <i>Três agentes acharam isso independentemente.</i></p>
 *
 * <p>Com a fila de eventos <b>ligada</b> (desde 18/11/2026 às 19:08), limpar uma loja deixava na fila
 * um {@code PedidoRecebido} de pedido que <b>não existe mais</b>, sem desfecho nenhum. Quem consumir
 * a fila na fase de acoplamento monta um pedido fantasma que nunca fecha — e o pior tipo de dado
 * errado é o que nasce certo e fica órfão.</p>
 *
 * <p>Não doía ainda: a fila tinha 1 evento e ninguém consome. <b>Doeria no dia do acoplamento</b>, e
 * aí a causa estaria a meses de distância do efeito. O próprio {@code PedidoService.excluir} gasta um
 * comentário inteiro explicando por que isso não pode acontecer — e era este caminho, o que apaga em
 * LOTE, que não fazia.</p>
 */
class AlimpezaEMITEoCANCELAMENTOnaFILATest {

    private static final Long LOJA = 18L;
    private static final String NOME = "Zira Centro";

    private PedidoRepository pedidos;
    private PublicadorDeEventos eventos;
    private LimpezaDeTestesService service;

    @BeforeEach
    void montar() {
        LojaRepository lojas = mock(LojaRepository.class);
        pedidos = mock(PedidoRepository.class);
        PedidoItemRepository itens = mock(PedidoItemRepository.class);
        LogStatusRepository logs = mock(LogStatusRepository.class);
        ClienteRepository clientes = mock(ClienteRepository.class);
        AcertoEntregadorRepository acertos = mock(AcertoEntregadorRepository.class);
        ProdutoRepository produtos = mock(ProdutoRepository.class);
        EstornoDaVendaService estorno = mock(EstornoDaVendaService.class);
        AuthContext ctx = mock(AuthContext.class);
        eventos = mock(PublicadorDeEventos.class);

        Loja l = new Loja();
        l.setId(LOJA);
        l.setNome(NOME);
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));
        when(clientes.findByLojaId(LOJA)).thenReturn(List.of());
        when(ctx.atual()).thenReturn(new BoraPrincipal(1L, null, "ADMINISTRADOR_BORA", "dono@bora.com"));
        when(estorno.devolverSoOestoque(any())).thenReturn(1);

        service = new LimpezaDeTestesService(lojas, pedidos, itens, logs, clientes, acertos, produtos,
                estorno, eventos, ctx);
    }

    private Pedido pedido(long id, StatusPedido status) {
        Pedido p = new Pedido();
        p.id = id;
        p.lojaId = LOJA;
        p.status = status;
        return p;
    }

    private void comPedidos(Pedido... lista) {
        when(pedidos.findByLojaIdOrderByCriadoEmDesc(LOJA)).thenReturn(List.of(lista));
        when(pedidos.countByLojaId(LOJA)).thenReturn((long) lista.length);
    }

    // ----------------------------------------------------------------- a premissa

    /**
     * A PREMISSA: sem pedido nenhum, nada é emitido.
     *
     * <p>Sem isto, um teste que contasse eventos poderia estar medindo chamada de outro lugar.</p>
     */
    @Test
    void aPREMISSAsemPEDIDOnaoSAIevento() {
        comPedidos();

        Map<String, Object> r = service.limpar(LOJA, NOME, false);

        verify(eventos, never()).dePedido(anyString(), any(), anyMap());
        assertEquals(0, r.get("eventosDeCancelamentoEmitidos"));
    }

    // ----------------------------------------------------------------- o que importa

    /** Cada pedido apagado vira um `PedidoCancelado` na fila. */
    @Test
    void cadaPEDIDOapagadoVIRAumCANCELAMENTO() {
        comPedidos(pedido(1L, StatusPedido.ENTREGUE), pedido(2L, StatusPedido.RECEBIDO));

        Map<String, Object> r = service.limpar(LOJA, NOME, false);

        verify(eventos).dePedido(eq("PedidoCancelado"), argThat(p -> p != null && p.id == 1L), anyMap());
        verify(eventos).dePedido(eq("PedidoCancelado"), argThat(p -> p != null && p.id == 2L), anyMap());
        verify(eventos, times(2)).dePedido(anyString(), any(), anyMap());
        assertEquals(2, r.get("eventosDeCancelamentoEmitidos"));
    }

    /** O evento diz de onde veio e que foi exclusão — é o que o consumidor precisa para fechar. */
    @Test
    void oEVENTOdizQUEfoiEXCLUSAOeDEquemPARTIU() {
        comPedidos(pedido(7L, StatusPedido.EM_PREPARO));

        service.limpar(LOJA, NOME, false);

        verify(eventos).dePedido(eq("PedidoCancelado"), any(), argThat(extra ->
                "PLATAFORMA".equals(extra.get("solicitante"))
                        && Boolean.TRUE.equals(extra.get("exclusao"))
                        && "EM_PREPARO".equals(extra.get("fase"))
                        && String.valueOf(extra.get("motivo")).contains("limpeza")));
    }

    // ----------------------------------------------------------------- o controle

    /**
     * O CONTROLE: pedido JÁ cancelado não gera um segundo cancelamento.
     *
     * <p>O cancelamento dele já emitiu o evento quando aconteceu. Emitir de novo não é reentrega — é
     * um <b>segundo</b> cancelamento, com outro {@code evento_id}, que o consumidor não tem como
     * distinguir. Mesma razão pela qual o estoque dele não é devolvido duas vezes.</p>
     */
    @Test
    void oCONTROLEpedidoJAcanceladoNAOgeraOUTROevento() {
        comPedidos(pedido(1L, StatusPedido.CANCELADO), pedido(2L, StatusPedido.RECEBIDO));

        Map<String, Object> r = service.limpar(LOJA, NOME, false);

        verify(eventos, times(1)).dePedido(anyString(), any(), anyMap());
        verify(eventos).dePedido(eq("PedidoCancelado"), argThat(p -> p != null && p.id == 2L), anyMap());
        assertEquals(1, r.get("eventosDeCancelamentoEmitidos"));
        assertEquals(1, r.get("pedidosJaCanceladosSemDevolucao"));
    }

    /** E se a confirmação pelo nome falha, nada é emitido: não há fato, não há evento. */
    @Test
    void semCONFIRMACAOnaoSAIevento() {
        comPedidos(pedido(1L, StatusPedido.RECEBIDO));

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.limpar(LOJA, "nome errado", false));

        verify(eventos, never()).dePedido(anyString(), any(), anyMap());
    }

    // ----------------------------------------------------------------- a mesma transacao

    /**
     * O evento tem de nascer na MESMA transação do apagamento — a regra da entrega A2.
     *
     * <p>Mock não prova transação. O que dá para exigir aqui é o que a torna possível: que o método
     * seja {@code @Transactional}. Sem a anotação, cada gravação é a sua própria transação e o par
     * "ou os dois existem, ou nenhum" deixa de valer.</p>
     */
    @Test
    void oMETODOdeLIMPEZAehTRANSACIONAL() throws Exception {
        String fonte = Files.readString(
                Path.of("src/main/java/br/com/bora/service/LimpezaDeTestesService.java"),
                StandardCharsets.UTF_8);
        int i = fonte.indexOf("public Map<String, Object> limpar(");
        assertTrue(i > 0, "nao achei o metodo limpar");
        String antes = fonte.substring(Math.max(0, i - 200), i);
        assertTrue(antes.contains("@Transactional"),
                "o `limpar` precisa de @Transactional: o evento do outbox tem de ir na mesma "
                        + "transacao do apagamento, senao sobra evento de pedido que nao foi apagado "
                        + "(ou o contrario)");
    }
}
