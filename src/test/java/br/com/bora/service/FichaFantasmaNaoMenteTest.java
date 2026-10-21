package br.com.bora.service;

import br.com.bora.entity.Insumo;
import br.com.bora.entity.Produto;
import br.com.bora.entity.ProdutoInsumo;
import br.com.bora.repository.InsumoRepository;
import br.com.bora.repository.ProdutoInsumoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Insumo apagado não deixa a ficha mentindo.
 *
 * <h2>O defeito (varredura 2.5, medido em 21/10/2026)</h2>
 * <p>{@code excluir} apagava só o insumo. A tabela {@code produto_insumo} não tem chave estrangeira,
 * então as linhas da ficha ficavam apontando para um insumo que não existe — <b>ficha fantasma</b>.</p>
 *
 * <p>Depois, no {@code consumirFicha}, a linha fantasma era pulada e o método devolvia
 * {@code BigDecimal.ZERO}. E como os dois canais fazem {@code if (custoFicha == null) baixarEstoque},
 * zero não é null: <b>a baixa do produto não rodava</b> — e o item era gravado com
 * {@code consumiuFicha = true}, uma mentira. No cancelamento, essa marca manda devolver insumo, que
 * também não existe. <b>O estoque vaza nos dois sentidos, calado.</b></p>
 *
 * <p><b>Conferido na produção:</b> existe uma ficha fantasma hoje — o produto "Acai 500ml Atualizado"
 * tem linha de ficha para o insumo 2, que não existe (1 das 3 linhas de ficha da base).</p>
 */
class FichaFantasmaNaoMenteTest {

    private InsumoRepository insumos;
    private ProdutoInsumoRepository fichas;
    private ProdutoRepository produtos;
    private AuthContext ctx;
    private InsumoService service;

    private static final Long LOJA = 1L;
    private static final Long PRODUTO = 11L;

    @BeforeEach
    void montar() {
        insumos = mock(InsumoRepository.class);
        fichas = mock(ProdutoInsumoRepository.class);
        produtos = mock(ProdutoRepository.class);
        ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        service = new InsumoService(insumos, fichas, produtos, ctx);
    }

    private Insumo insumo(long id, String nome) {
        Insumo i = new Insumo();
        i.id = id;
        i.lojaId = LOJA;
        i.nome = nome;
        i.custo = new BigDecimal("10.00");
        i.estoque = new BigDecimal("100.000");
        when(insumos.findByIdAndLojaId(id, LOJA)).thenReturn(Optional.of(i));
        return i;
    }

    private ProdutoInsumo linhaDeFicha(long insumoId, String porUnidade) {
        ProdutoInsumo pi = new ProdutoInsumo();
        pi.lojaId = LOJA;
        pi.produtoId = PRODUTO;
        pi.insumoId = insumoId;
        pi.quantidade = new BigDecimal(porUnidade);
        return pi;
    }

    // =============================================================== o excluir recusa

    @Test
    void excluirInsumoQueESTAnumaFicha_ehRECUSADO() {
        Insumo polpa = insumo(50L, "Polpa de Acai");
        Produto acai = new Produto();
        acai.id = PRODUTO;
        acai.nome = "Açaí 500ml";
        when(produtos.findByIdAndLojaId(PRODUTO, LOJA)).thenReturn(Optional.of(acai));
        when(fichas.findByLojaIdAndInsumoId(LOJA, 50L)).thenReturn(List.of(linhaDeFicha(50L, "0.500")));

        var erro = assertThrows(ResponseStatusException.class, () -> service.excluir(50L));

        assertEquals(HttpStatus.CONFLICT, erro.getStatusCode());
        verify(insumos, never()).delete(any(Insumo.class));
        assertEquals("Polpa de Acai", polpa.nome);
    }

    @Test
    void aRECUSAdizEMQUAISprodutosOinsumoEsta() {
        // E a informacao que falta para decidir. Sem ela o lojista fica adivinhando qual receita
        // mexer.
        insumo(50L, "Polpa de Acai");
        Produto acai = new Produto();
        acai.id = PRODUTO;
        acai.nome = "Açaí 500ml";
        when(produtos.findByIdAndLojaId(PRODUTO, LOJA)).thenReturn(Optional.of(acai));
        when(fichas.findByLojaIdAndInsumoId(LOJA, 50L)).thenReturn(List.of(linhaDeFicha(50L, "0.500")));

        var erro = assertThrows(ResponseStatusException.class, () -> service.excluir(50L));

        assertTrue(erro.getReason().contains("Açaí 500ml"), "a mensagem foi: " + erro.getReason());
        assertTrue(erro.getReason().contains("Polpa de Acai"), "e o nome do insumo");
    }

    @Test
    void excluirInsumoQueNAOestaEmFichaNENHUMA_funciona() {
        // O conserto nao pode virar "nunca da para excluir".
        Insumo avulso = insumo(51L, "Guardanapo");
        when(fichas.findByLojaIdAndInsumoId(LOJA, 51L)).thenReturn(List.of());

        assertDoesNotThrow(() -> service.excluir(51L));

        verify(insumos).delete(avulso);
    }

