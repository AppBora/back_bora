package br.com.bora.service;

import br.com.bora.entity.Insumo;
import br.com.bora.repository.ComplementoItemRepository;
import br.com.bora.repository.InsumoRepository;
import br.com.bora.repository.ProdutoInsumoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.service.ComplementoService.Escolhido;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O adicional baixa estoque pelo insumo que ele consome.
 *
 * <h2>O item 2.6 da varredura, e por que ele não era bug</h2>
 * <p>{@code complemento_item} tinha só {@code (id, loja_id, grupo_id, nome, preco)}: sem campo de
 * estoque e sem ligação com insumo. Numa açaiteria metade do valor da venda está nos adicionais — a
 * granola e o leite em pó saíam sem nenhum registro, e a margem de cada venda aparecia melhor do que
 * era. Não era um {@code if} esquecido: o modelo não comportava, então era <b>decisão de produto</b>.
 * O dono decidiu em 21/10/2026: ligar o adicional ao insumo, e não dar estoque próprio ao adicional.</p>
 *
 * <p>O motivo da escolha importa para quem mexer nisto depois: a mesma granola está na ficha técnica
 * dos produtos <b>e</b> nos adicionais. Dois lugares para contar a mesma granola nunca fecham entre
 * si. Pelo insumo, o consumo reaproveita o caminho que já existe, já é atômico no banco e já recusa
 * venda sem estoque.</p>
 */
class AdicionalBaixaEstoqueTest {

    private InsumoRepository insumos;
    private InsumoService service;

    private static final Long LOJA = 7L;

    @BeforeEach
    void montar() {
        insumos = mock(InsumoRepository.class);
        service = new InsumoService(insumos, mock(ProdutoInsumoRepository.class),
                mock(ProdutoRepository.class), mock(ComplementoItemRepository.class),
                mock(AuthContext.class));
    }

    /** Um insumo que existe, com custo e saldo. */
    private Insumo insumo(long id, String nome, String custo, String estoque) {
        Insumo i = new Insumo();
        i.id = id;
        i.lojaId = LOJA;
        i.nome = nome;
        i.custo = new BigDecimal(custo);
        i.estoque = estoque == null ? null : new BigDecimal(estoque);
        when(insumos.findByIdAndLojaId(id, LOJA)).thenReturn(Optional.of(i));
        return i;
    }

    private Escolhido adicional(String nome, Long insumoId, String porUnidade) {
        return new Escolhido(99L, nome, new BigDecimal("3.00"), insumoId,
                porUnidade == null ? null : new BigDecimal(porUnidade));
    }

    /** O banco tem saldo: o consumo condicional passa. */
    private void bancoTemSaldo() {
        when(insumos.consumirSeTiver(anyLong(), anyLong(), any())).thenReturn(1);
    }

    /** O banco NAO tem saldo: o consumo condicional nao mexe em linha nenhuma. */
    private void bancoSemSaldo() {
        when(insumos.consumirSeTiver(anyLong(), anyLong(), any())).thenReturn(0);
    }

    // =============================================================== o consumo

    @Test
    void adicionalCOMinsumoBAIXAoEstoqueDELE() {
        insumo(50L, "Granola", "20.00", "5.000");

        service.consumirAdicionais(LOJA, List.of(adicional("Granola", 50L, "0.030")), 1, false);

        verify(insumos).mexerNoEstoque(50L, LOJA, new BigDecimal("-0.030"));
    }

    @Test
    void aQUANTIDADEdoITEMmultiplica() {
        // Tres acais com granola levam tres vezes a granola. Errar isto faz o estoque derreter
        // devagar, que e o jeito mais caro de errar -- ninguem percebe por semanas.
        insumo(50L, "Granola", "20.00", "5.000");

        service.consumirAdicionais(LOJA, List.of(adicional("Granola", 50L, "0.030")), 3, false);

        verify(insumos).mexerNoEstoque(50L, LOJA, new BigDecimal("-0.090"));
    }

    @Test
    void oCUSTOvoltaPORunidade_naoPeloPedidoTodo() {
        // Quem chama grava isto em `custoUnitario`, que e multiplicado pela quantidade depois. Voltar
        // o custo do pedido inteiro aqui inflaria o CMV ao quadrado.
        insumo(50L, "Granola", "20.00", "5.000");

        BigDecimal custo = service.consumirAdicionais(
                LOJA, List.of(adicional("Granola", 50L, "0.030")), 4, false);

        assertEquals(0, custo.compareTo(new BigDecimal("0.60")), "0,030 x R$ 20,00; a quantidade nao entra");
    }

