package br.com.bora.service;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.service.ComplementoService.Escolhido;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Cancelar devolve o insumo do adicional, com o que foi gravado no item — não com o cadastro de hoje.
 *
 * <h2>Por que o cadastro de hoje não serve</h2>
 * <p>{@code ComplementoController.salvar} apaga e recria TODOS os adicionais do produto a cada
 * gravação, com ids novos da sequência. Se o cancelamento relesse o insumo pelo id do adicional, uma
 * gravação do cadastro entre a venda e o cancelamento faria o id apontar para <b>outro</b> adicional
 * — e o estoque voltaria no insumo errado. Devolver granola na conta do leite em pó é pior que não
 * devolver: os dois números ficam errados e nada denuncia.</p>
 *
 * <p>Por isso o insumo e a quantidade vão gravados no item do pedido, junto do nome e do preço, que
 * já estavam lá pelo mesmo motivo.</p>
 *
 * <h2>Quando devolve, e quando não</h2>
 * <p>A mesma regra do insumo da ficha técnica: se a cozinha já começou, a granola foi para o copo.
 * Devolver inflaria o estoque com comida que foi para o lixo.</p>
 */
class AdicionalVoltaNoCancelamentoTest {

    private PedidoItemRepository itens;
    private InsumoService insumos;
    private EstornoDaVendaService servico;

    private static final Long LOJA = 4L;

    @BeforeEach
    void montar() {
        itens = mock(PedidoItemRepository.class);
        insumos = mock(InsumoService.class);
        servico = new EstornoDaVendaService(itens, mock(ProdutoRepository.class), insumos,
                mock(FidelidadeService.class));
    }

    private Pedido pedido() {
        Pedido p = new Pedido();
        p.id = 900L;
        p.lojaId = LOJA;
        return p;
    }

    /** Item com um adicional de granola gravado, que consome 0,030 do insumo 50. */
    private PedidoItem itemComGranola(int qtd, Boolean consumiuFicha) {
        PedidoItem it = new PedidoItem();
        it.setLojaId(LOJA);
        it.setPedidoId(900L);
        it.setProdutoId(10L);
        it.setQuantidade(qtd);
        it.setConsumiuFicha(consumiuFicha);
        it.setComplementos(ComplementosDoItem.escrever(List.of(
                new Escolhido(12L, "Granola", new BigDecimal("3.00"), 50L, new BigDecimal("0.030")))));
        return it;
    }

    private void comItens(PedidoItem... lista) {
        when(itens.findByLojaIdAndPedidoIdOrderById(LOJA, 900L)).thenReturn(List.of(lista));
    }

    @SuppressWarnings("unchecked")
    private List<Escolhido> devolvido() {
        var cap = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(insumos).devolverAdicionais(eq(LOJA), cap.capture(), anyInt());
        return cap.getValue();
    }

    // =============================================================== devolve

    @Test
    void cancelarANTESdoPREPARO_devolveOinsumoDOadicional() {
        comItens(itemComGranola(2, Boolean.TRUE));

        servico.devolver(pedido(), StatusPedido.RECEBIDO, false);

        verify(insumos).devolverAdicionais(eq(LOJA), anyList(), eq(2));
        assertEquals(50L, devolvido().get(0).insumoId());
        assertEquals(0, devolvido().get(0).quantidade().compareTo(new BigDecimal("0.030")));
    }

    @Test
    void devolveOqueFOIGRAVADO_eNAOoCadastroDEhoje() {
        // O coracao desta classe. A lista que chega ao servico tem que ser a que veio do ITEM.
        comItens(itemComGranola(1, Boolean.TRUE));

        servico.devolver(pedido(), StatusPedido.CONFIRMADO, false);

        assertEquals("Granola", devolvido().get(0).nome(),
                "a lista tem que vir do item do pedido, nao de uma releitura do cadastro");
    }