    // =============================================================== o consumirFicha para de mentir

    @Test
    void fichaComTODOSosInsumosAPAGADOS_devolveNULO() {
        // O coracao do conserto. Devolver ZERO fazia `if (custoFicha == null) baixarEstoque` NAO
        // rodar, e o item era gravado com consumiuFicha=true -- mentira dupla: o produto nao baixou e
        // o cancelamento tambem nao devolve.
        //
        // Devolvendo null, o produto se comporta como produto SEM ficha: baixa o proprio estoque,
        // grava consumiuFicha=false, e o cancelamento devolve a unidade certa.
        when(fichas.findByLojaIdAndProdutoId(LOJA, PRODUTO))
                .thenReturn(List.of(linhaDeFicha(99L, "0.500")));
        when(insumos.findByIdAndLojaId(99L, LOJA)).thenReturn(Optional.empty());

        assertNull(service.consumirFicha(LOJA, PRODUTO, 2),
                "ficha sem nenhum insumo vivo tem que se comportar como produto SEM ficha");
    }

    @Test
    void fichaComTODOSapagados_NAOconsomeNadaENAOestoura() {
        when(fichas.findByLojaIdAndProdutoId(LOJA, PRODUTO))
                .thenReturn(List.of(linhaDeFicha(99L, "0.500"), linhaDeFicha(98L, "0.200")));
        when(insumos.findByIdAndLojaId(anyLong(), eq(LOJA))).thenReturn(Optional.empty());

        assertNull(service.consumirFicha(LOJA, PRODUTO, 1));

        verify(insumos, never()).mexerNoEstoque(anyLong(), anyLong(), any());
        verify(insumos, never()).consumirSeTiver(anyLong(), anyLong(), any());
    }

    @Test
    void fichaPARCIALmenteFantasma_consomeOqueEXISTEEdevolveOcustoDELE() {
        // E o caso que esta na producao hoje: uma linha viva e uma fantasma. O insumo vivo FOI
        // consumido, entao consumiuFicha=true e verdade -- o que fica errado e o custo, menor do que
        // deveria, e e por isso que o metodo avisa no log.
        insumo(50L, "Polpa de Acai");
        when(fichas.findByLojaIdAndProdutoId(LOJA, PRODUTO))
                .thenReturn(List.of(linhaDeFicha(50L, "0.500"), linhaDeFicha(99L, "0.200")));
        when(insumos.findByIdAndLojaId(99L, LOJA)).thenReturn(Optional.empty());

        BigDecimal custo = service.consumirFicha(LOJA, PRODUTO, 1);

        assertNotNull(custo, "ha insumo vivo: NAO e para se comportar como produto sem ficha");
        assertEquals(0, custo.compareTo(new BigDecimal("5.00")), "0,500 x R$ 10,00; o fantasma nao soma");
        verify(insumos).mexerNoEstoque(eq(50L), eq(LOJA), any());
    }

    @Test
    void fichaINTEIRAcontinuaFuncionando() {
        insumo(50L, "Polpa de Acai");
        insumo(51L, "Leite Ninho");
        when(fichas.findByLojaIdAndProdutoId(LOJA, PRODUTO))
                .thenReturn(List.of(linhaDeFicha(50L, "0.500"), linhaDeFicha(51L, "0.100")));

        BigDecimal custo = service.consumirFicha(LOJA, PRODUTO, 1);

        assertEquals(0, custo.compareTo(new BigDecimal("6.00")), "(0,5 + 0,1) x R$ 10,00");
        verify(insumos).mexerNoEstoque(eq(50L), eq(LOJA), any());
        verify(insumos).mexerNoEstoque(eq(51L), eq(LOJA), any());
    }

    @Test
    void produtoSEMfichaContinuaDevolvendoNULO() {
        when(fichas.findByLojaIdAndProdutoId(LOJA, PRODUTO)).thenReturn(List.of());

        assertNull(service.consumirFicha(LOJA, PRODUTO, 1));
    }

    @Test
    void noMODOqueRECUSA_aFichaTODAfantasmaTambemDevolveNULO() {
        // O cardapio publico usa o modo que recusa por falta de estoque. Ficha toda fantasma nao e
        // falta de estoque: e ficha quebrada, e o caminho tem que ser o mesmo.
        when(fichas.findByLojaIdAndProdutoId(LOJA, PRODUTO))
                .thenReturn(List.of(linhaDeFicha(99L, "0.500")));
        when(insumos.findByIdAndLojaId(99L, LOJA)).thenReturn(Optional.empty());

        assertNull(service.consumirFicha(LOJA, PRODUTO, 1, true));
        verify(insumos, never()).consumirSeTiver(anyLong(), anyLong(), any());
    }
}