    @Test
    void VARIOSadicionaisSOMAMoCUSTOeBAIXAMcadaINSUMO() {
        insumo(50L, "Granola", "20.00", "5.000");
        insumo(51L, "Leite Ninho", "40.00", "5.000");

        BigDecimal custo = service.consumirAdicionais(LOJA,
                List.of(adicional("Granola", 50L, "0.030"), adicional("Leite Ninho", 51L, "0.020")),
                1, false);

        assertEquals(0, custo.compareTo(new BigDecimal("1.40")), "0,60 + 0,80");
        verify(insumos).mexerNoEstoque(50L, LOJA, new BigDecimal("-0.030"));
        verify(insumos).mexerNoEstoque(51L, LOJA, new BigDecimal("-0.020"));
    }

    // =============================================================== quem NAO consome

    @Test
    void adicionalSEMinsumoNAObaixaNADAeNAOcustaNADA() {
        // E o padrao, e e todo adicional cadastrado antes da V55. O recurso novo nao pode mudar o
        // comportamento de quem nao usa ele.
        BigDecimal custo = service.consumirAdicionais(
                LOJA, List.of(adicional("Borda recheada", null, "0.030")), 2, false);

        assertEquals(0, custo.signum());
        verify(insumos, never()).mexerNoEstoque(anyLong(), anyLong(), any());
        verify(insumos, never()).consumirSeTiver(anyLong(), anyLong(), any());
    }

    @Test
    void insumoLIGADOmasSEMquantidadeNAObaixaNADA() {
        // Os dois campos andam juntos. Com quantidade nula ou zero nao ha o que tirar, e inventar
        // "um" seria pior: a unidade do insumo pode ser quilo.
        insumo(50L, "Granola", "20.00", "5.000");

        service.consumirAdicionais(LOJA, List.of(
                adicional("Granola", 50L, null), adicional("Granola", 50L, "0")), 1, false);

        verify(insumos, never()).mexerNoEstoque(anyLong(), anyLong(), any());
    }

    @Test
    void listaVAZIAouNULAnaoEstoura() {
        assertEquals(0, service.consumirAdicionais(LOJA, List.of(), 1, false).signum());
        assertEquals(0, service.consumirAdicionais(LOJA, null, 1, false).signum());
    }

    @Test
    void quantidadeZEROouNEGATIVAdoITEMnaoBaixaNada() {
        insumo(50L, "Granola", "20.00", "5.000");

        service.consumirAdicionais(LOJA, List.of(adicional("Granola", 50L, "0.030")), 0, false);
        service.consumirAdicionais(LOJA, List.of(adicional("Granola", 50L, "0.030")), -2, false);

        verify(insumos, never()).mexerNoEstoque(anyLong(), anyLong(), any());
    }

    @Test
    void insumoAPAGADOnaoEstouraEnaoBaixaNada() {
        // Nao deveria acontecer: a V55 tem chave estrangeira e o `excluir` recusa. Mas dado de antes
        // da V55, ou mexida no banco na mao, nao pode derrubar uma venda no meio.
        when(insumos.findByIdAndLojaId(77L, LOJA)).thenReturn(Optional.empty());

        BigDecimal custo = assertDoesNotThrow(() -> service.consumirAdicionais(
                LOJA, List.of(adicional("Granola", 77L, "0.030")), 1, false));

        assertEquals(0, custo.signum());
        verify(insumos, never()).mexerNoEstoque(anyLong(), anyLong(), any());
    }

    // =============================================================== recusar ou deixar passar

    @Test
    void comEXIGIRestoque_oBANCOrecusaEoMETODOestoura() {
        insumo(50L, "Granola", "20.00", "0.010");
        bancoSemSaldo();

        var erro = assertThrows(InsumoService.SemAdicional.class, () -> service.consumirAdicionais(
                LOJA, List.of(adicional("Granola", 50L, "0.030")), 1, true));

        assertEquals("Granola", erro.adicional, "o nome do ADICIONAL, para o cliente");
        assertEquals("Granola", erro.insumo, "e o nome do INSUMO, para o lojista");
    }

    @Test
    void aRECUSAtrazOSDOISnomes_queVAOparaLEITORESdiferentes() {
        // O adicional na tela pode se chamar "Granola crocante" e o insumo "Granola Mae Terra 1kg".
        // O cliente nao entende o segundo, e o lojista nao repoe pelo primeiro.
        insumo(50L, "Granola Mae Terra 1kg", "20.00", "0.000");
        bancoSemSaldo();

        var erro = assertThrows(InsumoService.SemAdicional.class, () -> service.consumirAdicionais(
                LOJA, List.of(adicional("Granola crocante", 50L, "0.030")), 1, true));

        assertEquals("Granola crocante", erro.adicional);
        assertEquals("Granola Mae Terra 1kg", erro.insumo);
    }

