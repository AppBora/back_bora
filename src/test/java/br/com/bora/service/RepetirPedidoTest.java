package br.com.bora.service;

import br.com.bora.entity.ComplementoGrupo;
import br.com.bora.entity.ComplementoItem;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.Produto;
import br.com.bora.repository.ComplementoGrupoRepository;
import br.com.bora.repository.ComplementoItemRepository;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.repository.ProdutoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * "Repetir o ultimo pedido" contra um cardapio que mudou.
 *
 * <p>O caso facil — nada mudou desde o pedido — nao e o que importa. O que importa e o cardapio de
 * um mes depois: produto desativado, preco reajustado, adicional removido, grupo que ficou
 * obrigatorio. Em todos esses o link tem que continuar funcionando e <b>avisar</b>, nunca entregar
 * ao cliente um carrinho diferente do que ele pediu sem dizer nada.</p>
 *
 * <p>O outro eixo e privacidade: o pedido diz o que a pessoa comeu e os ids sao sequenciais. Sem
 * assinatura valida, nao sai nada.</p>
 */
class RepetirPedidoTest {

    private static final long LOJA = 18L, PEDIDO = 105L, PRODUTO = 7L;

    private PedidoRepository pedidos;
    private PedidoItemRepository itens;
    private ProdutoRepository produtos;
    private ComplementoGrupoRepository grupos;
    private ComplementoItemRepository complementos;
    private RepetirPedidoService servico;

    private Produto copo;

    @BeforeEach
    void montar() {
        pedidos = mock(PedidoRepository.class);
        itens = mock(PedidoItemRepository.class);
        produtos = mock(ProdutoRepository.class);
        grupos = mock(ComplementoGrupoRepository.class);
        complementos = mock(ComplementoItemRepository.class);
        servico = new RepetirPedidoService(pedidos, itens, produtos, grupos, complementos,
                "segredo-de-teste-com-mais-de-32-bytes-aqui!!");

        Pedido p = new Pedido();
        p.id = PEDIDO;
        p.lojaId = LOJA;
        p.codigo = "CD-105";
        p.criadoEm = OffsetDateTime.now().minusDays(30);
        when(pedidos.findByIdAndLojaId(PEDIDO, LOJA)).thenReturn(Optional.of(p));

        copo = new Produto();
        copo.id = PRODUTO;
        copo.nome = "Copo 500ml";
        copo.preco = new BigDecimal("19.90");
        copo.ativo = true;
        when(produtos.findByIdAndLojaId(PRODUTO, LOJA)).thenReturn(Optional.of(copo));

        semComplementos();
    }

    // ---------------------------------------------------------------- utilidades

    private void semComplementos() {
        when(grupos.findByLojaIdAndProdutoIdOrderById(LOJA, PRODUTO)).thenReturn(List.of());
        when(complementos.findByLojaIdAndGrupoIdInOrderById(eq(LOJA), anyList())).thenReturn(List.of());
    }

    /** Um grupo com min/max e os adicionais que AINDA existem no cardapio de hoje. */
    private void comGrupo(int minimo, int maximo, long... idsQueAindaExistem) {
        ComplementoGrupo g = new ComplementoGrupo();
        g.id = 1L; g.lojaId = LOJA; g.produtoId = PRODUTO; g.nome = "Adicionais";
        g.minimo = minimo; g.maximo = maximo;
        when(grupos.findByLojaIdAndProdutoIdOrderById(LOJA, PRODUTO)).thenReturn(List.of(g));

        List<ComplementoItem> vivos = new ArrayList<>();
        for (long id : idsQueAindaExistem) {
            ComplementoItem ci = new ComplementoItem();
            ci.id = id; ci.lojaId = LOJA; ci.grupoId = 1L;
            ci.nome = "Adicional " + id; ci.preco = new BigDecimal("2.00");
            vivos.add(ci);
        }
        when(complementos.findByLojaIdAndGrupoIdInOrderById(eq(LOJA), anyList())).thenReturn(vivos);
    }

