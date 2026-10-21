package br.com.bora.service;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.ProdutoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pedido que volta a valer tem o estoque baixado de novo.
 *
 * <h2>O defeito (varredura 2.7) e por que o conserto óbvio estava errado</h2>
 * <p>O PIX do cardápio expira em 30 minutos e o pedido é cancelado — e o cancelamento devolve o
 * estoque. Se o dinheiro entrar depois, o caminho reconsumia o cashback de propósito (o cliente pagou
 * o valor já descontado) mas <b>não refazia a baixa de estoque</b>. A comida sairia e o estoque
 * seguiria contando.</p>
 *
 * <p><b>Medir mostrou que "rebaixar o estoque" sozinho estaria errado.</b> O pedido continuava
 * CANCELADO, e {@code alterarStatus} recusa sair de estado final: o lojista que quisesse preparar
 * teria que lançar <b>outro</b> pedido — que baixa o estoque por conta própria. Rebaixar aqui tiraria
 * em dobro.</p>
 *
 * <p>E o dano era maior que o estoque: <b>o cliente pagava e a cozinha nunca via o pedido</b>. A
 * única pista ficava num texto na aba de cancelados.</p>
 *
 * <p>Por isso o pedido <b>reabre</b>: volta a RECEBIDO, aparece no quadro, o estoque sai aqui, e se o
 * lojista não puder atender ele cancela de novo — e o cancelamento devolve estoque e cashback pelos
 * caminhos que já existem. Nunca aconteceu na produção (medido: zero pedidos com "pago APÓS o
 * cancelamento"), então é preventivo.</p>
 *
 * <p>Esta classe prova o {@code rebaixarDeNovo}: ele é o espelho exato do {@code devolver}, e mora no
 * mesmo serviço porque a regra "produto com ficha consome insumo, produto sem ficha baixa o próprio
 * estoque" existe num lugar só.</p>
 */
class PedidoPagoDepoisDoCancelamentoReabreTest {

    private PedidoItemRepository itens;
    private ProdutoRepository produtos;
    private InsumoService insumos;
    private EstornoDaVendaService servico;

    private static final Long LOJA = 18L;

    @BeforeEach
    void montar() {
        itens = mock(PedidoItemRepository.class);
        produtos = mock(ProdutoRepository.class);
        insumos = mock(InsumoService.class);
        servico = new EstornoDaVendaService(itens, produtos, insumos, mock(FidelidadeService.class));
    }

    private Pedido pedido() {
        Pedido p = new Pedido();
        p.id = 500L;
        p.lojaId = LOJA;
        return p;
    }

    private PedidoItem item(Long produtoId, int qtd, Boolean consumiuFicha) {
        PedidoItem it = new PedidoItem();
        it.setLojaId(LOJA);
        it.setPedidoId(500L);
        it.setProdutoId(produtoId);
        it.setQuantidade(qtd);
        it.setConsumiuFicha(consumiuFicha);
        return it;
    }

    private void comItens(PedidoItem... lista) {
        when(itens.findByLojaIdAndPedidoIdOrderById(LOJA, 500L)).thenReturn(List.of(lista));
    }

    // =============================================================== o conserto

    @Test
    void produtoSEMfichaTemOestoqueBAIXADOdeNovo() {
        comItens(item(11L, 3, Boolean.FALSE));
        when(produtos.baixarEstoque(11L, LOJA, 3)).thenReturn(1);

        assertEquals(1, servico.rebaixarDeNovo(pedido()));

        verify(produtos).baixarEstoque(11L, LOJA, 3);
    }

    @Test
    void produtoCOMfichaCONSOMEosInsumosDeNovo() {
        comItens(item(11L, 2, Boolean.TRUE));

        assertEquals(1, servico.rebaixarDeNovo(pedido()));

        verify(insumos).consumirFicha(LOJA, 11L, 2);
        verify(produtos, never()).baixarEstoque(anyLong(), anyLong(), anyInt());
    }

    @Test
    void NAOrecusaPorFaltaDeEstoque() {
        // A venda esta PAGA. Recusar aqui nao desfaz o pagamento -- deixaria o estoque errado E o
        // pedido sem baixa. E o mesmo motivo do balcao: saldo negativo e informacao de que a contagem
        // esta errada, nao motivo para travar.
        comItens(item(11L, 5, Boolean.TRUE));

        assertDoesNotThrow(() -> servico.rebaixarDeNovo(pedido()));

        verify(insumos).consumirFicha(LOJA, 11L, 5);
        // a sobrecarga de 3 argumentos e a que NAO recusa
        verify(insumos, never()).consumirFicha(anyLong(), anyLong(), anyInt(), anyBoolean());
    }

    @Test
    void itemSEMaMARCAnaoBaixaNADA() {
        // Igual ao devolver: se nao se sabe COMO saiu, nao se sabe como tirar de novo. Inventar baixa
        // e tao ruim quanto inventar devolucao -- e e silencioso.
        comItens(item(11L, 2, null));

        assertEquals(0, servico.rebaixarDeNovo(pedido()));

        verify(produtos, never()).baixarEstoque(anyLong(), anyLong(), anyInt());
        verify(insumos, never()).consumirFicha(anyLong(), anyLong(), anyInt());
    }

    @Test
    void itemSEMprodutoNaoBaixaNada() {
        // Item de marketplace que nao casou com o cadastro (ver v119).
        comItens(item(null, 2, Boolean.FALSE));

        assertEquals(0, servico.rebaixarDeNovo(pedido()));

        verify(produtos, never()).baixarEstoque(anyLong(), anyLong(), anyInt());
    }

    @Test
    void quantidadeZEROouNEGATIVAnaoBaixaNada() {
        comItens(item(11L, 0, Boolean.FALSE), item(12L, -1, Boolean.FALSE));

        assertEquals(0, servico.rebaixarDeNovo(pedido()));

        verify(produtos, never()).baixarEstoque(anyLong(), anyLong(), anyInt());
    }

    @Test
    void pedidoCOMvariosITENSbaixaCadaUmPeloSeuCaminho() {
        comItens(item(11L, 1, Boolean.TRUE), item(12L, 2, Boolean.FALSE), item(13L, 3, null));
        when(produtos.baixarEstoque(12L, LOJA, 2)).thenReturn(1);

        assertEquals(2, servico.rebaixarDeNovo(pedido()), "o sem marca nao conta");

        verify(insumos).consumirFicha(LOJA, 11L, 1);
        verify(produtos).baixarEstoque(12L, LOJA, 2);
        verify(produtos, never()).baixarEstoque(eq(13L), anyLong(), anyInt());
    }

    @Test
    void pedidoNULOouSEMidNaoEstoura() {
        assertEquals(0, servico.rebaixarDeNovo(null));
        Pedido semId = new Pedido();
        semId.lojaId = LOJA;
        assertEquals(0, servico.rebaixarDeNovo(semId));
    }

    @Test
    void pedidoSEMitensDevolveZERO() {
        comItens();

        assertEquals(0, servico.rebaixarDeNovo(pedido()));
    }

    // =============================================================== o espelho

    @Test
    void ehOespelhoDoDEVOLVER_mesmaRegraDeProdutoVersusFicha() {
        // O devolver usa consumiuFicha para escolher entre insumo e produto; o rebaixarDeNovo tem que
        // usar a MESMA marca, senao cancelar e reabrir deixa de fechar a conta.
        String fonte = assertDoesNotThrow(() -> java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/br/com/bora/service/EstornoDaVendaService.java"),
                java.nio.charset.StandardCharsets.UTF_8));

        int iDevolver = fonte.indexOf("public int devolver(");
        int iRebaixar = fonte.indexOf("public int rebaixarDeNovo(");
        assertTrue(iDevolver > 0 && iRebaixar > 0, "os dois metodos tem que existir no mesmo servico");

        String rebaixar = fonte.substring(iRebaixar, fonte.indexOf("\n    }", iRebaixar));
        assertTrue(rebaixar.contains("getConsumiuFicha()"),
                "o rebaixarDeNovo tem que ler a marca do item, nao adivinhar pela ficha de hoje");
    }
}