    @Test
    void quemRECUSAehOBANCO_naoUmaConferenciaANTES() {
        // Duas vendas ao mesmo tempo leriam o mesmo saldo e as duas passariam. Por isso o caminho que
        // recusa usa `consumirSeTiver` (um UPDATE condicional) e NUNCA le o estoque para decidir.
        insumo(50L, "Granola", "20.00", "5.000");
        bancoTemSaldo();

        service.consumirAdicionais(LOJA, List.of(adicional("Granola", 50L, "0.030")), 1, true);

        verify(insumos).consumirSeTiver(50L, LOJA, new BigDecimal("0.030"));
        verify(insumos, never()).mexerNoEstoque(anyLong(), anyLong(), any());
    }

    @Test
    void SEMexigirEstoque_aVENDApassaMESMOsemSALDO() {
        // E o balcao: o operador esta com o copo na mao. Travar a venda com o cliente na frente e
        // pior que o saldo negativo, que pelo menos denuncia que a contagem esta errada.
        insumo(50L, "Granola", "20.00", "0.010");

        assertDoesNotThrow(() -> service.consumirAdicionais(
                LOJA, List.of(adicional("Granola", 50L, "0.030")), 1, false));

        verify(insumos).mexerNoEstoque(50L, LOJA, new BigDecimal("-0.030"));
    }

    @Test
    void insumoQUEnaoCONTROLAestoqueNAOquebraNada() {
        // Insumo com estoque nulo nao controla saldo. O aviso de negativo nao pode estourar nele.
        insumo(50L, "Granola", "20.00", null);

        assertDoesNotThrow(() -> service.consumirAdicionais(
                LOJA, List.of(adicional("Granola", 50L, "0.030")), 1, false));

        verify(insumos).mexerNoEstoque(50L, LOJA, new BigDecimal("-0.030"));
    }

    // =============================================================== a devolucao e o espelho

    @Test
    void devolverEHoESPELHOexato_mesmaContaSinalTrocado() {
        insumo(50L, "Granola", "20.00", "5.000");

        int devolvidos = service.devolverAdicionais(
                LOJA, List.of(adicional("Granola", 50L, "0.030")), 3);

        assertEquals(1, devolvidos);
        verify(insumos).mexerNoEstoque(50L, LOJA, new BigDecimal("0.090"));
    }

    @Test
    void devolverNUNCArecusa() {
        // Cancelar nao pode travar. Devolver nunca passa pelo caminho condicional.
        insumo(50L, "Granola", "20.00", "5.000");
        bancoSemSaldo();

        assertDoesNotThrow(() -> service.devolverAdicionais(
                LOJA, List.of(adicional("Granola", 50L, "0.030")), 1));

        verify(insumos, never()).consumirSeTiver(anyLong(), anyLong(), any());
    }

    @Test
    void devolverADICIONALsemINSUMOnaoDevolveNada() {
        assertEquals(0, service.devolverAdicionais(
                LOJA, List.of(adicional("Borda recheada", null, "0.030")), 1));
        assertEquals(0, service.devolverAdicionais(LOJA, null, 1));
        verify(insumos, never()).mexerNoEstoque(anyLong(), anyLong(), any());
    }

    @Test
    void oQUEsaiEHoQUEvolta_consumirEdevolverSEanulam() {
        // A prova que importa para o estoque: vender e cancelar deixa o saldo como estava. Se esta
        // conta nao fechar, cada checkout abandonado no cardapio come granola de uma venda que nunca
        // existiu -- com o PIX expirando sozinho em 30 minutos, isso vira rotina.
        insumo(50L, "Granola", "20.00", "5.000");
        var escolhidos = List.of(adicional("Granola", 50L, "0.030"));

        service.consumirAdicionais(LOJA, escolhidos, 4, false);
        service.devolverAdicionais(LOJA, escolhidos, 4);

        var saiu = org.mockito.ArgumentCaptor.forClass(BigDecimal.class);
        verify(insumos, times(2)).mexerNoEstoque(eq(50L), eq(LOJA), saiu.capture());
        assertEquals(0, saiu.getAllValues().get(0).add(saiu.getAllValues().get(1)).signum(),
                "as duas mexidas tem que somar zero, e foram: " + saiu.getAllValues());
    }
}
