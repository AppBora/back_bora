package br.com.bora.service;

import br.com.bora.entity.Insumo;
import br.com.bora.entity.ProdutoInsumo;
import br.com.bora.repository.InsumoRepository;
import br.com.bora.repository.ProdutoInsumoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Vender sem ter: quem recusa, quem deixa passar, e por quê.
 *
 * <h2>O defeito que isto trava (medido em 20/10/2026)</h2>
 * <p>Nada conferia estoque antes de vender. A baixa só tinha {@code where estoque is not null}, então
 * o saldo ia a negativo sem limite — uma única chamada do cardápio podia levar um produto a −4950
 * (50 itens × 99).</p>
 *
 * <h2>A regra, e ela não é a mesma nos três canais</h2>
 * <p>O que decide é <b>quem está com o produto na mão</b>:</p>
 * <ul>
 *   <li><b>Cardápio público</b> — o cliente não tem nada na mão. <b>Recusa.</b> Vender e depois
 *       cancelar é pior que dizer "acabou" agora. E era por aqui que vinha o −4950.</li>
 *   <li><b>Balcão</b> — o operador está com o açaí na mão. <b>Deixa vender</b>, e avisa. Travar a
 *       venda com o cliente na frente é pior que o saldo negativo, que pelo menos denuncia que a
 *       contagem está errada.</li>
 *   <li><b>Marketplace</b> — o pedido já foi aceito no iFood. <b>Nunca recusa</b>, e avisa.</li>
 * </ul>
 *
 * <p>A trava de verdade é do banco ({@code baixarEstoqueSeTiver} / {@code consumirSeTiver}), provada
 * contra Postgres em {@link br.com.bora.repository.EstoqueNaoPerdeBaixaIT}. O que estes testes medem
 * é a <b>decisão de cada canal</b>: quem chama qual caminho.</p>
 */
class VendaSemEstoqueTest {

    private InsumoRepository insumos;
    private ProdutoInsumoRepository fichas;
    private InsumoService service;

    private static final Long LOJA = 1L;
    private static final Long PRODUTO = 11L;

    @BeforeEach
    void montar() {
        insumos = mock(InsumoRepository.class);
        fichas = mock(ProdutoInsumoRepository.class);
        service = new InsumoService(insumos, fichas, mock(ProdutoRepository.class), mock(br.com.bora.repository.ComplementoItemRepository.class), mock(AuthContext.class));
    }

    /** Uma ficha de um insumo só: cada unidade do produto consome {@code porUnidade}. */
    private Insumo comFicha(String nome, String porUnidade, String estoque) {
        Insumo in = new Insumo();
        in.id = 50L;
        in.lojaId = LOJA;
        in.nome = nome;
        in.custo = new BigDecimal("10.00");
        in.estoque = estoque == null ? null : new BigDecimal(estoque);
        ProdutoInsumo pi = new ProdutoInsumo();
        pi.lojaId = LOJA;
        pi.produtoId = PRODUTO;
        pi.insumoId = in.id;
        pi.quantidade = new BigDecimal(porUnidade);
        when(fichas.findByLojaIdAndProdutoId(LOJA, PRODUTO)).thenReturn(List.of(pi));
        when(insumos.findByIdAndLojaId(in.id, LOJA)).thenReturn(Optional.of(in));
        return in;
    }

    // ----------------------------------------------------------------- o cardápio recusa

    @Test
    void cardapio_insumoQueFALTA_recusaNomeandoOinsumo() {
        comFicha("Polpa de Acai", "0.500", "0.300");
        when(insumos.consumirSeTiver(eq(50L), eq(LOJA), any())).thenReturn(0);

        var erro = assertThrows(InsumoService.SemInsumo.class,
                () -> service.consumirFicha(LOJA, PRODUTO, 1, true));

        assertEquals("Polpa de Acai", erro.insumo,
                "o nome do insumo tem que vir, para o lojista ler no log");
    }

    @Test
    void cardapio_insumoQueTEM_consomePeloCaminhoComTrava() {
        comFicha("Polpa de Acai", "0.500", "10.000");
        when(insumos.consumirSeTiver(eq(50L), eq(LOJA), any())).thenReturn(1);

        BigDecimal custo = service.consumirFicha(LOJA, PRODUTO, 3, true);

        // 3 unidades x 0,500 = 1,500 kg
        verify(insumos).consumirSeTiver(50L, LOJA, new BigDecimal("1.500"));
        verify(insumos, never()).mexerNoEstoque(anyLong(), anyLong(), any());
        assertEquals(0, custo.compareTo(new BigDecimal("5.00")), "0,500 x R$ 10,00 = R$ 5,00");
    }

    // ----------------------------------------------------------------- o balcão deixa passar

    @Test
    void balcao_insumoQueFALTA_NAOrecusa() {
        // A diferença que importa. O operador está com o produto na mão.
        comFicha("Polpa de Acai", "0.500", "0.100");

        assertDoesNotThrow(() -> service.consumirFicha(LOJA, PRODUTO, 5));

        verify(insumos).mexerNoEstoque(50L, LOJA, new BigDecimal("2.500").negate());
        verify(insumos, never()).consumirSeTiver(anyLong(), anyLong(), any());
    }

