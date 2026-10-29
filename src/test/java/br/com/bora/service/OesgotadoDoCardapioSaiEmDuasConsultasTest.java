package br.com.bora.service;

import br.com.bora.entity.Insumo;
import br.com.bora.entity.ProdutoInsumo;
import br.com.bora.repository.ComplementoItemRepository;
import br.com.bora.repository.InsumoRepository;
import br.com.bora.repository.ProdutoInsumoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O "esgotado" do cardapio publico sai em DUAS consultas, e diz a mesma coisa que dizia antes.
 *
 * <h2>O N+1 que isto fecha (pente fino de 29/10/2026)</h2>
 * <p>O cardapio chamava {@code fichaDaParaFazer} dentro do laco que monta cada produto. Cada chamada
 * e uma consulta de ficha mais UMA consulta por linha de ficha. Na loja da Zira, com 44 produtos,
 * eram 45 ou mais idas ao banco so para desenhar a tela -- a tela mais aberta do sistema e a unica
 * sem cache nenhum.</p>
 *
 * <h2>O risco de verdade nao e o desempenho</h2>
 * <p>E as <b>duas regras divergirem</b>. Se o lote decidir diferente do original, o cardapio marca
 * "esgotado" um produto que o pedido aceita -- ou pior, oferece um que a cozinha nao consegue fazer.
 * Por isso a metade deste arquivo compara as duas respostas no mesmo cenario, incluindo os tres
 * casos em que o original deixa passar de proposito: produto sem ficha, insumo apagado ou sem
 * controle de estoque, e linha de ficha com quantidade zero.</p>
 */
class OesgotadoDoCardapioSaiEmDuasConsultasTest {

    private static final Long LOJA = 18L;

    private InsumoRepository insumos;
    private ProdutoInsumoRepository fichas;
    private InsumoService service;

    @BeforeEach
    void montar() {
        insumos = mock(InsumoRepository.class);
        fichas = mock(ProdutoInsumoRepository.class);
        service = new InsumoService(insumos, fichas, mock(ProdutoRepository.class),
                mock(ComplementoItemRepository.class), mock(AuthContext.class));
    }

    private Insumo insumo(long id, String estoque) {
        Insumo in = new Insumo();
        in.id = id;
        in.lojaId = LOJA;
        in.nome = "Insumo " + id;
        in.estoque = estoque == null ? null : new BigDecimal(estoque);
        return in;
    }

    private ProdutoInsumo linha(long produtoId, long insumoId, String qtd) {
        ProdutoInsumo pi = new ProdutoInsumo();
        pi.lojaId = LOJA;
        pi.produtoId = produtoId;
        pi.insumoId = insumoId;
        pi.quantidade = new BigDecimal(qtd);
        return pi;
    }

    /**
     * Instala o mesmo mundo nos dois caminhos: o do lote e o de uma consulta por produto.
     *
     * <p>Sem isso a comparacao nao valeria nada -- cada caminho leria um banco diferente.</p>
     */
    private void mundo(List<ProdutoInsumo> todasAsFichas, List<Insumo> todosOsInsumos) {
        when(fichas.findByLojaIdAndProdutoIdIn(eq(LOJA), any())).thenAnswer(i -> {
            Collection<?> ids = i.getArgument(1);
            return todasAsFichas.stream().filter(f -> ids.contains(f.produtoId)).toList();
        });
        when(fichas.findByLojaIdAndProdutoId(eq(LOJA), anyLong())).thenAnswer(i -> {
            Long pid = i.getArgument(1);
            return todasAsFichas.stream().filter(f -> pid.equals(f.produtoId)).toList();
        });
        Map<Long, Insumo> porId = new java.util.HashMap<>();
        for (Insumo in : todosOsInsumos) porId.put(in.id, in);
        when(insumos.findByLojaIdAndIdIn(eq(LOJA), any())).thenAnswer(i -> {
            Collection<?> ids = i.getArgument(1);
            return todosOsInsumos.stream().filter(in -> ids.contains(in.id)).toList();
        });
        when(insumos.findByIdAndLojaId(anyLong(), eq(LOJA)))
                .thenAnswer(i -> Optional.ofNullable(porId.get((Long) i.getArgument(0))));
    }

    // ===================================================== as duas consultas

    @Test
    void saoDUASconsultasPARAtodosOSprodutos() {
        mundo(List.of(linha(1L, 10L, "0.5"), linha(2L, 10L, "0.5"), linha(3L, 11L, "1")),
                List.of(insumo(10L, "100"), insumo(11L, "100")));

        service.produtosQueNaoDaoParaFazer(LOJA, List.of(1L, 2L, 3L, 4L, 5L), 1);

        verify(fichas).findByLojaIdAndProdutoIdIn(eq(LOJA), any());
        verify(insumos).findByLojaIdAndIdIn(eq(LOJA), any());
        // Nenhuma consulta por produto: era isso que multiplicava por 45 na abertura do cardapio.
        verify(fichas, never()).findByLojaIdAndProdutoId(anyLong(), anyLong());
        verify(insumos, never()).findByIdAndLojaId(anyLong(), anyLong());
    }

