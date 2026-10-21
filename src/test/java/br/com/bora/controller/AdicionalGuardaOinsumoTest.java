package br.com.bora.controller;

import br.com.bora.entity.ComplementoGrupo;
import br.com.bora.entity.ComplementoItem;
import br.com.bora.entity.Produto;
import br.com.bora.repository.ComplementoGrupoRepository;
import br.com.bora.repository.ComplementoItemRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O cadastro do adicional guarda o insumo e a quantidade que a tela mandou.
 *
 * <h2>Por que esta classe existe</h2>
 * <p>Teste de serviço não prova fiação de controller, e isso já passou verde duas vezes em
 * 21/10/2026. Aqui é o caso mais fácil de quebrar sem ninguém ver: se o {@code salvar} ignorar os dois
 * campos novos, a tela mostra o insumo escolhido, o lojista salva, recarrega e o campo volta vazio —
 * e o adicional continua não baixando estoque, exatamente como antes do recurso existir. Nenhum erro,
 * nenhum log, e o lojista concluindo que o sistema não funciona.</p>
 */
class AdicionalGuardaOinsumoTest {

    private ComplementoGrupoRepository grupos;
    private ComplementoItemRepository itens;
    private ComplementoController controller;

    private static final Long LOJA = 1L;
    private static final Long PRODUTO = 10L;

    @BeforeEach
    void montar() {
        grupos = mock(ComplementoGrupoRepository.class);
        itens = mock(ComplementoItemRepository.class);
        ProdutoRepository produtos = mock(ProdutoRepository.class);
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);

        Produto p = new Produto();
        p.id = PRODUTO;
        p.lojaId = LOJA;
        when(produtos.findById(PRODUTO)).thenReturn(Optional.of(p));

        when(grupos.findByLojaIdAndProdutoIdOrderById(LOJA, PRODUTO)).thenReturn(List.of());
        when(grupos.save(any(ComplementoGrupo.class))).thenAnswer(i -> {
            ComplementoGrupo g = i.getArgument(0);
            g.id = 77L;
            return g;
        });
        when(itens.save(any(ComplementoItem.class))).thenAnswer(i -> i.getArgument(0));

        controller = new ComplementoController(grupos, itens, produtos, ctx);
    }

    /** Um grupo com uma opção, cujos campos de insumo vêm do mapa. */
    private List<Map<String, Object>> corpo(Map<String, Object> campos) {
        Map<String, Object> item = new HashMap<>();
        item.put("nome", "Granola");
        item.put("preco", "3.00");
        item.putAll(campos);
        Map<String, Object> grupo = new HashMap<>();
        grupo.put("nome", "Adicionais");
        grupo.put("minimo", 0);
        grupo.put("maximo", 5);
        grupo.put("itens", List.of(item));
        List<Map<String, Object>> fora = new ArrayList<>();
        fora.add(grupo);
        return fora;
    }

    private ComplementoItem salvo(Map<String, Object> campos) {
        controller.salvar(PRODUTO, corpo(campos));
        ArgumentCaptor<ComplementoItem> c = ArgumentCaptor.forClass(ComplementoItem.class);
        verify(itens).save(c.capture());
        return c.getValue();
    }

    // =============================================================== guarda

    @Test
    void oINSUMOeaQUANTIDADEsaoGRAVADOS() {
        ComplementoItem ci = salvo(Map.of("insumoId", 50, "quantidade", "0.030"));

        assertEquals(50L, ci.insumoId);
        assertEquals(0, ci.quantidade.compareTo(new BigDecimal("0.030")));
    }

    @Test
    void quantidadeComVIRGULAeAceita() {
        // O lojista digita "0,030" no campo. Recusar em silencio deixaria o adicional sem baixar
        // estoque e ele nunca saberia por que.
        ComplementoItem ci = salvo(Map.of("insumoId", 50, "quantidade", "0,030"));

        assertEquals(0, ci.quantidade.compareTo(new BigDecimal("0.030")));
    }

    @Test
    void oNOMEeOPRECOcontinuamFuncionando() {
        ComplementoItem ci = salvo(Map.of());

        assertEquals("Granola", ci.nome);
        assertEquals(0, ci.preco.compareTo(new BigDecimal("3.00")));
    }

    // =============================================================== os dois campos andam juntos

    @Test
    void insumoSEMquantidadeNAOguardaNENHUMdosDOIS() {
        // Insumo sem quantidade nao baixa nada, e deixar so o insumo gravado faria a tela mostrar um
        // vinculo que nao funciona.
        ComplementoItem ci = salvo(Map.of("insumoId", 50));

        assertNull(ci.insumoId);
        assertNull(ci.quantidade);
    }

    @Test
    void quantidadeSEMinsumoNAOguardaNENHUMdosDOIS() {
        ComplementoItem ci = salvo(Map.of("quantidade", "0.030"));

        assertNull(ci.insumoId);
        assertNull(ci.quantidade);
    }

    @Test
    void quantidadeZEROouNEGATIVAnaoLIGAoINSUMO() {
        assertNull(salvo(Map.of("insumoId", 50, "quantidade", "0")).insumoId);
        reset(itens);
        when(itens.save(any(ComplementoItem.class))).thenAnswer(i -> i.getArgument(0));
        assertNull(salvo(Map.of("insumoId", 50, "quantidade", "-1")).insumoId);
    }

    @Test
    void campoILEGIVELnaoDERRUBAaGRAVACAO() {
        // O adicional e o preco sao o que importa; o vinculo com insumo e extra. Estourar aqui faria
        // o lojista perder o cadastro inteiro por causa de um campo.
        ComplementoItem ci = assertDoesNotThrow(
                () -> salvo(Map.of("insumoId", "abc", "quantidade", "muito")));

        assertEquals("Granola", ci.nome);
        assertNull(ci.insumoId);
    }

    @Test
    void adicionalSEMosCAMPOSnovosContinuaSALVANDO() {
        // Todo adicional cadastrado antes da V55, e todo adicional que nao controla estoque.
        ComplementoItem ci = salvo(Map.of());

        assertNull(ci.insumoId);
        assertNull(ci.quantidade);
    }

    // =============================================================== e a tela consegue ler de volta

    @Test
    void aLISTAdevolveOinsumoEaQUANTIDADE() {
        // Sem isto a tela abre com o campo vazio depois de salvar, e o lojista reescreve tudo a cada
        // visita -- ou conclui que nao salvou.
        ComplementoGrupo g = new ComplementoGrupo();
        g.id = 77L;
        g.lojaId = LOJA;
        g.produtoId = PRODUTO;
        g.nome = "Adicionais";
        ComplementoItem ci = new ComplementoItem();
        ci.id = 5L;
        ci.grupoId = 77L;
        ci.nome = "Granola";
        ci.preco = new BigDecimal("3.00");
        ci.insumoId = 50L;
        ci.quantidade = new BigDecimal("0.0300");
        when(grupos.findByLojaIdAndProdutoIdOrderById(LOJA, PRODUTO)).thenReturn(List.of(g));
        when(itens.findByLojaIdAndGrupoIdInOrderById(eq(LOJA), anyList())).thenReturn(List.of(ci));

        var fora = controller.listar(PRODUTO);

        @SuppressWarnings("unchecked")
        var opcoes = (List<Map<String, Object>>) fora.get(0).get("itens");
        assertEquals(50L, opcoes.get(0).get("insumoId"));
        assertEquals(new BigDecimal("0.0300"), opcoes.get(0).get("quantidade"));
    }
}
