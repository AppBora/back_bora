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
import br.com.bora.service.ComplementoService.Escolhido;
import br.com.bora.service.RepetirPedidoService.CarrinhoSugerido;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * "Repetir o ultimo pedido" contra um cardapio que mudou.
 *
 * <p>O caso facil — nada mudou desde o pedido — nao e o que importa. O que importa e o cardapio de
 * um mes depois: produto desativado, preco reajustado, adicional removido, grupo que virou
 * obrigatorio, e sobretudo <b>o cardapio regravado</b>, que troca todos os ids de complemento. Em
 * todos esses o link tem que continuar funcionando e <b>avisar</b>, nunca entregar ao cliente um
 * carrinho diferente do que ele pediu sem dizer nada.</p>
 *
 * <p>O outro eixo e privacidade: o pedido diz o que a pessoa comeu e os ids sao sequenciais. Sem
 * assinatura valida nao sai nada, e o servico nem toca no banco.</p>
 */
class RepetirPedidoTest {

    private static final long LOJA = 18L, PEDIDO = 105L, PRODUTO = 7L, GRUPO = 1L;

    private PedidoRepository pedidos;
    private PedidoItemRepository itens;
    private ProdutoRepository produtos;
    private ComplementoGrupoRepository grupos;
    private ComplementoItemRepository complementos;
    private RepetirPedidoService servico;

    private Produto copo;
    private String assinatura;

    @BeforeEach
    void montar() {
        pedidos = mock(PedidoRepository.class);
        itens = mock(PedidoItemRepository.class);
        produtos = mock(ProdutoRepository.class);
        grupos = mock(ComplementoGrupoRepository.class);
        complementos = mock(ComplementoItemRepository.class);
        servico = new RepetirPedidoService(pedidos, itens, produtos, grupos, complementos,
                "segredo-de-teste-com-mais-de-32-bytes-aqui!!");
        assinatura = servico.token(LOJA, PEDIDO);

        Pedido p = new Pedido();
        p.id = PEDIDO; p.lojaId = LOJA; p.codigo = "CD-105";
        p.criadoEm = OffsetDateTime.now().minusDays(30);
        when(pedidos.findByIdAndLojaId(PEDIDO, LOJA)).thenReturn(Optional.of(p));

        copo = new Produto();
        copo.id = PRODUTO; copo.nome = "Copo 500ml"; copo.preco = new BigDecimal("19.90"); copo.ativo = true;
        when(produtos.findByLojaIdAndIdIn(eq(LOJA), anyCollection())).thenReturn(List.of(copo));

        semGrupos();
    }

    // ---------------------------------------------------------------- cenario

    private void semGrupos() {
        when(grupos.findByLojaIdAndProdutoIdInOrderById(eq(LOJA), anyCollection())).thenReturn(List.of());
        when(complementos.findByLojaIdAndGrupoIdInOrderById(eq(LOJA), anyCollection())).thenReturn(List.of());
    }

    /** O cardapio de HOJE: um grupo com min/max e os adicionais que ele tem agora. */
    private void cardapioDeHoje(int minimo, int maximo, ComplementoItem... itensDoGrupo) {
        ComplementoGrupo g = new ComplementoGrupo();
        g.id = GRUPO; g.lojaId = LOJA; g.produtoId = PRODUTO; g.nome = "Adicionais";
        g.minimo = minimo; g.maximo = maximo;
        when(grupos.findByLojaIdAndProdutoIdInOrderById(eq(LOJA), anyCollection())).thenReturn(List.of(g));
        when(complementos.findByLojaIdAndGrupoIdInOrderById(eq(LOJA), anyCollection()))
                .thenReturn(List.of(itensDoGrupo));
    }

    private static ComplementoItem adicional(long id, String nome, String preco) {
        ComplementoItem ci = new ComplementoItem();
        ci.id = id; ci.lojaId = LOJA; ci.grupoId = GRUPO; ci.nome = nome;
        ci.preco = preco == null ? null : new BigDecimal(preco);
        return ci;
    }

    /** O que o pedido ANTIGO guardou. */
    private void pedidoGuardou(String registro, String precoPago) {
        PedidoItem li = new PedidoItem();
        li.setLojaId(LOJA); li.setPedidoId(PEDIDO); li.setProdutoId(PRODUTO);
        li.setDescricao("Copo 500ml"); li.setQuantidade(2);
        li.setPrecoUnitario(precoPago == null ? null : new BigDecimal(precoPago));
        li.setComplementos(registro);
        when(itens.findByLojaIdAndPedidoIdOrderById(LOJA, PEDIDO)).thenReturn(new ArrayList<>(List.of(li)));
    }

