package br.com.bora.service;

import br.com.bora.entity.CategoriaOrdem;
import br.com.bora.repository.CategoriaOrdemRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A ordem das seções do cardápio é da loja, não do alfabeto.
 *
 * <h2>O que originou isto (medido em 28/10/2026)</h2>
 * <p>As três lojas da Zirá tiveram as categorias renomeadas para os nomes do cardápio de papel. Os
 * nomes ficaram iguais; a ordem não. A Frente de Caixa faz {@code [...set].sort()} e o cardápio
 * público vinha de {@code OrderByCategoriaAsc} — então o cliente abria o cardápio em "Açaí Fitness",
 * duas bebidas proteicas, enquanto "Especiais para Compartilhar" ficava no meio da página.</p>
 *
 * <h2>A armadilha que estes testes guardam</h2>
 * <p>O desempate alfabético <b>não pode</b> ser {@code String.compareTo}. O perigo está na
 * PRIMEIRA letra: "Água" começa com 'Á', código 193, maior que o 'Z' (90) — com {@code compareTo}
 * ela cai depois de todas as outras, e o cliente lê o cardápio fora de ordem.</p>
 *
 * <p><b>Eu errei isto ao escrever o teste e ele me corrigiu:</b> eu havia afirmado que "Açaí Puro"
 * também cairia no fim. Não cai — o 'A' dela é ASCII e só o 'ç' do meio é acentuado, e o meio só
 * decide quando o começo empata. O teste abaixo mede os dois casos em vez de confiar na minha
 * leitura, e falha se alguém trocar o {@code Collator} por um {@code compareTo} "equivalente".</p>
 */
class AordemDasSecoesEhDaLojaTest {

    private static final Long LOJA = 7L;

    private CategoriaOrdemRepository repo;
    private ProdutoRepository produtos;
    private AuthContext ctx;
    private CategoriaOrdemService service;

    @BeforeEach
    void montar() {
        repo = mock(CategoriaOrdemRepository.class);
        produtos = mock(ProdutoRepository.class);
        ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        when(repo.findByLojaIdOrderByPosicaoAsc(LOJA)).thenReturn(List.of());
        service = new CategoriaOrdemService(repo, produtos, ctx);
    }

    /** O {@code resumoDaLoja} devolve Object[]; só o índice 2 (categoria) importa aqui. */
    private void produtosNasCategorias(String... categorias) {
        List<Object[]> linhas = new ArrayList<>();
        for (String c : categorias) {
            Object[] r = new Object[10];
            r[2] = c;
            linhas.add(r);
        }
        when(produtos.resumoDaLoja(LOJA)).thenReturn(linhas);
    }

    private void ordemGravada(String... nomes) {
        List<CategoriaOrdem> lista = new ArrayList<>();
        for (int i = 0; i < nomes.length; i++) {
            CategoriaOrdem o = new CategoriaOrdem();
            o.lojaId = LOJA;
            o.nome = nomes[i];
            o.posicao = i;
            lista.add(o);
        }
        when(repo.findByLojaIdOrderByPosicaoAsc(LOJA)).thenReturn(lista);
    }

    private List<String> nomesEmOrdem() {
        return service.listar().stream().map(m -> (String) m.get("nome")).toList();
    }

    @Test
    void semOrdemGravadaContinuaAlfabetico() {
        produtosNasCategorias("Pague 4, Leve 5", "Bebidas", "Açaí Puro");

        assertEquals(List.of("Açaí Puro", "Bebidas", "Pague 4, Leve 5"), nomesEmOrdem(),
                "loja que nunca escolheu ordem tem de continuar exatamente como era antes da V64");
    }

    @Test
    void acentoNaoJogaAcategoriaParaOfim() {
        produtosNasCategorias("Bebidas", "Água e Sucos", "Açaí Puro", "Zabaione");

        assertEquals(List.of("Açaí Puro", "Água e Sucos", "Bebidas", "Zabaione"), nomesEmOrdem(),
                "'Água' tem de ficar entre 'Açaí' e 'Bebidas', onde um leitor espera achá-la");

        // A prova de que o perigo é real e não teórico -- e a medida exata do tamanho dele, que não
        // é o que eu supus: com compareTo, "Açaí Puro" fica no lugar certo (o 'A' dela é ASCII) e
        // só "Água e Sucos" despenca para depois de "Zabaione".
        List<String> comCompareTo = new ArrayList<>(List.of("Bebidas", "Água e Sucos", "Açaí Puro", "Zabaione"));
        comCompareTo.sort(String::compareTo);
        assertEquals(List.of("Açaí Puro", "Bebidas", "Zabaione", "Água e Sucos"), comCompareTo,
                "se este assert quebrar, o Java mudou e o comentário acima precisa ser remedido");
    }

