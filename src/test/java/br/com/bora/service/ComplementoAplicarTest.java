package br.com.bora.service;

import br.com.bora.entity.ComplementoGrupo;
import br.com.bora.entity.ComplementoItem;
import br.com.bora.entity.Produto;
import br.com.bora.repository.ComplementoGrupoRepository;
import br.com.bora.repository.ComplementoItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A regra dos complementos, medida no serviço de verdade.
 *
 * <h2>Por que esta classe existe</h2>
 * <p>Achado por mutação em 21/10/2026: trocar
 * {@code new Escolhido(ci.id, ci.nome, preco, ci.insumoId, ci.quantidade)} pela versão de três
 * argumentos passou com <b>99 testes verdes</b>, incluindo todos os que eu acabei de escrever para o
 * adicional baixar estoque. O motivo: os dois canais que criam pedido <b>mockam</b> o
 * {@code aplicar}, então nenhum teste exercitava o método real. Com essa mutação, nada consumiria
 * insumo de adicional nunca — o recurso inteiro ficaria morto e a suíte inteira verde.</p>
 *
 * <p>Medindo, apareceu que a lacuna era maior e anterior a mim: <b>o {@code aplicar} não tinha teste
 * nenhum</b>. O acréscimo de preço, a validação de mínimo e máximo e a recusa de complemento de outro
 * produto — tudo isso é regra de dinheiro e de pedido, e só era coberta de lado, pelos mocks dos dois
 * canais.</p>
 */
class ComplementoAplicarTest {

    private ComplementoGrupoRepository grupos;
    private ComplementoItemRepository itens;
    private ComplementoService service;

    private static final Long LOJA = 3L;
    private static final Long PRODUTO = 10L;

    private Produto produto;
    private final List<ComplementoItem> catalogo = new ArrayList<>();

    @BeforeEach
    void montar() {
        grupos = mock(ComplementoGrupoRepository.class);
        itens = mock(ComplementoItemRepository.class);
        service = new ComplementoService(grupos, itens);

        produto = new Produto();
        produto.id = PRODUTO;
        produto.lojaId = LOJA;
        produto.nome = "Açaí 500ml";
        catalogo.clear();
    }

    private ComplementoGrupo grupo(long id, String nome, int min, int max) {
        ComplementoGrupo g = new ComplementoGrupo();
        g.id = id;
        g.lojaId = LOJA;
        g.produtoId = PRODUTO;
        g.nome = nome;
        g.minimo = min;
        g.maximo = max;
        return g;
    }

    private ComplementoItem opcao(long id, long grupoId, String nome, String preco,
                                  Long insumoId, String quantidade) {
        ComplementoItem ci = new ComplementoItem();
        ci.id = id;
        ci.lojaId = LOJA;
        ci.grupoId = grupoId;
        ci.nome = nome;
        ci.preco = new BigDecimal(preco);
        ci.insumoId = insumoId;
        ci.quantidade = quantidade == null ? null : new BigDecimal(quantidade);
        catalogo.add(ci);
        return ci;
    }

    private void noCadastro(ComplementoGrupo... gs) {
        when(grupos.findByLojaIdAndProdutoIdOrderById(LOJA, PRODUTO)).thenReturn(List.of(gs));
        when(itens.findByLojaIdAndGrupoIdInOrderById(eq(LOJA), anyList())).thenReturn(catalogo);
    }

    // =============================================================== o insumo viaja (a mutação)

    @Test
    void oINSUMOeaQUANTIDADEviajamDOcadastroPARAoQUEfoiESCOLHIDO() {
        // A mutação que passou com 99 testes verdes: devolver o Escolhido de tres argumentos. Com ela
        // nada consome insumo de adicional NUNCA -- o recurso fica morto e nada acusa.
        noCadastro(grupo(1L, "Adicionais", 0, 3));
        opcao(50L, 1L, "Granola", "3.00", 70L, "0.030");

        var escolha = service.aplicar(LOJA, produto, List.of(50L));

        assertEquals(70L, escolha.escolhidos().get(0).insumoId(),
                "sem o insumo aqui, nada baixa estoque em canal nenhum");
        assertEquals(0, escolha.escolhidos().get(0).quantidade().compareTo(new BigDecimal("0.030")));
        assertTrue(escolha.escolhidos().get(0).consomeInsumo());
    }