    private String registroDe(Escolhido... escolhidos) {
        return ComplementosDoItem.escrever(List.of(escolhidos));
    }

    private CarrinhoSugerido abrir() {
        return servico.abrir(LOJA, PEDIDO, assinatura).orElseThrow();
    }

    // ---------------------------------------------------------------- o caminho feliz

    @Test
    void nadaMudou_voltaOMesmoCarrinho() {
        cardapioDeHoje(0, 4, adicional(12, "Granola", "2.00"), adicional(15, "Morango", "4.00"));
        pedidoGuardou(registroDe(new Escolhido(12L, "Granola", new BigDecimal("2.00")),
                                 new Escolhido(15L, "Morango", new BigDecimal("4.00"))), "25.90");

        var r = abrir();

        assertEquals(1, r.itens().size());
        assertEquals(PRODUTO, r.itens().get(0).produtoId());
        assertEquals(2, r.itens().get(0).quantidade(), "a quantidade tem que voltar igual");
        assertEquals(List.of(12L, 15L), r.itens().get(0).complementos());
        assertEquals(List.of(), r.avisos(), "nada mudou, nao ha o que avisar");
    }

    // ------------------------------------------------- o cardapio foi REGRAVADO (ids trocados)

    @Test
    void cardapioRegravado_reencontraOsAdicionaisPeloNome() {
        // Salvar o cardapio apaga e recria os complementos com ids novos. Casando so por id, o
        // cliente perderia os adicionais na primeira vez que a loja corrigisse um preco.
        cardapioDeHoje(0, 4, adicional(901, "Granola", "2.00"), adicional(902, "Morango", "4.00"));
        pedidoGuardou(registroDe(new Escolhido(12L, "Granola", new BigDecimal("2.00")),
                                 new Escolhido(15L, "Morango", new BigDecimal("4.00"))), "25.90");

        var r = abrir();

        assertEquals(List.of(901L, 902L), r.itens().get(0).complementos(), "ids novos, mesmos adicionais");
        assertEquals(List.of(), r.avisos(), "reencontrou tudo: nada a avisar");
    }

