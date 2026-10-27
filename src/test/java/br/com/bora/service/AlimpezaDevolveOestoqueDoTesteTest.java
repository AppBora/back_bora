package br.com.bora.service;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.AcertoEntregadorRepository;
import br.com.bora.repository.ClienteRepository;
import br.com.bora.repository.LogStatusRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Limpar a movimentacao de teste devolve o estoque que ela baixou.
 *
 * <h2>O defeito (achado pelo QA em 26/10/2026)</h2>
 * <p>Quem vende o sistema testa dentro da conta do cliente — e o javadoc da
 * {@link LimpezaDeTestesService} diz isso com estas palavras. Cada pedido de mentira baixou estoque:
 * produto sem ficha tirou do proprio saldo, produto com ficha consumiu insumo.</p>
 *
 * <p>A limpeza apagava os pedidos e <b>nao devolvia nada</b>. O lojista ficava com o estoque curto
 * <b>e sem o historico que explicaria</b> — a contagem desaparece junto com a prova. Para um insumo,
 * que se mede em quilos e nao se confere olhando a prateleira, isso e silencioso por semanas.</p>
 *
 * <h2>O que NAO pode acontecer</h2>
 * <p>Devolver duas vezes. Pedido que ja estava CANCELADO teve o estoque devolvido no cancelamento;
 * devolver de novo somaria estoque que nunca saiu, e estoque inventado e silencioso. Entre errar
 * somando e nao mexer, nao mexer e melhor — a regra que este codigo ja aplica ao item sem a marca da
 * V45.</p>
 */
class AlimpezaDevolveOestoqueDoTesteTest {

    private static final Long LOJA = 18L;
    private static final String NOME = "Zira Centro";

    private LojaRepository lojas;
    private PedidoRepository pedidos;
    private PedidoItemRepository itens;
    private LogStatusRepository logs;
    private ClienteRepository clientes;
    private AcertoEntregadorRepository acertos;
    private ProdutoRepository produtos;
    private EstornoDaVendaService estorno;
    private AuthContext ctx;
    private LimpezaDeTestesService service;