    @Test
    void adicionalSEMinsumoVEMsemINSUMO_eNAOconsome() {
        // O padrao, e todo adicional cadastrado antes da V55.
        noCadastro(grupo(1L, "Adicionais", 0, 3));
        opcao(50L, 1L, "Borda recheada", "8.00", null, null);

        var escolha = service.aplicar(LOJA, produto, List.of(50L));

        assertNull(escolha.escolhidos().get(0).insumoId());
        assertFalse(escolha.escolhidos().get(0).consomeInsumo());
        assertTrue(escolha.comInsumo().isEmpty());
    }

    @Test
    void comInsumoFILTRAsoOQUEbaixaESTOQUE() {
        noCadastro(grupo(1L, "Adicionais", 0, 3));
        opcao(50L, 1L, "Granola", "3.00", 70L, "0.030");
        opcao(51L, 1L, "Borda recheada", "8.00", null, null);

        var escolha = service.aplicar(LOJA, produto, List.of(50L, 51L));

        assertEquals(2, escolha.escolhidos().size());
        assertEquals(1, escolha.comInsumo().size());
        assertEquals("Granola", escolha.comInsumo().get(0).nome());
    }

    // =============================================================== a conta

    @Test
    void oACRESCIMOsomaOPRECOdasOPCOESescolhidas() {
        noCadastro(grupo(1L, "Adicionais", 0, 5));
        opcao(50L, 1L, "Granola", "3.00", null, null);
        opcao(51L, 1L, "Leite Ninho", "4.50", null, null);
        opcao(52L, 1L, "Morango", "6.00", null, null);

        var escolha = service.aplicar(LOJA, produto, List.of(50L, 52L));

        assertEquals(0, escolha.acrescimo().compareTo(new BigDecimal("9.00")), "3,00 + 6,00");
    }

    @Test
    void oPRECOgravadoEHoDOcadastro() {
        // O cliente manda o ID, nunca o preco. Esta e a linha que impede o pedido de chegar com o
        // adicional "de graca" direto no corpo da requisicao.
        noCadastro(grupo(1L, "Adicionais", 0, 3));
        opcao(50L, 1L, "Granola", "3.00", null, null);

        var escolha = service.aplicar(LOJA, produto, List.of(50L));

        assertEquals(0, escolha.escolhidos().get(0).preco().compareTo(new BigDecimal("3.00")));
    }

    @Test
    void opcaoSEMprecoCONTAcomoZERO() {
        noCadastro(grupo(1L, "Tamanho", 1, 1));
        ComplementoItem p = opcao(50L, 1L, "500ml", "0.00", null, null);
        p.preco = null;

        var escolha = service.aplicar(LOJA, produto, List.of(50L));

        assertEquals(0, escolha.acrescimo().signum());
    }

    @Test
    void aDESCRICAOjuntaONOMEdoPRODUTOcomOSadicionais() {
        noCadastro(grupo(1L, "Adicionais", 0, 3));
        opcao(50L, 1L, "Granola", "3.00", null, null);

        var escolha = service.aplicar(LOJA, produto, List.of(50L));

        assertEquals("Açaí 500ml (Granola)", escolha.descricao("Açaí 500ml"));
    }

    // =============================================================== o que é recusado

    @Test
    void complementoDEoutroPRODUTOehRECUSADO() {
        noCadastro(grupo(1L, "Adicionais", 0, 3));
        opcao(50L, 1L, "Granola", "3.00", null, null);

        var erro = assertThrows(ResponseStatusException.class,
                () -> service.aplicar(LOJA, produto, List.of(999L)));

        assertEquals(HttpStatus.BAD_REQUEST, erro.getStatusCode());
        assertTrue(erro.getReason().contains("Açaí 500ml"), "foi: " + erro.getReason());
    }