    @Test
    void listaVAZIAnaoCONSULTAnada() {
        service.produtosQueNaoDaoParaFazer(LOJA, List.of(), 1);

        verify(fichas, never()).findByLojaIdAndProdutoIdIn(anyLong(), any());
        verify(insumos, never()).findByLojaIdAndIdIn(anyLong(), any());
    }

    @Test
    void nenhumPRODUTOcomFICHAnaoCONSULTAinsumo() {
        mundo(List.of(), List.of(insumo(10L, "100")));

        service.produtosQueNaoDaoParaFazer(LOJA, List.of(1L, 2L), 1);

        verify(insumos, never()).findByLojaIdAndIdIn(anyLong(), any());
    }

    // ===================================================== e diz o mesmo que antes

    /** Compara, produto por produto, o lote com a consulta de um. Divergir aqui e o estrago. */
    private void asDuasRegrasConcordam(List<Long> produtos, List<ProdutoInsumo> fichasDoMundo,
                                       List<Insumo> insumosDoMundo, int quantas) {
        mundo(fichasDoMundo, insumosDoMundo);

        Set<Long> peloLote = service.produtosQueNaoDaoParaFazer(LOJA, produtos, quantas);
        List<Long> umPorUm = new ArrayList<>();
        for (Long id : produtos) {
            if (!service.fichaDaParaFazer(LOJA, id, quantas)) umPorUm.add(id);
        }

        assertEquals(umPorUm.stream().sorted().toList(), peloLote.stream().sorted().toList(),
                "o lote e a consulta por produto discordaram: o cardapio passa a mentir sobre esgotado");
    }

    @Test
    void oINSUMOqueFALTAesgotaOPRODUTO_nosDOIScaminhos() {
        asDuasRegrasConcordam(List.of(1L, 2L),
                List.of(linha(1L, 10L, "2"), linha(2L, 11L, "1")),
                List.of(insumo(10L, "1"), insumo(11L, "50")), 1);
    }

    @Test
    void oPRODUTOsemFICHAnuncaESTAesgotado_nosDOIScaminhos() {
        // Nao ha insumo para faltar. Marcar esgotado aqui tiraria do ar todo produto sem ficha.
        asDuasRegrasConcordam(List.of(7L, 8L), List.of(linha(7L, 10L, "1")),
                List.of(insumo(10L, "0")), 1);
    }

    @Test
    void oINSUMOapagadoNAObloqueiaVENDA_nosDOIScaminhos() {
        // Ficha fantasma: o consumo tambem pula essa linha, entao o cardapio nao pode bloquear.
        asDuasRegrasConcordam(List.of(1L), List.of(linha(1L, 999L, "5")), List.of(), 1);
    }

    @Test
    void oINSUMOsemCONTROLEdeESTOQUEnaoBLOQUEIA_nosDOIScaminhos() {
        asDuasRegrasConcordam(List.of(1L), List.of(linha(1L, 10L, "5")),
                List.of(insumo(10L, null)), 1);
    }

    @Test
    void aLINHAdeFICHAcomZEROnaoCONSOMEnada_nosDOIScaminhos() {
        asDuasRegrasConcordam(List.of(1L, 2L),
                List.of(linha(1L, 10L, "0"), linha(2L, 10L, "-3")),
                List.of(insumo(10L, "0")), 1);
    }

    @Test
    void oPRODUTOcomVARIASlinhasPRECISAdeTODAS_nosDOIScaminhos() {
        // Polpa sobrando e leite acabando: o produto esta esgotado pela segunda linha.
        asDuasRegrasConcordam(List.of(1L),
                List.of(linha(1L, 10L, "0.3"), linha(1L, 11L, "0.2"), linha(1L, 12L, "0.1")),
                List.of(insumo(10L, "100"), insumo(11L, "0.05"), insumo(12L, "100")), 1);
    }

    @Test
    void aQUANTIDADEpedidaENTRAnaCONTA_nosDOIScaminhos() {
        // Da para fazer 1, nao da para fazer 10. As duas regras tem de dizer o mesmo nos dois casos.
        asDuasRegrasConcordam(List.of(1L), List.of(linha(1L, 10L, "1")), List.of(insumo(10L, "5")), 1);
        asDuasRegrasConcordam(List.of(1L), List.of(linha(1L, 10L, "1")), List.of(insumo(10L, "5")), 10);
    }

    @Test
    void oESTOQUEexatoDAparaFAZER_nosDOIScaminhos() {
        // Fronteira: 5 de saldo para 5 de necessidade da para fazer. `<` e nao `<=`.
        mundo(List.of(linha(1L, 10L, "5")), List.of(insumo(10L, "5")));

        assertTrue(service.produtosQueNaoDaoParaFazer(LOJA, List.of(1L), 1).isEmpty(),
                "saldo exatamente igual ao necessario da para fazer");
        assertTrue(service.fichaDaParaFazer(LOJA, 1L, 1));
    }
}
