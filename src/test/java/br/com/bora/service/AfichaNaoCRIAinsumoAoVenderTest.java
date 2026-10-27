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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A ficha tecnica nao cria insumo ao vender, e a linha que nao consome nada nao trava o checkout.
 *
 * <h2>Defeito 1: quantidade negativa na ficha (achado pelo QA em 26/10/2026)</h2>
 * <p>O {@code salvarFicha} levava a quantidade do corpo do PUT direto para o {@code BigDecimal}, sem
 * olhar o sinal. Com {@code -0,5} gravado, <b>cada venda SOMAVA ao estoque do insumo</b>, nas duas
 * pontas:</p>
 * <ul>
 *   <li>balcao e marketplace: {@code mexerNoEstoque(id, loja, precisa.negate())} — o negate de um
 *       numero negativo e positivo;</li>
 *   <li>cardapio publico: {@code consumirSeTiver} faz {@code estoque - (-0,5)}, e a condicao
 *       {@code estoque >= -0,5} nunca barra.</li>
 * </ul>
 * <p>E o CMV ia junto: o custo do item e {@code custo x quantidade}, entao a linha negativa
 * <b>abatia</b> o custo do produto. Dava para zerar o CMV de um lanche inteiro e inflar a margem no
 * relatorio que o dono usa para decidir preco.</p>
 *
 * <h2>Defeito 2: o cardapio oferecia e o checkout recusava</h2>
 * <p>O {@code fichaDaParaFazer} — que marca o produto como esgotado no cardapio — pulava a linha com
 * quantidade {@code <= 0}. O {@code consumirFicha} nao pulava. Com {@code qtd = 0} a condicao do
 * banco e {@code estoque >= 0}, e insumo que o balcao deixou negativo (o balcao deixa, de proposito)
 * reprovava a condicao: o cliente via o produto disponivel, montava o carrinho e levava
 * "falta insumo" por causa de uma linha que nao tira nada do estoque.</p>
 *
 * <h2>Defeito 3: o espelho do cancelamento</h2>
 * <p>O {@code devolverFicha} tambem nao olhava o sinal. Com a linha negativa gravada antes da regra,
 * cancelar <b>TIRAVA</b> do estoque em vez de devolver.</p>
 */
class AfichaNaoCRIAinsumoAoVenderTest {

    private InsumoRepository insumos;
    private ProdutoInsumoRepository fichas;
    private ProdutoRepository produtos;
    private br.com.bora.repository.ComplementoItemRepository adicionais;
    private AuthContext ctx;
    private InsumoService service;

    private static final Long LOJA = 1L;
    private static final Long PRODUTO = 11L;
    private static final Long INSUMO = 50L;

    @BeforeEach
    void montar() {
        insumos = mock(InsumoRepository.class);
        fichas = mock(ProdutoInsumoRepository.class);
        produtos = mock(ProdutoRepository.class);
        adicionais = mock(br.com.bora.repository.ComplementoItemRepository.class);
        ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        Produto p = new Produto();
        p.id = PRODUTO;
        p.lojaId = LOJA;
        when(produtos.findByIdAndLojaId(PRODUTO, LOJA)).thenReturn(Optional.of(p));
        when(insumos.consumirSeTiver(anyLong(), anyLong(), any())).thenReturn(1);
        service = new InsumoService(insumos, fichas, produtos, adicionais, ctx);
    }

    /** Um insumo com saldo e custo conhecidos. */
    private Insumo insumo(String estoque) {
        Insumo i = new Insumo();
        i.id = INSUMO;
        i.lojaId = LOJA;
        i.nome = "Polpa de acai";
        i.unidade = "kg";
        i.custo = new BigDecimal("20.00");
        i.estoque = estoque == null ? null : new BigDecimal(estoque);
        when(insumos.findByIdAndLojaId(INSUMO, LOJA)).thenReturn(Optional.of(i));
        return i;
    }

    private void fichaCom(String porUnidade) {
        ProdutoInsumo pi = new ProdutoInsumo();
        pi.lojaId = LOJA;
        pi.produtoId = PRODUTO;
        pi.insumoId = INSUMO;
        pi.quantidade = porUnidade == null ? null : new BigDecimal(porUnidade);
        when(fichas.findByLojaIdAndProdutoId(LOJA, PRODUTO)).thenReturn(List.of(pi));
    }