    @BeforeEach
    void montar() {
        lojas = mock(LojaRepository.class);
        pedidos = mock(PedidoRepository.class);
        itens = mock(PedidoItemRepository.class);
        logs = mock(LogStatusRepository.class);
        clientes = mock(ClienteRepository.class);
        acertos = mock(AcertoEntregadorRepository.class);
        produtos = mock(ProdutoRepository.class);
        estorno = mock(EstornoDaVendaService.class);
        ctx = mock(AuthContext.class);

        Loja l = new Loja();
        l.setId(LOJA);
        l.setNome(NOME);
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));
        when(clientes.findByLojaId(LOJA)).thenReturn(List.of());
        when(ctx.atual()).thenReturn(new BoraPrincipal(1L, null, "ADMINISTRADOR_BORA", "dono@bora.com"));
        when(estorno.devolverSoOestoque(any())).thenReturn(1);
        service = new LimpezaDeTestesService(lojas, pedidos, itens, logs, clientes, acertos, produtos,
                estorno, ctx);
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

    private Map<String, Object> limpar() {
        return service.limpar(LOJA, NOME, false);
    }

    // ---------------------------------------------------------------- o conserto
    @Test
    void oESTOQUEvoltaANTESdeAPAGAR() {
        comPedidos(pedido(1L, StatusPedido.ENTREGUE), pedido(2L, StatusPedido.RECEBIDO));

        Map<String, Object> r = limpar();

        verify(estorno).devolverSoOestoque(argThat(p -> p != null && p.id == 1L));
        verify(estorno).devolverSoOestoque(argThat(p -> p != null && p.id == 2L));
        assertEquals(2, r.get("estoqueDevolvidoEmPedidos"));
        assertEquals(2, r.get("estoqueDevolvidoEmItens"));
    }

    @Test
    void aDEVOLUCAOacontecePRIMEIRO_eNAOdepoisDEapagarOitem() {
        // Depois do delete nao ha mais item para dizer o que devolver. A ordem e o conserto.
        comPedidos(pedido(1L, StatusPedido.ENTREGUE));

        limpar();

        org.mockito.InOrder ordem = inOrder(estorno, itens, pedidos);
        ordem.verify(estorno).devolverSoOestoque(any());
        ordem.verify(itens).deleteByLojaId(LOJA);
        ordem.verify(pedidos).deleteByLojaId(LOJA);
    }

    @Test
    void oESTOQUEdeQUALQUERestagioVOLTA() {
        // Nenhuma guarda de estagio aqui, de proposito: num pedido de teste nenhuma polpa saiu do
        // pote. A guarda existe no cancelamento, onde a comida pode ter sido feita de verdade.
        comPedidos(pedido(1L, StatusPedido.EM_PREPARO), pedido(2L, StatusPedido.SAIU_PARA_ENTREGA),
                pedido(3L, StatusPedido.PRONTO), pedido(4L, StatusPedido.ENTREGUE));

        limpar();

        verify(estorno, times(4)).devolverSoOestoque(any());
    }

    // ---------------------------------------------------------------- o que NAO pode
    @Test
    void pedidoJAcanceladoNAOdevolveDEnovo() {
        // O cancelamento dele ja devolveu. Devolver outra vez somaria estoque que nunca saiu.
        comPedidos(pedido(1L, StatusPedido.CANCELADO), pedido(2L, StatusPedido.ENTREGUE));

        Map<String, Object> r = limpar();

        verify(estorno, times(1)).devolverSoOestoque(any());
        verify(estorno, never()).devolverSoOestoque(argThat(p -> p != null && p.id == 1L));
        assertEquals(1, r.get("pedidosJaCanceladosSemDevolucao"));
    }

    @Test
    void oRESULTADOdizQUANTOSficaramDEfora() {
        // Quem limpa precisa saber o que conferir a mao: o cancelado nao e devolvido aqui, e pode
        // ter ficado com insumo a menos por causa da guarda de estagio do cancelamento.
        comPedidos(pedido(1L, StatusPedido.CANCELADO), pedido(2L, StatusPedido.CANCELADO),
                pedido(3L, StatusPedido.ENTREGUE));

        Map<String, Object> r = limpar();

        assertEquals(2, r.get("pedidosJaCanceladosSemDevolucao"));
        assertEquals(1, r.get("estoqueDevolvidoEmPedidos"));
    }

    @Test
    void pedidoQUEnaoDEVOLVEUnadaNAOentraNAconta() {
        // Item sem a marca da V45 nao devolve nada, e o servico devolve 0. Contar como pedido
        // devolvido faria o relatorio da limpeza mentir.
        comPedidos(pedido(1L, StatusPedido.ENTREGUE));
        when(estorno.devolverSoOestoque(any())).thenReturn(0);

        Map<String, Object> r = limpar();

        assertEquals(0, r.get("estoqueDevolvidoEmPedidos"));
        assertEquals(0, r.get("estoqueDevolvidoEmItens"));
    }

    // ---------------------------------------------------------------- a previa
    @Test
    void aPREVIAdizQUANTOvaiVOLTARantesDAconfirmacao() {
        // Estoque e contagem fisica: quem limpa tem de saber o que muda na prateleira ANTES de
        // apertar o botao, nao depois.
        comPedidos(pedido(1L, StatusPedido.CANCELADO), pedido(2L, StatusPedido.ENTREGUE),
                pedido(3L, StatusPedido.RECEBIDO));

        Map<String, Object> p = service.previa(LOJA);

        assertEquals(2L, p.get("pedidosQueDevolvemEstoque"));
        assertEquals(1L, p.get("pedidosJaCanceladosQueNaoDevolvem"));
    }

    @Test
    void aPREVIAnaoMEXEemNADA() {
        comPedidos(pedido(1L, StatusPedido.ENTREGUE));

        service.previa(LOJA);

        verify(estorno, never()).devolverSoOestoque(any());
        verify(itens, never()).deleteByLojaId(anyLong());
        verify(pedidos, never()).deleteByLojaId(anyLong());
    }

    // ---------------------------------------------------------------- o que continua valendo
    @Test
    void oNOMEerradoNAOdevolveNADAnemAPAGA() {
        comPedidos(pedido(1L, StatusPedido.ENTREGUE));

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.limpar(LOJA, "Zira Zona Norte", false));

        verify(estorno, never()).devolverSoOestoque(any());
        verify(pedidos, never()).deleteByLojaId(anyLong());
    }

    @Test
    void oCARDAPIOcontinuaDEpe() {
        comPedidos(pedido(1L, StatusPedido.ENTREGUE));
        when(produtos.countByLojaId(LOJA)).thenReturn(33L);

        assertEquals(33L, limpar().get("produtosQueFicaram"));
        verify(produtos, never()).deleteById(anyLong());
    }

    // ---------------------------------------------------------------- o estorno sem cashback
    @Test
    void aLIMPEZAnaoMEXEnoCASHBACKpeloCAMINHOdoCANCELAMENTO() {
        // O `devolver` estorna cashback; a limpeza nao pode usa-lo. Cashback e dinheiro que o cliente
        // final tem a receber, e quem decide sobre ele e a flag `zerarClientes` -- que vem desligada.
        comPedidos(pedido(1L, StatusPedido.ENTREGUE));

        limpar();

        verify(estorno, never()).devolver(any(), any(), anyBoolean());
        verify(clientes, never()).saveAll(any());
    }

    /** O estoque volta pelo mesmo serviço do cancelamento, e não por uma segunda cópia da regra. */
    @Test
    void aREGRAdoESTOQUEnaoGANHOUumaSEGUNDAcopia() {
        String fonte = assertDoesNotThrow(() -> java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/br/com/bora/service/LimpezaDeTestesService.java"),
                java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(fonte.contains("estorno.devolverSoOestoque("),
                "a limpeza tem de chamar o servico de estorno");
        for (String proibido : new String[] { "devolverEstoque(", "consumirFicha(", "devolverFicha(" }) {
            assertFalse(fonte.contains(proibido),
                    "a limpeza mexeu no estoque na mao (" + proibido + "): foi a divergencia entre "
                            + "copias da regra que criou os defeitos de estoque deste mes");
        }
    }
}
