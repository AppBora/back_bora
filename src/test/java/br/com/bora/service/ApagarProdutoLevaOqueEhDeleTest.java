package br.com.bora.service;

import br.com.bora.entity.ComplementoGrupo;
import br.com.bora.entity.Produto;
import br.com.bora.repository.ComplementoGrupoRepository;
import br.com.bora.repository.ComplementoItemRepository;
import br.com.bora.repository.ProdutoInsumoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Apagar produto leva junto a ficha técnica e os complementos dele.
 *
 * <h2>O item 4.3 da varredura, com as duas metades que ele tem</h2>
 * <p><b>O erro 500.</b> {@code complemento_grupo} tem chave estrangeira para {@code produto} sem
 * {@code ON DELETE} — conferido na produção em 21/10/2026: é a única FK que aponta para produto, com
 * {@code confdeltype = 'a'} (NO ACTION). O {@code excluir} era um {@code repo.delete(...)} seco,
 * então o banco recusava e o lojista levava 500. E pegava justamente os produtos que têm adicional
 * cadastrado — numa açaiteria, os que mais vendem.</p>
 *
 * <p><b>A ficha órfã, que a varredura não mencionou.</b> {@code produto_insumo} é o contrário: não
 * tem chave estrangeira nenhuma. O DELETE passava e deixava as linhas da ficha apontando para um
 * produto que não existe — a ficha fantasma do item 2.5, pelo outro lado.</p>
 *
 * <h2>O que NÃO é apagado, e isso foi medido</h2>
 * <p>O histórico. {@code pedido_item} não tem FK para produto, e o relatório por produto agrupa pela
 * {@code descricao} gravada no item, não pelo nome do cadastro ({@code RelatorioService}). Pedido
 * antigo de um produto apagado continua no relatório com o nome que tinha no dia da venda — por isso
 * apagar o produto não precisa ser recusado.</p>
 */
class ApagarProdutoLevaOqueEhDeleTest {

    private ProdutoRepository repo;
    private ProdutoInsumoRepository fichas;
    private ComplementoGrupoRepository grupos;
    private ComplementoItemRepository opcoes;
    private ProdutoService service;

    private static final Long LOJA = 1L;
    private static final Long PRODUTO = 10L;

    private Produto alvo;

    @BeforeEach
    void montar() {
        repo = mock(ProdutoRepository.class);
        fichas = mock(ProdutoInsumoRepository.class);
        grupos = mock(ComplementoGrupoRepository.class);
        opcoes = mock(ComplementoItemRepository.class);
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);

        alvo = new Produto();
        alvo.id = PRODUTO;
        alvo.lojaId = LOJA;
        alvo.nome = "Açaí 500ml";
        when(repo.findByIdAndLojaId(PRODUTO, LOJA)).thenReturn(Optional.of(alvo));

        service = new ProdutoService(repo, fichas, grupos, opcoes, ctx);
    }

    private ComplementoGrupo grupo(long id) {
        ComplementoGrupo g = new ComplementoGrupo();
        g.id = id;
        g.lojaId = LOJA;
        g.produtoId = PRODUTO;
        g.nome = "Adicionais";
        return g;
    }

    private void comGrupos(ComplementoGrupo... gs) {
        when(grupos.findByLojaIdAndProdutoIdOrderById(LOJA, PRODUTO)).thenReturn(List.of(gs));
    }

    // =============================================================== o que vai junto

    @Test
    void osCOMPLEMENTOSdoProdutoSAOapagados() {
        comGrupos(grupo(77L), grupo(78L));

        service.excluir(PRODUTO);

        verify(opcoes).deleteByLojaIdAndGrupoIdIn(LOJA, List.of(77L, 78L));
        verify(grupos).deleteByLojaIdAndProdutoId(LOJA, PRODUTO);
        verify(repo).delete(alvo);
    }

    @Test
    void aFICHAtecnicaDoProdutoEHapagada() {
        comGrupos();

        service.excluir(PRODUTO);

        verify(fichas).deleteByLojaIdAndProdutoId(LOJA, PRODUTO);
    }

    @Test
    void aORDEMimporta_oFILHOsaiANTESdoPAI() {
        // Apagar o produto primeiro e o que dava 500: a chave estrangeira do grupo recusa. Esta ordem
        // e a razao de o conserto existir, nao um detalhe de estilo.
        comGrupos(grupo(77L));

        service.excluir(PRODUTO);

        InOrder ordem = inOrder(opcoes, grupos, fichas, repo);
        ordem.verify(opcoes).deleteByLojaIdAndGrupoIdIn(eq(LOJA), anyList());
        ordem.verify(grupos).deleteByLojaIdAndProdutoId(LOJA, PRODUTO);
        ordem.verify(fichas).deleteByLojaIdAndProdutoId(LOJA, PRODUTO);
        ordem.verify(repo).delete(alvo);
    }

    @Test
    void produtoSEMcomplementoNAOchamaOapagarDEopcoes() {
        // `deleteByLojaIdAndGrupoIdIn` com lista vazia e um DELETE com `in ()` -- em algumas versoes
        // do Hibernate isso e SQL invalido. Nao chamar e mais seguro que confiar nisso.
        comGrupos();

        service.excluir(PRODUTO);

        verify(opcoes, never()).deleteByLojaIdAndGrupoIdIn(anyLong(), anyList());
        verify(repo).delete(alvo);
    }

    // =============================================================== o que NAO vai junto

    @Test
    void oAPAGARehTRANSACIONAL() {
        // Sem transacao, um erro no meio deixaria os complementos apagados e o produto no ar -- o
        // lojista veria o produto voltar sem os adicionais, e nada diria o motivo.
        String fonte = assertDoesNotThrow(() -> java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/br/com/bora/service/ProdutoService.java"),
                java.nio.charset.StandardCharsets.UTF_8));
        int i = fonte.indexOf("public void excluir(");
        assertTrue(i > 0);

        assertTrue(fonte.substring(Math.max(0, i - 400), i).contains("Transactional"),
                "o excluir tem que ser transacional: ele apaga quatro coisas");
    }

    @Test
    void produtoDEoutraLOJAnaoEHapagado() {
        when(repo.findByIdAndLojaId(999L, LOJA)).thenReturn(Optional.empty());

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.excluir(999L));

        verify(repo, never()).delete(any(Produto.class));
        verify(fichas, never()).deleteByLojaIdAndProdutoId(anyLong(), anyLong());
        verify(grupos, never()).deleteByLojaIdAndProdutoId(anyLong(), anyLong());
    }

    @Test
    void todoAPAGARfiltraPELAlojaDOcontexto() {
        // Nenhuma das quatro chamadas pode usar a loja que vem de fora: o id do produto vem da URL, e
        // a loja tem que vir do token. Sem isto, apagar produto de outra loja seria so chutar um id.
        comGrupos(grupo(77L));

        service.excluir(PRODUTO);

        verify(grupos).findByLojaIdAndProdutoIdOrderById(LOJA, PRODUTO);
        verify(opcoes).deleteByLojaIdAndGrupoIdIn(eq(LOJA), anyList());
        verify(grupos).deleteByLojaIdAndProdutoId(LOJA, PRODUTO);
        verify(fichas).deleteByLojaIdAndProdutoId(LOJA, PRODUTO);
    }
}