    @Test
    void produtoSEMfichaTAMBEMdevolveOadicional() {
        // O adicional e independente do caminho do produto: um refrigerante sem ficha tecnica tambem
        // pode levar adicional. Se isto cair, metade dos pedidos deixa de devolver.
        comItens(itemComGranola(1, Boolean.FALSE));

        servico.devolver(pedido(), StatusPedido.RECEBIDO, false);

        verify(insumos).devolverAdicionais(eq(LOJA), anyList(), eq(1));
    }

    @Test
    void itemSEMaMARCAdoPRODUTOaindaDEVOLVEoADICIONAL() {
        // Item anterior a V45 nao registra COMO o produto baixou estoque, e por isso o produto nao e
        // devolvido. Mas o registro do adicional, quando existe, e informacao completa: diz o insumo
        // e a quantidade. Nao devolver seria jogar fora uma certeza.
        comItens(itemComGranola(1, null));

        servico.devolver(pedido(), StatusPedido.RECEBIDO, false);

        verify(insumos).devolverAdicionais(eq(LOJA), anyList(), eq(1));
    }

    // =============================================================== nao devolve

    @Test
    void depoisDoPREPARO_oADICIONALnaoVOLTA() {
        // A granola ja esta no copo. Devolver inflaria o estoque com comida que foi para o lixo -- e e
        // a mesma regra do insumo da ficha.
        comItens(itemComGranola(1, Boolean.TRUE));

        servico.devolver(pedido(), StatusPedido.EM_PREPARO, false);

        verify(insumos, never()).devolverAdicionais(anyLong(), anyList(), anyInt());
    }

    @Test
    void itemSEMregistroDEcomplementoNAOdevolveNADA() {
        // Item anterior a V46: nao se sabe o que foi escolhido. O `ler` devolve nulo e o servico de
        // insumo trata nulo como "nada a devolver" -- inventar seria pior.
        PedidoItem it = itemComGranola(1, Boolean.TRUE);
        it.setComplementos(null);
        comItens(it);

        servico.devolver(pedido(), StatusPedido.RECEBIDO, false);

        verify(insumos).devolverAdicionais(eq(LOJA), isNull(), eq(1));
    }

    // =============================================================== reabrir consome de novo

    @Test
    void pedidoREABERTOconsomeOadicionalDEnovo() {
        // PIX pago depois do cancelamento (item 2.7): o cancelamento devolveu a granola, o pedido
        // voltou a valer, entao ela tem que sair outra vez.
        comItens(itemComGranola(2, Boolean.TRUE));

        servico.rebaixarDeNovo(pedido());

        verify(insumos).consumirAdicionais(eq(LOJA), anyList(), eq(2), eq(false));
    }

    @Test
    void aoREABRIR_naoRECUSAporFaltaDeEstoque() {
        // A venda esta paga. O ultimo argumento tem que ser `false`: recusar aqui nao desfaz o
        // pagamento, so deixaria o estoque errado E o pedido sem baixa.
        comItens(itemComGranola(1, Boolean.TRUE));

        servico.rebaixarDeNovo(pedido());

        verify(insumos, never()).consumirAdicionais(anyLong(), anyList(), anyInt(), eq(true));
    }

    @Test
    void oQUEvoltaNOcancelamentoEHoQUEsaiNAreabertura() {
        // Cancelar e reabrir tem que fechar a conta. Se as duas listas divergirem, cada ida e volta
        // deixa um resto no estoque.
        comItens(itemComGranola(3, Boolean.TRUE));
        Pedido p = pedido();

        servico.devolver(p, StatusPedido.RECEBIDO, false);
        servico.rebaixarDeNovo(p);

        var voltou = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(insumos).devolverAdicionais(eq(LOJA), voltou.capture(), eq(3));
        var saiu = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(insumos).consumirAdicionais(eq(LOJA), saiu.capture(), eq(3), eq(false));

        assertEquals(voltou.getValue(), saiu.getValue(),
                "a lista devolvida e a reconsumida tem que ser a MESMA");
    }
}