    @Test
    void quemTemPosicaoGravadaVemAntesDeQuemNaoTem() {
        produtosNasCategorias("Bebidas", "Especiais para Compartilhar", "Açaí Puro", "Mundo dos Sorvetes");
        ordemGravada("Especiais para Compartilhar", "Açaí Puro");

        assertEquals(
                List.of("Especiais para Compartilhar", "Açaí Puro", "Bebidas", "Mundo dos Sorvetes"),
                nomesEmOrdem(),
                "as gravadas na ordem escolhida; as outras no fim, em A-Z");
    }

    @Test
    void categoriaQueFicouSemProdutoSomeDaLista() {
        produtosNasCategorias("Bebidas");
        ordemGravada("Especiais para Compartilhar", "Bebidas");

        assertEquals(List.of("Bebidas"), nomesEmOrdem(),
                "quem define que categorias existem é o produto, não a tabela de ordem");
    }

    @Test
    void categoriaVaziaApareceComoOutros() {
        produtosNasCategorias("Bebidas", "   ", null);

        assertEquals(List.of("Bebidas", "Outros"), nomesEmOrdem(),
                "é o nome que o cliente lê no cardápio; vazio e nulo têm de cair no mesmo balde");
    }

    @Test
    void aListaDizQuantosProdutosCadaSecaoTem() {
        produtosNasCategorias("Bebidas", "Bebidas", "Açaí Puro");

        Map<String, Object> primeira = service.listar().get(0);
        assertEquals("Açaí Puro", primeira.get("nome"));
        assertEquals(1, primeira.get("produtos"));
        assertEquals(2, service.listar().get(1).get("produtos"));
    }

    @Test
    void salvarNumeraDeZeroEmDianteEdescartaRepetido() {
        produtosNasCategorias("A", "B");
        service.salvar(List.of("B", "A", "B"));

        var capturado = org.mockito.ArgumentCaptor.forClass(CategoriaOrdem.class);
        verify(repo, times(2)).save(capturado.capture());
        List<CategoriaOrdem> gravados = capturado.getAllValues();
        assertEquals("B", gravados.get(0).nome);
        assertEquals(0, gravados.get(0).posicao);
        assertEquals("A", gravados.get(1).nome);
        assertEquals(1, gravados.get(1).posicao);
    }

    @Test
    void salvarApagaAntesDeInserirEmandaOdeleteAoBanco() {
        produtosNasCategorias("A");
        service.salvar(List.of("A"));

        org.mockito.InOrder ordem = inOrder(repo);
        ordem.verify(repo).deleteByLojaId(LOJA);
        // Sem o flush, o índice único (loja_id, nome) recusaria o INSERT de um nome que o DELETE
        // desta mesma transação ainda não tivesse mandado ao banco.
        ordem.verify(repo).flush();
        ordem.verify(repo).save(any(CategoriaOrdem.class));
    }

    @Test
    void salvarExigePapelDeGerente() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN))
                .when(ctx).requirePapel(any(String[].class));

        assertThrows(ResponseStatusException.class, () -> service.salvar(List.of("A")));
        verify(repo, never()).deleteByLojaId(anyLong());
        verify(repo, never()).save(any());
    }

    @Test
    void nomeMaiorQueAcolunaEhRecusadoAntesDeChegarAoBanco() {
        String longoDemais = "x".repeat(81);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.salvar(List.of(longoDemais)));
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(repo, never()).save(any());
    }

    @Test
    void oitentaCaracteresCabem() {
        produtosNasCategorias("x".repeat(80));
        assertDoesNotThrow(() -> service.salvar(List.of("x".repeat(80))),
                "80 é a largura de produto.categoria: tem de caber, senão a tela mente para o lojista");
    }

    @Test
    void listaGiganteEhRecusada() {
        List<String> demais = new ArrayList<>();
        for (int i = 0; i <= CategoriaOrdemService.MAXIMO_DE_CATEGORIAS; i++) demais.add("c" + i);

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.salvar(demais));
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(repo, never()).deleteByLojaId(anyLong());
    }

    /**
     * O comparador é o mesmo que o cardápio público usa para reordenar as seções, e lá ele roda
     * sobre uma lista que o banco já trouxe em nome A-Z. Como {@code List.sort} é estável, a ordem
     * dos nomes dentro de cada seção tem de sobreviver intacta.
     */
    @Test
    void reordenarPorSecaoNaoEmbaralhaOsNomesDeDentro() {
        List<String> produtosEmOrdem = new ArrayList<>(List.of(
                "Bebidas|Agua", "Bebidas|Coca", "Especiais|Barca", "Especiais|Titanic"));
        Map<String, Integer> posicoes = Map.of("Especiais", 0);

        produtosEmOrdem.sort(java.util.Comparator.comparing(
                (String s) -> s.split("\\|")[0], CategoriaOrdemService.comparador(posicoes)));

        assertEquals(List.of("Especiais|Barca", "Especiais|Titanic", "Bebidas|Agua", "Bebidas|Coca"),
                produtosEmOrdem);
    }
}