    @Test
    void cardapioRegravadoComPrecoNovo_reencontraEAvisaDoPreco() {
        cardapioDeHoje(0, 4, adicional(901, "Granola", "3.00"));
        pedidoGuardou(registroDe(new Escolhido(12L, "Granola", new BigDecimal("2.00"))), "21.90");

        var r = abrir();

        assertEquals(List.of(901L), r.itens().get(0).complementos());
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("mudou de preço")), r.avisos().toString());
    }

    @Test
    void nomeComCaixaEEspacoDiferentes_aindaEOMesmoAdicional() {
        cardapioDeHoje(0, 4, adicional(901, "  GRANOLA ", "2.00"));
        pedidoGuardou(registroDe(new Escolhido(12L, "Granola", new BigDecimal("2.00"))), "21.90");

        assertEquals(List.of(901L), abrir().itens().get(0).complementos());
    }

    // ---------------------------------------------------------------- o cardapio mudou

    @Test
    void produtoDesativado_naoEntraNoCarrinhoEAvisa() {
        copo.ativo = false;
        pedidoGuardou(registroDe(), "19.90");

        var r = abrir();

        assertTrue(r.itens().isEmpty(), "produto fora do cardapio nao pode entrar no carrinho");
        assertEquals(List.of("Copo 500ml saiu do cardápio"), r.avisos());
    }

    @Test
    void produtoApagado_naoQuebraOLink() {
        when(produtos.findByLojaIdAndIdIn(eq(LOJA), anyCollection())).thenReturn(List.of());
        pedidoGuardou(registroDe(), "19.90");

        var r = abrir();

        assertTrue(r.itens().isEmpty());
        assertEquals(1, r.avisos().size());
    }

    @Test
    void avisoDoProdutoApagado_naoRepeteAEscolhaAntiga() {
        copo.ativo = false;
        PedidoItem li = new PedidoItem();
        li.setLojaId(LOJA); li.setPedidoId(PEDIDO); li.setProdutoId(PRODUTO);
        li.setDescricao("Copo 500ml (Granola, Leite condensado)"); li.setQuantidade(1);
        li.setPrecoUnitario(new BigDecimal("23.90")); li.setComplementos(registroDe());
        when(itens.findByLojaIdAndPedidoIdOrderById(LOJA, PEDIDO)).thenReturn(List.of(li));

        assertEquals("Copo 500ml saiu do cardápio", abrir().avisos().get(0));
    }

    @Test
    void precoDoProdutoMudou_entraNoCarrinhoMasAvisa() {
        copo.preco = new BigDecimal("22.90");
        pedidoGuardou(registroDe(), "19.90");

        var r = abrir();

        assertEquals(1, r.itens().size(), "preco novo nao impede repetir, so precisa ser dito");
        assertEquals(List.of("Copo 500ml mudou de preço"), r.avisos());
    }

    @Test
    void adicionalRemovido_repeteSemEleEAvisaComONome() {
        cardapioDeHoje(0, 4, adicional(12, "Granola", "2.00")); // o Morango saiu de vez
        pedidoGuardou(registroDe(new Escolhido(12L, "Granola", new BigDecimal("2.00")),
                                 new Escolhido(15L, "Morango", new BigDecimal("4.00"))), "25.90");

        var r = abrir();

        assertEquals(List.of(12L), r.itens().get(0).complementos(), "so o que ainda existe");
        assertEquals(List.of("Copo 500ml: Morango não está mais no cardápio"), r.avisos(),
                "diz QUAL sumiu, e nao avisa de preco junto: o valor mudou por causa da remoção");
    }

    @Test
    void doisAdicionaisRemovidos_avisaNoPlural() {
        cardapioDeHoje(0, 4);
        pedidoGuardou(registroDe(new Escolhido(12L, "Granola", new BigDecimal("2.00")),
                                 new Escolhido(15L, "Morango", new BigDecimal("4.00"))), "25.90");

        assertEquals(List.of("Copo 500ml: Granola, Morango não estão mais no cardápio"), abrir().avisos());
    }

    @Test
    void adicionalRemovidoDeGrupoObrigatorio_pedeEscolherDeNovo() {
        // O cliente escolhia um tamanho obrigatorio, e aquele tamanho saiu. Remontar daria um item
        // invalido, que so estouraria la na frente, ao fechar o pedido.
        cardapioDeHoje(1, 1);
        pedidoGuardou(registroDe(new Escolhido(15L, "Morango", new BigDecimal("4.00"))), "23.90");

        var r = abrir();

        assertTrue(r.itens().isEmpty(), "item invalido nao pode ir para o carrinho");
        assertTrue(r.avisos().get(0).contains("escolha os adicionais de novo"), r.avisos().get(0));
    }

    @Test
    void grupoVirouObrigatorioDepois_pedeEscolherDeNovo() {
        cardapioDeHoje(1, 2, adicional(12, "Granola", "2.00"));
        pedidoGuardou(registroDe(), "19.90"); // o pedido antigo nao tinha nenhum

        assertTrue(abrir().itens().isEmpty());
        assertTrue(abrir().avisos().get(0).contains("escolha os adicionais de novo"));
    }

    @Test
    void lojaApertouOMaximoDepois_pedeEscolherDeNovo() {
        // Antes dava para levar 3 adicionais; hoje so 1. Remontar os 3 daria item que o pedido
        // recusaria no fim. Esta mutacao (ignorar o maximo) passava sem teste nenhum.
        cardapioDeHoje(0, 1, adicional(12, "Granola", "2.00"), adicional(15, "Morango", "4.00"));
        pedidoGuardou(registroDe(new Escolhido(12L, "Granola", new BigDecimal("2.00")),
                                 new Escolhido(15L, "Morango", new BigDecimal("4.00"))), "25.90");

        var r = abrir();

        assertTrue(r.itens().isEmpty());
        assertTrue(r.avisos().get(0).contains("escolha os adicionais de novo"));
    }

    @Test
    void produtoPerdeuOsComplementos_repeteOProdutoSozinhoEAvisa() {
        semGrupos();
        pedidoGuardou(registroDe(new Escolhido(12L, "Granola", new BigDecimal("2.00"))), "21.90");

        var r = abrir();

        assertEquals(1, r.itens().size());
        assertEquals(List.of(), r.itens().get(0).complementos());
        assertEquals(List.of("Copo 500ml: Granola não está mais no cardápio"), r.avisos());
    }

    @Test
    void adicionalComPrecoNulo_naoQuebra() {
        cardapioDeHoje(0, 4, adicional(12, "Granola", null));
        pedidoGuardou(registroDe(new Escolhido(12L, "Granola", null)), "19.90");

        assertEquals(List.of(12L), abrir().itens().get(0).complementos());
    }

    // ---------------------------------------------------------------- pedido antigo e marketplace

    @Test
    void pedidoAnteriorAoRegistro_pedeEscolherDeNovoQuandoOProdutoTemAdicionais() {
        // Item com complementos nulo: nao se sabe o que foi escolhido. Antes isso virava "produto
        // puro, sem aviso" — o cliente recebia um acai sem o que sempre pede e ninguem avisava.
        cardapioDeHoje(0, 4, adicional(12, "Granola", "2.00"));
        pedidoGuardou(null, "23.90");

        var r = abrir();

        assertTrue(r.itens().isEmpty());
        assertTrue(r.avisos().get(0).contains("esse pedido é antigo"), r.avisos().get(0));
    }

    @Test
    void pedidoAnteriorAoRegistro_repeteOProdutoQuandoEleNuncaTeveAdicional() {
        semGrupos();
        pedidoGuardou(null, "19.90");

        var r = abrir();

        assertEquals(1, r.itens().size(), "sem grupo nenhum, nao havia o que escolher");
        assertEquals(List.of(), r.avisos());
    }

    @Test
    void itemDeMarketplace_naoRemontaEExplicaPorque() {
        // criarExterno nao grava produtoId: nao ha o que remontar. Este ramo nao tinha teste.
        PedidoItem li = new PedidoItem();
        li.setLojaId(LOJA); li.setPedidoId(PEDIDO); li.setProdutoId(null);
        li.setDescricao("Acai 500ml (sem banana)"); li.setQuantidade(1);
        when(itens.findByLojaIdAndPedidoIdOrderById(LOJA, PEDIDO)).thenReturn(List.of(li));

        var r = abrir();

        assertTrue(r.itens().isEmpty());
        assertEquals(List.of("Acai 500ml veio de aplicativo e não pode ser repetido por aqui"), r.avisos());
    }

    // ---------------------------------------------------------------- varios itens

    @Test
    void carrinhoComVariosItens_repeteOQueDaEAvisaDoResto() {
        Produto agua = new Produto();
        agua.id = 9L; agua.nome = "Água"; agua.preco = new BigDecimal("4.00"); agua.ativo = false;
        when(produtos.findByLojaIdAndIdIn(eq(LOJA), anyCollection())).thenReturn(List.of(copo, agua));

        PedidoItem bom = new PedidoItem();
        bom.setLojaId(LOJA); bom.setPedidoId(PEDIDO); bom.setProdutoId(PRODUTO);
        bom.setDescricao("Copo 500ml"); bom.setQuantidade(1);
        bom.setPrecoUnitario(new BigDecimal("19.90")); bom.setComplementos(registroDe());
        PedidoItem ruim = new PedidoItem();
        ruim.setLojaId(LOJA); ruim.setPedidoId(PEDIDO); ruim.setProdutoId(9L);
        ruim.setDescricao("Água"); ruim.setQuantidade(1);
        ruim.setPrecoUnitario(new BigDecimal("4.00")); ruim.setComplementos(registroDe());
        when(itens.findByLojaIdAndPedidoIdOrderById(LOJA, PEDIDO)).thenReturn(List.of(bom, ruim));

        var r = abrir();

        assertEquals(1, r.itens().size(), "o que da, vai");
        assertEquals(PRODUTO, r.itens().get(0).produtoId());
        assertEquals(List.of("Água saiu do cardápio"), r.avisos());
    }

    @Test
    void quantidadeInvalida_viraUm() {
        PedidoItem li = new PedidoItem();
        li.setLojaId(LOJA); li.setPedidoId(PEDIDO); li.setProdutoId(PRODUTO);
        li.setDescricao("Copo 500ml"); li.setQuantidade(0);
        li.setPrecoUnitario(new BigDecimal("19.90")); li.setComplementos(registroDe());
        when(itens.findByLojaIdAndPedidoIdOrderById(LOJA, PEDIDO)).thenReturn(List.of(li));

        assertEquals(1, abrir().itens().get(0).quantidade());
    }

    // ---------------------------------------------------------------- privacidade

    @Test
    void semAssinatura_naoAbreENaoToacaNoBanco() {
        assertTrue(servico.abrir(LOJA, PEDIDO, null).isEmpty());
        assertTrue(servico.abrir(LOJA, PEDIDO, "").isEmpty());
        assertTrue(servico.abrir(LOJA, PEDIDO, "chute").isEmpty());
        verify(pedidos, never()).findByIdAndLojaId(any(), any());
    }

    @Test
    void assinaturaDeOutroPedido_naoAbreEste() {
        // Sem isto, quem tem um link proprio varre os numeros e le o pedido dos vizinhos.
        assertTrue(servico.abrir(LOJA, PEDIDO, servico.token(LOJA, PEDIDO + 1)).isEmpty());
    }

    @Test
    void assinaturaDeOutraLoja_naoAbre() {
        assertFalse(servico.tokenValido(LOJA, PEDIDO, servico.token(LOJA + 1, PEDIDO)));
    }

    @Test
    void segredoDiferente_naoValidaOTokenDoOutro() {
        var outro = new RepetirPedidoService(pedidos, itens, produtos, grupos, complementos,
                "outro-segredo-completamente-diferente-aqui!!");

        assertFalse(outro.tokenValido(LOJA, PEDIDO, assinatura));
    }

    @Test
    void pedidoDeOutraLoja_naoAbre() {
        assertTrue(servico.abrir(99L, PEDIDO, servico.token(99L, PEDIDO)).isEmpty(), "isolamento entre lojas");
    }

    // ---------------------------------------------------------------- os links do CRM

    @Test
    void linksDosUltimosPedidos_umPorClienteComPedidoRepetivel() {
        when(pedidos.ultimoPedidoRepetivelPorCliente(LOJA))
                .thenReturn(List.of(new Object[]{3L, PEDIDO}, new Object[]{4L, 200L}));

        var links = servico.linksDosUltimosPedidos("https://borahapp.com.br", LOJA);

        assertEquals(2, links.size());
        assertTrue(links.get(3L).contains("loja=" + LOJA), links.get(3L));
        assertTrue(links.get(3L).contains("repetir=" + PEDIDO + "&"), links.get(3L));
        assertTrue(links.get(3L).endsWith("t=" + servico.token(LOJA, PEDIDO)), links.get(3L));
    }

    @Test
    void nenhumClienteComPedidoRepetivel_mapaVazio() {
        when(pedidos.ultimoPedidoRepetivelPorCliente(LOJA)).thenReturn(List.of());

        assertTrue(servico.linksDosUltimosPedidos("https://borahapp.com.br", LOJA).isEmpty());
    }

    @Test
    void umaConsultaSo_eNaoUmaPorCliente() {
        // O laco anterior fazia 501 idas ao banco numa loja de 500 clientes.
        when(pedidos.ultimoPedidoRepetivelPorCliente(LOJA))
                .thenReturn(List.of(new Object[]{1L, 10L}, new Object[]{2L, 20L}, new Object[]{3L, 30L}));

        servico.linksDosUltimosPedidos("https://borahapp.com.br", LOJA);

        verify(pedidos, times(1)).ultimoPedidoRepetivelPorCliente(LOJA);
        verify(pedidos, never()).findFirstByLojaIdAndClienteIdOrderByCriadoEmDesc(any(), any());
    }

    // ---------------------------------------------------------------- custo

    @Test
    void pedidoDeVariosItens_consultaOCardapioEmLote() {
        // Eram 3 consultas por linha do pedido. Num pedido de 6 itens, 18 idas ao banco num
        // endpoint publico.
        cardapioDeHoje(0, 4, adicional(12, "Granola", "2.00"));
        List<PedidoItem> muitos = new ArrayList<>();
        for (int n = 0; n < 6; n++) {
            PedidoItem li = new PedidoItem();
            li.setLojaId(LOJA); li.setPedidoId(PEDIDO); li.setProdutoId(PRODUTO);
            li.setDescricao("Copo 500ml"); li.setQuantidade(1);
            li.setPrecoUnitario(new BigDecimal("21.90"));
            li.setComplementos(registroDe(new Escolhido(12L, "Granola", new BigDecimal("2.00"))));
            muitos.add(li);
        }
        when(itens.findByLojaIdAndPedidoIdOrderById(LOJA, PEDIDO)).thenReturn(muitos);

        assertEquals(6, abrir().itens().size());
        verify(produtos, times(1)).findByLojaIdAndIdIn(eq(LOJA), anyCollection());
        verify(grupos, times(1)).findByLojaIdAndProdutoIdInOrderById(eq(LOJA), anyCollection());
        verify(complementos, times(1)).findByLojaIdAndGrupoIdInOrderById(eq(LOJA), anyCollection());
    }
}
