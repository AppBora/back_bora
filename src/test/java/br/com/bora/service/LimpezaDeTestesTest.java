package br.com.bora.service;

import br.com.bora.entity.Cliente;
import br.com.bora.entity.Loja;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Limpar a movimentação de teste de uma loja.
 *
 * <p><b>Isto é código que APAGA dado de cliente pagante em produção.</b> Então a maior parte dos
 * testes aqui não prova o que ele apaga — prova o que ele <b>não</b> apaga, e o que ele recusa a
 * fazer. Produto, insumo, integração, assinatura e usuário não podem sair nunca: a Zirá tem 33
 * produtos cadastrados e 12 pedidos de teste, e apagar o cardápio junto deixaria a loja sem nada
 * para vender numa sexta à noite.</p>
 */
class LimpezaDeTestesTest {

    private static final Long LOJA = 18L;
    private static final String NOME = "Zirá Açaíteria";

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
        ctx = mock(AuthContext.class);

        Loja l = new Loja();
        l.setId(LOJA);
        l.setNome(NOME);
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));
        when(pedidos.countByLojaId(LOJA)).thenReturn(12L);
        when(itens.countByLojaId(LOJA)).thenReturn(12L);
        when(logs.countByLojaId(LOJA)).thenReturn(21L);
        when(acertos.countByLojaId(LOJA)).thenReturn(0L);
        when(clientes.countByLojaId(LOJA)).thenReturn(0L);
        when(produtos.countByLojaId(LOJA)).thenReturn(33L);
        when(clientes.findByLojaId(LOJA)).thenReturn(List.of());
        when(ctx.atual()).thenReturn(new BoraPrincipal(1L, null, "ADMINISTRADOR_BORA", "dono@bora.com"));

        // Desde 26/10/2026 a limpeza devolve o estoque dos pedidos de teste antes de apagar: pedido
        // de mentira baixou produto ou insumo, e apagar o pedido nao desfazia isso.
        estorno = mock(EstornoDaVendaService.class);
        when(pedidos.findByLojaIdOrderByCriadoEmDesc(LOJA)).thenReturn(List.of());
        service = new LimpezaDeTestesService(lojas, pedidos, itens, logs, clientes, acertos, produtos,
                estorno, ctx);
    }

    // ---------------------------------------------------------------- quem pode

    @Test
    void soOadministradorDAPLATAFORMAlimpa() {
        // O administrador da LOJA nao tem este gatilho de proposito: o estrago de um clique errado e
        // o historico de vendas do proprio negocio dele.
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(ctx).requireAdminBora();

        assertThrows(ResponseStatusException.class, () -> service.limpar(LOJA, NOME, false));
        assertThrows(ResponseStatusException.class, () -> service.previa(LOJA));
        verify(pedidos, never()).deleteByLojaId(any());
    }

    // ---------------------------------------------------------------- a trava do nome

    @Test
    void semOnomeEXATOnaoApagaNADA() {
        for (String errado : new String[]{null, "", "   ", "Zira", "Zirá Centro", "zirá açaíteria x"}) {
            assertThrows(ResponseStatusException.class, () -> service.limpar(LOJA, errado, false),
                    "deveria recusar: " + errado);
        }
        verify(pedidos, never()).deleteByLojaId(any());
        verify(itens, never()).deleteByLojaId(any());
        verify(logs, never()).deleteByLojaId(any());
    }

    @Test
    void oNomeCERTOpassa_comEspacoSobrandoEcaixaDiferente() {
        service.limpar(LOJA, "  zirá açaíteria  ", false);

        verify(pedidos).deleteByLojaId(LOJA);
    }

    @Test
    void aMensagemDErroDIZqualNomeDigitar() {
        var e = assertThrows(ResponseStatusException.class, () -> service.limpar(LOJA, "errado", false));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains(NOME), e.getReason());
    }

    // ---------------------------------------------------------------- o que sai

    @Test
    void apagaMovimentacaoNAORDEMcerta() {
        service.limpar(LOJA, NOME, false);

        // Filho antes do pai: item e historico apontam para o pedido.
        var ordem = inOrder(itens, logs, acertos, pedidos);
        ordem.verify(itens).deleteByLojaId(LOJA);
        ordem.verify(logs).deleteByLojaId(LOJA);
        ordem.verify(acertos).deleteByLojaId(LOJA);
        ordem.verify(pedidos).deleteByLojaId(LOJA);
    }

    @Test
    void sempreCOMolojaIdNAcondicao() {
        // O risco real nao e apagar demais nesta loja: e apagar a loja errada.
        service.limpar(LOJA, NOME, false);

        verify(pedidos).deleteByLojaId(LOJA);
        verify(itens).deleteByLojaId(LOJA);
        verify(logs).deleteByLojaId(LOJA);
        verify(acertos).deleteByLojaId(LOJA);
        // Nenhum apagar-tudo sem condicao de loja em lugar nenhum.
        verify(pedidos, never()).deleteAll();
        verify(itens, never()).deleteAll();
        verify(logs, never()).deleteAll();
        verify(acertos, never()).deleteAll();
    }

    // ---------------------------------------------------------------- o que NAO sai

    @Test
    void oCARDAPIOnaoEncosta() {
        service.limpar(LOJA, NOME, false);

        // So leitura para contar; nenhum delete e nenhum save.
        verify(produtos, never()).deleteById(any());
        verify(produtos, never()).deleteAll();
        verify(produtos, never()).save(any());
    }

    @Test
    void aLOJAnaoEalteradaNEMapagada() {
        service.limpar(LOJA, NOME, false);

        // Assinatura, integracao, PIX e configuracao vivem pendurados na loja: se a loja nao e
        // gravada nem apagada, nada disso se mexe.
        verify(lojas, never()).save(any());
        verify(lojas, never()).deleteById(any());
    }

    @Test
    void semZerarClientes_oCASHBACKdelesFICA() {
        Cliente c = new Cliente();
        c.lojaId = LOJA;
        c.cashback = new BigDecimal("25.00");
        when(clientes.findByLojaId(LOJA)).thenReturn(List.of(c));

        service.limpar(LOJA, NOME, false);

        assertEquals(0, c.cashback.compareTo(new BigDecimal("25.00")),
                "cashback e dinheiro que o cliente final ja tem a receber; apagar o pedido nao "
                        + "devolve o acai que ele comprou");
        verify(clientes, never()).saveAll(anyList());
    }

    @Test
    void comZerarClientes_zeraOSQUATROcampos() {
        Cliente c = new Cliente();
        c.lojaId = LOJA;
        c.cashback = new BigDecimal("25.00");
        c.totalGasto = new BigDecimal("300.00");
        c.qtdPedidos = 7;
        c.ultimoPedido = java.time.OffsetDateTime.now();
        when(clientes.findByLojaId(LOJA)).thenReturn(List.of(c));

        Map<String, Object> r = service.limpar(LOJA, NOME, true);

        assertEquals(0, c.cashback.compareTo(BigDecimal.ZERO));
        assertEquals(0, c.totalGasto.compareTo(BigDecimal.ZERO));
        assertEquals(0, c.qtdPedidos);
        assertNull(c.ultimoPedido, "o 'ultimo pedido' apontava para um pedido que nao existe mais");
        assertEquals(1, r.get("clientesZerados"));
    }

    @Test
    void oCLIENTEnuncaEapagado_soZerado() {
        Cliente c = new Cliente();
        c.lojaId = LOJA;
        when(clientes.findByLojaId(LOJA)).thenReturn(List.of(c));

        service.limpar(LOJA, NOME, true);

        // Nome e telefone podem ser de gente de verdade. Zerar contador e uma coisa; apagar a pessoa
        // da base do lojista e outra.
        verify(clientes, never()).delete(any());
        verify(clientes, never()).deleteAll();
    }

    // ---------------------------------------------------------------- o que ele conta

    @Test
    void devolveQUANTOsaiuDEcadaLUGAR() {
        Map<String, Object> r = service.limpar(LOJA, NOME, false);

        assertEquals(NOME, r.get("loja"));
        assertEquals(12L, r.get("pedidos"));
        assertEquals(12L, r.get("itens"));
        assertEquals(21L, r.get("historico"));
        assertEquals(33L, r.get("produtosQueFicaram"), "a tela mostra isto para provar que o cardapio ficou");
    }

    @Test
    void aPREVIAnaoApagaNADA() {
        Map<String, Object> p = service.previa(LOJA);

        assertEquals(12L, p.get("pedidos"));
        assertEquals(33L, p.get("produtosQueFicam"));
        verify(pedidos, never()).deleteByLojaId(any());
        verify(itens, never()).deleteByLojaId(any());
        verify(logs, never()).deleteByLojaId(any());
        verify(acertos, never()).deleteByLojaId(any());
    }

    @Test
    void lojaQueNAOexisteDa404eNaoApaga() {
        when(lojas.findById(99L)).thenReturn(Optional.empty());

        var e = assertThrows(ResponseStatusException.class, () -> service.limpar(99L, "qualquer", false));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(pedidos, never()).deleteByLojaId(any());
    }

    @Test
    void lojaSEMnomeNaoViraPassePorNomeVAZIO() {
        // Loja com nome nulo + confirmacao vazia nao pode casar e liberar a limpeza por acidente.
        Loja semNome = new Loja();
        semNome.setId(77L);
        semNome.setNome(null);
        when(lojas.findById(77L)).thenReturn(Optional.of(semNome));

        assertThrows(ResponseStatusException.class, () -> service.limpar(77L, "", false));
        assertThrows(ResponseStatusException.class, () -> service.limpar(77L, null, false));
        verify(pedidos, never()).deleteByLojaId(any());
    }
}