    private void pedidoCom(String complementosSalvos, String precoPago) {
        PedidoItem li = new PedidoItem();
        li.setLojaId(LOJA); li.setPedidoId(PEDIDO); li.setProdutoId(PRODUTO);
        li.setDescricao("Copo 500ml"); li.setQuantidade(2);
        li.setPrecoUnitario(new BigDecimal(precoPago));
        li.setComplementos(complementosSalvos);
        when(itens.findByLojaIdAndPedidoIdOrderById(LOJA, PEDIDO)).thenReturn(List.of(li));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> carrinho(Map<String, Object> r) {
        return (List<Map<String, Object>>) r.get("itens");
    }

    @SuppressWarnings("unchecked")
    private List<String> avisos(Map<String, Object> r) {
        return (List<String>) r.get("avisos");
    }

    // ---------------------------------------------------------------- o caminho feliz

    @Test
    void nadaMudou_voltaOMesmoCarrinho() {
        comGrupo(0, 4, 12L, 15L);
        pedidoCom("12,15", "23.90");

        var r = servico.montar(LOJA, PEDIDO);

        assertEquals(1, carrinho(r).size());
        assertEquals(PRODUTO, carrinho(r).get(0).get("produtoId"));
        assertEquals(2, carrinho(r).get(0).get("quantidade"), "a quantidade tem que voltar igual");
        assertEquals(List.of(12L, 15L), carrinho(r).get(0).get("complementos"));
        assertTrue(avisos(r).isEmpty(), "nada mudou, nao ha o que avisar: " + avisos(r));
    }

    // ---------------------------------------------------------------- o cardapio mudou

    @Test
    void produtoDesativado_naoEntraNoCarrinhoEAvisa() {
        copo.ativo = false;
        pedidoCom(null, "19.90");

        var r = servico.montar(LOJA, PEDIDO);

        assertTrue(carrinho(r).isEmpty(), "produto fora do cardapio nao pode entrar no carrinho");
        assertEquals(1, avisos(r).size());
        assertTrue(avisos(r).get(0).contains("saiu do cardápio"), avisos(r).get(0));
    }

    @Test
    void produtoApagado_naoQuebraOLink() {
        when(produtos.findByIdAndLojaId(PRODUTO, LOJA)).thenReturn(Optional.empty());
        pedidoCom(null, "19.90");

        var r = servico.montar(LOJA, PEDIDO);

        assertNotNull(r, "o link nao pode quebrar porque a loja apagou um produto");
        assertTrue(carrinho(r).isEmpty());
        assertEquals(1, avisos(r).size());
    }

    @Test
    void avisoDoProdutoApagado_naoRepeteAEscolhaAntiga() {
        // A descricao salva e "Copo 500ml (Granola)". O aviso fala do produto; repetir a escolha
        // antiga de um item que nem existe mais so confunde.
        copo.ativo = false;
        PedidoItem li = new PedidoItem();
        li.setLojaId(LOJA); li.setPedidoId(PEDIDO); li.setProdutoId(PRODUTO);
        li.setDescricao("Copo 500ml (Granola, Leite condensado)"); li.setQuantidade(1);
        li.setPrecoUnitario(new BigDecimal("23.90"));
        when(itens.findByLojaIdAndPedidoIdOrderById(LOJA, PEDIDO)).thenReturn(List.of(li));

        var r = servico.montar(LOJA, PEDIDO);

        assertEquals("Copo 500ml saiu do cardápio", avisos(r).get(0));
    }

    @Test
    void precoMudou_entraNoCarrinhoMasAvisa() {
        comGrupo(0, 4, 12L);
        pedidoCom("12", "21.90"); // pagou 21,90; hoje sai 19,90 + 2,00 = 21,90... reajuste:
        copo.preco = new BigDecimal("22.90");

        var r = servico.montar(LOJA, PEDIDO);

        assertEquals(1, carrinho(r).size(), "preco novo nao impede repetir, so precisa ser dito");
        assertTrue(avisos(r).stream().anyMatch(a -> a.contains("mudou de preço")), avisos(r).toString());
    }

    @Test
    void adicionalRemovidoDeGrupoOpcional_repeteSemEleEAvisa() {
        comGrupo(0, 4, 12L);       // o 15 saiu do cardapio
        pedidoCom("12,15", "25.90");

        var r = servico.montar(LOJA, PEDIDO);

        assertEquals(1, carrinho(r).size());
        assertEquals(List.of(12L), carrinho(r).get(0).get("complementos"), "so o que ainda existe");
        assertTrue(avisos(r).stream().anyMatch(a -> a.contains("não existe(m) mais")), avisos(r).toString());
    }

    @Test
    void adicionalRemovidoDeGrupoObrigatorio_pedeEscolherDeNovo() {
        // O cliente escolhia um tamanho obrigatorio, e aquele tamanho saiu. Remontar daria um item
        // invalido, que so estouraria la na frente, ao fechar o pedido.
        comGrupo(1, 1);            // obrigatorio escolher 1, e nenhum dos antigos sobreviveu
        pedidoCom("15", "19.90");

        var r = servico.montar(LOJA, PEDIDO);

        assertTrue(carrinho(r).isEmpty(), "item invalido nao pode ir para o carrinho");
        assertTrue(avisos(r).get(0).contains("escolha os adicionais de novo"), avisos(r).get(0));
    }

    @Test
    void grupoVirouObrigatorioDepois_pedeEscolherDeNovo() {
        comGrupo(1, 2, 12L);       // antes era opcional; hoje exige pelo menos 1
        pedidoCom(null, "19.90");  // o pedido antigo nao tinha nenhum

        var r = servico.montar(LOJA, PEDIDO);

        assertTrue(carrinho(r).isEmpty());
        assertTrue(avisos(r).get(0).contains("escolha os adicionais de novo"));
    }

    @Test
    void pedidoAntigoSemComplementosSalvos_repeteOProdutoQuandoOGrupoEOpcional() {
        // Item criado antes da V46: complementos = null. Com grupo opcional da para repetir o
        // produto puro, que e melhor que nao repetir nada.
        comGrupo(0, 4, 12L);
        pedidoCom(null, "19.90");

        var r = servico.montar(LOJA, PEDIDO);

        assertEquals(1, carrinho(r).size());
        assertEquals(List.of(), carrinho(r).get(0).get("complementos"));
    }

    @Test
    void produtoPerdeuOsComplementos_repeteOProdutoSozinho() {
        semComplementos();         // hoje o produto nao tem mais grupo nenhum
        pedidoCom("12,15", "25.90");

        var r = servico.montar(LOJA, PEDIDO);

        assertEquals(1, carrinho(r).size());
        assertEquals(List.of(), carrinho(r).get(0).get("complementos"));
    }

    @Test
    void idRepetidoNoRegistro_naoCobraDuasVezes() {
        comGrupo(0, 4, 12L);
        pedidoCom("12,12", "21.90");

        var r = servico.montar(LOJA, PEDIDO);

        assertEquals(List.of(12L), carrinho(r).get(0).get("complementos"), "id repetido entra uma vez só");
    }

    // ---------------------------------------------------------------- privacidade

    @Test
    void assinaturaCerta_abre() {
        assertTrue(servico.tokenValido(LOJA, PEDIDO, servico.token(LOJA, PEDIDO)));
    }

    @Test
    void semAssinatura_naoAbre() {
        assertFalse(servico.tokenValido(LOJA, PEDIDO, null));
        assertFalse(servico.tokenValido(LOJA, PEDIDO, ""));
        assertFalse(servico.tokenValido(LOJA, PEDIDO, "chute"));
    }

    @Test
    void assinaturaDeOutroPedido_naoAbreEste() {
        // Sem isto, quem tem um link proprio varre os numeros e le o pedido dos vizinhos.
        assertFalse(servico.tokenValido(LOJA, PEDIDO, servico.token(LOJA, PEDIDO + 1)));
    }

    @Test
    void assinaturaDeOutraLoja_naoAbre() {
        assertFalse(servico.tokenValido(LOJA, PEDIDO, servico.token(LOJA + 1, PEDIDO)));
    }

    @Test
    void segredoDiferente_geraAssinaturaDiferente() {
        var outro = new RepetirPedidoService(pedidos, itens, produtos, grupos, complementos,
                "outro-segredo-completamente-diferente-aqui!!");
        assertNotEquals(servico.token(LOJA, PEDIDO), outro.token(LOJA, PEDIDO));
    }

    @Test
    void pedidoDeOutraLoja_naoMonta() {
        when(pedidos.findByIdAndLojaId(PEDIDO, 99L)).thenReturn(Optional.empty());
        assertNull(servico.montar(99L, PEDIDO), "isolamento entre lojas");
    }

    // ---------------------------------------------------------------- o link do CRM

    @Test
    void clienteComPedido_ganhaLinkAssinado() {
        Pedido ultimo = new Pedido();
        ultimo.id = PEDIDO; ultimo.lojaId = LOJA;
        when(pedidos.findFirstByLojaIdAndClienteIdOrderByCriadoEmDesc(LOJA, 3L))
                .thenReturn(Optional.of(ultimo));

        String link = servico.linkDoUltimoPedido("https://borahapp.com.br", LOJA, 3L).orElseThrow();

        assertTrue(link.contains("loja=" + LOJA), link);
        assertTrue(link.contains("repetir=" + PEDIDO), link);
        assertTrue(link.contains("t=" + servico.token(LOJA, PEDIDO)), link);
    }

    @Test
    void clienteQueNuncaPediu_naoGanhaLink() {
        when(pedidos.findFirstByLojaIdAndClienteIdOrderByCriadoEmDesc(LOJA, 4L))
                .thenReturn(Optional.empty());
        assertTrue(servico.linkDoUltimoPedido("https://borahapp.com.br", LOJA, 4L).isEmpty());
        assertTrue(servico.linkDoUltimoPedido("https://borahapp.com.br", LOJA, null).isEmpty());
    }
}