    @Test
    void menosQueOMINIMOehRECUSADO() {
        // Grupo "Tamanho" com minimo 1: deixar passar sem escolha gravaria o item com preco abaixo do
        // real -- numa acaiteria, onde o tamanho e o preco, isso e prejuizo direto.
        noCadastro(grupo(1L, "Tamanho", 1, 1));
        opcao(50L, 1L, "500ml", "0.00", null, null);

        var erro = assertThrows(ResponseStatusException.class,
                () -> service.aplicar(LOJA, produto, List.of()));

        assertEquals(HttpStatus.BAD_REQUEST, erro.getStatusCode());
        assertTrue(erro.getReason().contains("Tamanho"), "foi: " + erro.getReason());
    }

    @Test
    void maisQueOMAXIMOehRECUSADO() {
        noCadastro(grupo(1L, "Tamanho", 1, 1));
        opcao(50L, 1L, "500ml", "0.00", null, null);
        opcao(51L, 1L, "700ml", "5.00", null, null);

        assertThrows(ResponseStatusException.class,
                () -> service.aplicar(LOJA, produto, List.of(50L, 51L)));
    }

    @Test
    void produtoSEMgrupoRECUSAcomplementoENVIADO() {
        when(grupos.findByLojaIdAndProdutoIdOrderById(LOJA, PRODUTO)).thenReturn(List.of());

        var erro = assertThrows(ResponseStatusException.class,
                () -> service.aplicar(LOJA, produto, List.of(50L)));

        assertTrue(erro.getReason().contains("não tem complementos"), "foi: " + erro.getReason());
    }

    // =============================================================== os caminhos tranquilos

    @Test
    void produtoSEMgrupoESEMescolhaPASSA() {
        when(grupos.findByLojaIdAndProdutoIdOrderById(LOJA, PRODUTO)).thenReturn(List.of());

        var escolha = service.aplicar(LOJA, produto, List.of());

        assertEquals(0, escolha.acrescimo().signum());
        assertTrue(escolha.escolhidos().isEmpty());
    }

    @Test
    void grupoOPCIONALsemESCOLHApassa() {
        noCadastro(grupo(1L, "Adicionais", 0, 3));
        opcao(50L, 1L, "Granola", "3.00", 70L, "0.030");

        var escolha = service.aplicar(LOJA, produto, List.of());

        assertEquals(0, escolha.acrescimo().signum());
        assertTrue(escolha.comInsumo().isEmpty(), "sem escolha nao ha insumo para consumir");
    }

    @Test
    void escolhaNULAvaleComoVAZIA() {
        noCadastro(grupo(1L, "Adicionais", 0, 3));
        opcao(50L, 1L, "Granola", "3.00", null, null);

        assertDoesNotThrow(() -> service.aplicar(LOJA, produto, null));
    }

    @Test
    void grupoSEMminimoEmaximoUSAopadrao_nadaOBRIGATORIOeUMAescolha() {
        // Os padroes moram no servico de proposito: quem repete um pedido antigo precisa aplicar
        // exatamente a mesma regua.
        ComplementoGrupo g = grupo(1L, "Adicionais", 0, 1);
        g.minimo = null;
        g.maximo = null;
        noCadastro(g);
        opcao(50L, 1L, "Granola", "3.00", null, null);

        assertDoesNotThrow(() -> service.aplicar(LOJA, produto, List.of()), "nada obrigatorio");
        assertDoesNotThrow(() -> service.aplicar(LOJA, produto, List.of(50L)), "uma escolha cabe");
        assertEquals(0, ComplementoService.minimoDe(g));
        assertEquals(1, ComplementoService.maximoDe(g));
    }

    @Test
    void doisGRUPOSsaoVALIDADOSseparadamente() {
        noCadastro(grupo(1L, "Tamanho", 1, 1), grupo(2L, "Adicionais", 0, 2));
        opcao(50L, 1L, "500ml", "0.00", null, null);
        opcao(60L, 2L, "Granola", "3.00", 70L, "0.030");
        opcao(61L, 2L, "Morango", "6.00", 71L, "0.050");

        var escolha = service.aplicar(LOJA, produto, List.of(50L, 60L, 61L));

        assertEquals(0, escolha.acrescimo().compareTo(new BigDecimal("9.00")));
        assertEquals(2, escolha.comInsumo().size(), "os dois adicionais com insumo");
    }
}