    @Test
    void oModoAntigoContinuaSendoOpadrao() {
        // Quem chama consumirFicha(loja, produto, qtd) sem o quarto argumento não pode mudar de
        // comportamento: é o balcão, o marketplace e os testes que já existiam.
        comFicha("Polpa de Acai", "1.000", "0.000");

        assertDoesNotThrow(() -> service.consumirFicha(LOJA, PRODUTO, 1));

        verify(insumos, never()).consumirSeTiver(anyLong(), anyLong(), any());
    }

    // ----------------------------------------------------------------- produto sem ficha

    @Test
    void produtoSemFichaDevolveNuloNosDoisModos() {
        when(fichas.findByLojaIdAndProdutoId(LOJA, PRODUTO)).thenReturn(List.of());

        assertNull(service.consumirFicha(LOJA, PRODUTO, 1, true));
        assertNull(service.consumirFicha(LOJA, PRODUTO, 1, false));
        verify(insumos, never()).consumirSeTiver(anyLong(), anyLong(), any());
    }

    // ----------------------------------------------------------------- a foto para o menu

    @Test
    void fichaDaParaFazer_temInsumoDeSobra() {
        comFicha("Polpa de Acai", "0.500", "10.000");
        assertTrue(service.fichaDaParaFazer(LOJA, PRODUTO, 1));
    }

    @Test
    void fichaDaParaFazer_naoTemNemParaUM() {
        comFicha("Polpa de Acai", "0.500", "0.300");
        assertFalse(service.fichaDaParaFazer(LOJA, PRODUTO, 1),
                "0,300 kg nao faz um produto que precisa de 0,500");
    }

    @Test
    void fichaDaParaFazer_oLIMITEexato() {
        comFicha("Polpa de Acai", "0.500", "0.500");
        assertTrue(service.fichaDaParaFazer(LOJA, PRODUTO, 1),
                "da para fazer exatamente um: nao pode aparecer esgotado");
        assertFalse(service.fichaDaParaFazer(LOJA, PRODUTO, 2));
    }

    @Test
    void fichaDaParaFazer_insumoSemCONTROLEnaoEsgota() {
        // Insumo sem numero de estoque e insumo que o lojista escolheu nao controlar. Nao pode fazer
        // o produto aparecer esgotado no cardapio.
        comFicha("Polpa de Acai", "0.500", null);
        assertTrue(service.fichaDaParaFazer(LOJA, PRODUTO, 99));
    }

    @Test
    void fichaDaParaFazer_insumoEmZERO_APAGA_oProdutoDoCardapio() {
        // O caso da Zira, medido na producao em 24/10/2026: 42 dos 43 insumos com `estoque = 0`
        // (nao nulo). ZERO e "nao tem nenhum" e BLOQUEIA; NULO e "nao controla estoque" e nao
        // bloqueia -- e o teste logo acima prova o lado nulo.
        //
        // Por que isto importa para a operacao: enquanto nao existe ficha, o zero e inofensivo. No
        // minuto em que a ficha e salva, todo produto que use um insumo zerado passa a sair
        // `esgotado: true` no cardapio publico. Montar as fichas antes de lancar a contagem apaga o
        // cardapio inteiro para o cliente final, sem erro nenhum na tela do lojista.
        comFicha("Polpa de Acai", "0.500", "0.000");

        assertFalse(service.fichaDaParaFazer(LOJA, PRODUTO, 1),
                "insumo em ZERO tem de bloquear: e a diferenca entre 'nao tem' e 'nao controla'");
    }

    @Test
    void fichaDaParaFazer_insumoAPAGADOnaoEsgota() {
        // Ficha fantasma: o lojista apagou o insumo e a linha da ficha ficou. O consumo tambem pula
        // essa linha, entao o cardapio nao pode fingir que falta. E outro problema (varredura 2.5).
        comFicha("Polpa de Acai", "0.500", "0.000");
        when(insumos.findByIdAndLojaId(50L, LOJA)).thenReturn(Optional.empty());

        assertTrue(service.fichaDaParaFazer(LOJA, PRODUTO, 1));
    }

    @Test
    void fichaDaParaFazer_produtoSemFichaNuncaEsgotaPelaFicha() {
        when(fichas.findByLojaIdAndProdutoId(LOJA, PRODUTO)).thenReturn(List.of());
        assertTrue(service.fichaDaParaFazer(LOJA, PRODUTO, 50));
    }

    @Test
    void fichaDaParaFazer_quantidadeZeradaNaFichaNaoEsgota() {
        // Linha de ficha com quantidade 0 (ou negativa, que nao deveria existir): nao consome nada,
        // logo nao pode esgotar nada.
        comFicha("Polpa de Acai", "0.000", "0.000");
        assertTrue(service.fichaDaParaFazer(LOJA, PRODUTO, 10));
    }
}