    private List<Map<String, Object>> corpo(Object quantidade) {
        List<Map<String, Object>> l = new ArrayList<>();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("insumoId", INSUMO);
        m.put("quantidade", quantidade);
        l.add(m);
        return l;
    }

    // ================================================ 1. a gravacao recusa o negativo
    @Test
    void aFICHAnaoACEITAquantidadeNEGATIVA() {
        insumo("100.000");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.salvarFicha(PRODUTO, corpo("-0.5")));

        assertEquals(400, e.getStatusCode().value());
        verify(fichas, never()).save(any());
    }

    @Test
    void aMENSAGEMdizPORQUE_eNAOsoQUErecusou() {
        insumo("100.000");
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.salvarFicha(PRODUTO, corpo("-1")));
        String m = e.getReason() == null ? "" : e.getReason().toLowerCase();
        assertTrue(m.contains("consome"), "a mensagem precisa dizer o que o campo e: " + e.getReason());
    }

    @Test
    void textoQUEnaoEHnumeroDA400_eNAO500() {
        // Antes: NumberFormatException subia crua -- HTTP 500 com rastro de pilha, e o lojista sem
        // saber qual campo esta errado.
        insumo("100.000");
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.salvarFicha(PRODUTO, corpo("meio quilo")));
        assertEquals(400, e.getStatusCode().value());
    }

    @Test
    void virgulaDEcimalEHaceita() {
        // O painel e em portugues: "0,030" e o que o lojista digita.
        insumo("100.000");
        service.salvarFicha(PRODUTO, corpo("0,030"));

        var gravada = org.mockito.ArgumentCaptor.forClass(ProdutoInsumo.class);
        verify(fichas).save(gravada.capture());
        assertEquals(0, new BigDecimal("0.030").compareTo(gravada.getValue().quantidade));
    }

    @Test
    void zeroCONTINUAvalendo() {
        // Linha que o lojista adicionou e nao preencheu. Recusar transformaria rascunho em erro de
        // tela -- e desde este commit o consumo pula a linha que nao consome nada.
        insumo("100.000");
        assertDoesNotThrow(() -> service.salvarFicha(PRODUTO, corpo("0")));
        verify(fichas).save(any());
    }

    @Test
    void quantidadeNORMALcontinuaGRAVANDO() {
        insumo("100.000");
        service.salvarFicha(PRODUTO, corpo("0.300"));
        verify(fichas).save(any());
    }

    // ================================================ 2. vender nao soma ao estoque
    @Test
    void fichaNEGATIVAantigaNAOsomaAOestoqueNObalcao() {
        // A base ja tem ficha gravada antes da regra de 26/10/2026: a guarda no consumo tambem.
        insumo("100.000");
        fichaCom("-0.5");

        service.consumirFicha(LOJA, PRODUTO, 2);

        verify(insumos, never()).mexerNoEstoque(anyLong(), anyLong(), any());
        verify(insumos, never()).consumirSeTiver(anyLong(), anyLong(), any());
    }

    @Test
    void fichaNEGATIVAantigaNAOsomaAOestoqueNOcardapio() {
        insumo("100.000");
        fichaCom("-0.5");

        service.consumirFicha(LOJA, PRODUTO, 2, true);

        verify(insumos, never()).consumirSeTiver(anyLong(), anyLong(), any());
        verify(insumos, never()).mexerNoEstoque(anyLong(), anyLong(), any());
    }

    @Test
    void oCUSTOdeUMAfichaNEGATIVAnaoFICAnegativo() {
        // O CMV e `custo x quantidade`. Uma linha de -0,5 x R$ 20 abatia R$ 10 do custo do produto.
        insumo("100.000");
        fichaCom("-0.5");

        BigDecimal custo = service.consumirFicha(LOJA, PRODUTO, 1);

        assertNotNull(custo, "a ficha existe: o produto nao pode virar produto sem ficha");
        assertTrue(custo.signum() >= 0,
                "o custo do item saiu NEGATIVO (" + custo + "): a margem do relatorio sai melhor do "
                        + "que e");
    }

    // ================================================ 3. o zero nao trava o checkout
    @Test
    void linhaZEROnaoRECUSAoPEDIDOnoCARDAPIO() {
        // O caso medido: insumo com saldo NEGATIVO (o balcao deixa) e linha de ficha com quantidade
        // zero. A condicao do banco e `estoque >= 0`, que reprova -- e o cardapio nem marcava o
        // produto como esgotado, porque o `fichaDaParaFazer` ja pulava a linha.
        insumo("-2.000");
        fichaCom("0");
        when(insumos.consumirSeTiver(anyLong(), anyLong(), any())).thenReturn(0);

        assertDoesNotThrow(() -> service.consumirFicha(LOJA, PRODUTO, 1, true),
            "o cliente ve o produto disponivel e leva \"falta insumo\" por uma linha que nao tira nada");
        verify(insumos, never()).consumirSeTiver(anyLong(), anyLong(), any());
    }

    @Test
    void oCARDAPIOeOcheckoutCONCORDAM_linhaPORlinha() {
        // A trava contra os dois voltarem a divergir: para a mesma ficha e o mesmo saldo, se o
        // cardapio diz que da para fazer, o checkout nao pode recusar.
        for (String qtd : new String[] { "0", "-0.5", "0.100" }) {
            for (String saldo : new String[] { "-2.000", "0.000", "100.000" }) {
                montar();
                insumo(saldo);
                fichaCom(qtd);
                // O banco recusa exatamente quando o saldo nao cobre o pedido.
                BigDecimal precisa = new BigDecimal(qtd);
                boolean bancoRecusa = new BigDecimal(saldo).compareTo(precisa) < 0;
                when(insumos.consumirSeTiver(anyLong(), anyLong(), any())).thenReturn(bancoRecusa ? 0 : 1);

                boolean cardapioOferece = service.fichaDaParaFazer(LOJA, PRODUTO, 1);
                boolean checkoutAceita;
                try {
                    service.consumirFicha(LOJA, PRODUTO, 1, true);
                    checkoutAceita = true;
                } catch (InsumoService.SemInsumo e) {
                    checkoutAceita = false;
                }

                assertFalse(cardapioOferece && !checkoutAceita,
                        "divergiram com quantidade " + qtd + " e saldo " + saldo + ": o cardapio "
                                + "oferece e o checkout recusa");
            }
        }
    }

    @Test
    void linhaDEverdadeCONTINUArecusandoQUANDOfalta() {
        // O que nao pode e afrouxar: linha de verdade sem saldo tem de recusar no cardapio.
        insumo("0.050");
        fichaCom("0.100");
        when(insumos.consumirSeTiver(anyLong(), anyLong(), any())).thenReturn(0);

        assertThrows(InsumoService.SemInsumo.class, () -> service.consumirFicha(LOJA, PRODUTO, 1, true));
        assertFalse(service.fichaDaParaFazer(LOJA, PRODUTO, 1), "o cardapio tambem tem de barrar");
    }

    @Test
    void linhaDEverdadeCONTINUAbaixandoOestoque() {
        insumo("100.000");
        fichaCom("0.300");

        BigDecimal custo = service.consumirFicha(LOJA, PRODUTO, 2);

        verify(insumos).mexerNoEstoque(eq(INSUMO), eq(LOJA), eq(new BigDecimal("0.600").negate()));
        assertEquals(0, new BigDecimal("6.00").compareTo(custo), "0,300 x R$ 20 = R$ 6,00");
    }

    // ================================================ 4. o espelho do cancelamento
    @Test
    void cancelarNAOtiraDOestoqueCOMfichaNEGATIVA() {
        insumo("100.000");
        fichaCom("-0.5");

        service.devolverFicha(LOJA, PRODUTO, 2);

        verify(insumos, never()).mexerNoEstoque(anyLong(), anyLong(), any());
    }

    @Test
    void cancelarCONTINUAdevolvendoOquePRECISA() {
        insumo("100.000");
        fichaCom("0.300");

        assertTrue(service.devolverFicha(LOJA, PRODUTO, 2));
        verify(insumos).mexerNoEstoque(INSUMO, LOJA, new BigDecimal("0.600"));
    }

    @Test
    void linhaZEROnaoDEVOLVEnada() {
        insumo("100.000");
        fichaCom("0");

        service.devolverFicha(LOJA, PRODUTO, 3);

        verify(insumos, never()).mexerNoEstoque(anyLong(), anyLong(), any());
    }
}
