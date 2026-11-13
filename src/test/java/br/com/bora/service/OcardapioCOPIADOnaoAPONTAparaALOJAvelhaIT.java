package br.com.bora.service;

import br.com.bora.entity.CategoriaOrdem;
import br.com.bora.entity.ComplementoGrupo;
import br.com.bora.entity.ComplementoItem;
import br.com.bora.entity.ConfiguracaoLoja;
import br.com.bora.entity.Empresa;
import br.com.bora.entity.Insumo;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Produto;
import br.com.bora.entity.ProdutoInsumo;
import br.com.bora.entity.TaxaEntrega;
import br.com.bora.repository.CategoriaOrdemRepository;
import br.com.bora.repository.ComplementoGrupoRepository;
import br.com.bora.repository.ComplementoItemRepository;
import br.com.bora.repository.ConfiguracaoLojaRepository;
import br.com.bora.repository.EmpresaRepository;
import br.com.bora.repository.InsumoRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.ProdutoInsumoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.repository.TaxaEntregaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O cardapio copiado NAO aponta para a loja velha — contra um Postgres de verdade.
 *
 * <h2>O defeito que isto fecha antes de ele existir</h2>
 * <p>Copiar cardapio e copiar <b>ponteiros</b>: o grupo de complemento aponta para o produto, o
 * item aponta para o grupo, e o item e a ficha tecnica apontam para o <b>insumo</b>. Se o id for
 * copiado cru, o cardapio da unidade nova fica apontando para o insumo da unidade velha — e uma
 * venda na loja nova baixa o estoque da outra loja, calada.</p>
 *
 * <p><b>E o banco nao pega isso.</b> A V55 criou {@code fk_comp_item_insumo}: o
 * {@code complemento_item.insumo_id} tem chave estrangeira para {@code insumo (id)} — e um id da
 * loja velha <b>satisfaz</b> essa chave, porque o insumo existe, so pertence a outra loja. A
 * restricao do banco nunca reclamaria. O remapeamento e a unica protecao que existe, e e por isso
 * que este teste assere <b>id por id</b>, e nao contagem.</p>
 *
 * <h2>Por que IT e nao teste unitario</h2>
 * <p>Porque o que se mede aqui e o id que o banco <b>gera</b>. Com repositorio dublado, os ids sao
 * os que o teste mandar — ele provaria que eu sei escrever um mapa, nao que a linha gravada aponta
 * para a linha certa.</p>
 *
 * <h2>Como rodar: precisa de um Postgres</h2>
 * <pre>
 * docker run -d --name bora-teste-pg -e POSTGRES_PASSWORD=teste -e POSTGRES_DB=bora -p 55432:5432 postgres:16
 * mvn test -Dtest='*IT'
 * </pre>
 * <p>Sem banco no ar o teste FALHA, em vez de passar calado.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:55432/bora",
        "spring.datasource.username=postgres",
        "spring.datasource.password=teste",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Import(CopiaDeCardapioService.class)
class OcardapioCOPIADOnaoAPONTAparaALOJAvelhaIT {

    @Autowired private CopiaDeCardapioService copias;
    @Autowired private EmpresaRepository empresas;
    @Autowired private LojaRepository lojas;
    @Autowired private ProdutoRepository produtos;
    @Autowired private InsumoRepository insumos;
    @Autowired private ProdutoInsumoRepository fichas;
    @Autowired private ComplementoGrupoRepository grupos;
    @Autowired private ComplementoItemRepository itens;
    @Autowired private CategoriaOrdemRepository categorias;
    @Autowired private TaxaEntregaRepository taxas;
    @Autowired private ConfiguracaoLojaRepository configs;

    private Long origem;
    private Long destino;
    private Long deOutroCliente;

    /** Ids da ORIGEM, para o teste poder dizer "nenhum destes aparece no destino". */
    private Set<Long> insumosDaOrigem;
    private Set<Long> produtosDaOrigem;
    private Set<Long> gruposDaOrigem;

    @BeforeEach
    void montarDuasUnidadesDoMesmoClienteEUmaDeOutro() {
        Empresa cliente = new Empresa();
        cliente.razaoSocial = "Rede de Teste do Cardapio";
        cliente.cnpj = "99000000000199";
        cliente = empresas.save(cliente);

        Empresa outro = new Empresa();
        outro.razaoSocial = "Concorrente";
        outro.cnpj = "99000000000288";
        outro = empresas.save(outro);

        origem = novaLoja("Unidade Matriz", cliente.id);
        destino = novaLoja("Unidade Nova", cliente.id);
        deOutroCliente = novaLoja("Loja de outro cliente", outro.id);

        // --- insumos: um que controla saldo e um que nao ---
        Long acai = novoInsumo(origem, "Acai", new BigDecimal("12.500"));
        Long granola = novoInsumo(origem, "Granola", null);
        insumosDaOrigem = Set.of(acai, granola);

        // --- produtos: um controlando estoque, um sem controlar ---
        Long copo500 = novoProduto(origem, "Copo 500ml", "Acai", new BigDecimal("22.00"), 10);
        Long copo300 = novoProduto(origem, "Copo 300ml", "Acai", new BigDecimal("16.00"), null);
        produtosDaOrigem = Set.of(copo500, copo300);

        // --- ficha tecnica do copo 500: 0,300 kg de acai ---
        ProdutoInsumo f = new ProdutoInsumo();
        f.lojaId = origem;
        f.produtoId = copo500;
        f.insumoId = acai;
        f.quantidade = new BigDecimal("0.300");
        fichas.save(f);

        // --- grupos e itens ---
        Long gTamanho = novoGrupo(origem, copo500, "Tamanho", 1, 1);
        Long gAdicionais = novoGrupo(origem, copo500, "Adicionais", 0, 5);
        gruposDaOrigem = Set.of(gTamanho, gAdicionais);
        novoItem(origem, gTamanho, "Normal", BigDecimal.ZERO, null, null);
        novoItem(origem, gAdicionais, "Granola", new BigDecimal("2.00"), granola, new BigDecimal("0.030"));
        novoItem(origem, gAdicionais, "Leite ninho", new BigDecimal("3.00"), null, null);

        CategoriaOrdem co = new CategoriaOrdem();
        co.lojaId = origem;
        co.nome = "Acai";
        co.posicao = 1;
        categorias.save(co);

        TaxaEntrega t = new TaxaEntrega();
        t.lojaId = origem;
        t.bairro = "Centro";
        t.taxa = new BigDecimal("7.00");
        t.tempoMin = 40;
        t.ativo = true;
        taxas.save(t);

        // Aparencia: a origem tem a marca do cliente; o destino nasce com o padrao do Bora.
        ConfiguracaoLoja cOrigem = new ConfiguracaoLoja();
        cOrigem.lojaId = origem;
        cOrigem.nomeExibicao = "Zira Matriz";
        cOrigem.corPrimaria = "#7C3AED";
        cOrigem.logoUrl = "https://exemplo/logo.png";
        cOrigem.cashbackPercentual = new BigDecimal("3.00");
        configs.save(cOrigem);

        ConfiguracaoLoja cDestino = new ConfiguracaoLoja();
        cDestino.lojaId = destino;
        cDestino.nomeExibicao = "Unidade Nova";
        cDestino.corPrimaria = "#2563EB";
        cDestino.cashbackPercentual = new BigDecimal("5.00");
        configs.save(cDestino);
    }

    @Test
    void oQUEfoiCOPIADObateITEMporITEM() {
        Map<String, Object> r = copias.copiar(origem, destino);

        assertEquals(2, r.get("insumos"));
        assertEquals(2, r.get("produtos"));
        assertEquals(1, r.get("fichasTecnicas"));
        assertEquals(2, r.get("gruposDeComplemento"));
        assertEquals(3, r.get("itensDeComplemento"));
        assertEquals(1, r.get("ordemDeCategorias"));
        assertEquals(1, r.get("taxasDeEntrega"));
    }

    /**
     * O TESTE QUE JUSTIFICA O ARQUIVO: nenhum ponteiro do destino cai na loja de origem.
     *
     * <p>Conferido pelos dois lados — o id gravado esta no conjunto do destino E nao esta no
     * conjunto da origem. Só a primeira metade deixaria passar um id que por azar coincidisse.</p>
     */
    @Test
    void nenhumPONTEIROdoDESTINOapontaPARAaORIGEM() {
        copias.copiar(origem, destino);

        Set<Long> insumosNovos = insumos.findByLojaIdOrderByNomeAsc(destino).stream()
                .map(i -> i.id).collect(Collectors.toSet());
        Set<Long> produtosNovos = produtos.findByLojaIdOrderByNomeAsc(destino).stream()
                .map(p -> p.id).collect(Collectors.toSet());

        List<ComplementoGrupo> gruposNovos = grupos.findByLojaIdAndProdutoIdInOrderById(destino, produtosNovos);
        assertEquals(2, gruposNovos.size(), "os grupos tem de estar pendurados nos produtos NOVOS");
        for (ComplementoGrupo g : gruposNovos) {
            assertTrue(produtosNovos.contains(g.produtoId));
            assertFalse(produtosDaOrigem.contains(g.produtoId), "grupo apontando para produto da origem");
            assertEquals(destino, g.lojaId);
        }

        Set<Long> gruposNovosIds = gruposNovos.stream().map(g -> g.id).collect(Collectors.toSet());
        List<ComplementoItem> itensNovos = itens.findByLojaIdAndGrupoIdInOrderById(destino, gruposNovosIds);
        assertEquals(3, itensNovos.size());
        for (ComplementoItem it : itensNovos) {
            assertFalse(gruposDaOrigem.contains(it.grupoId), "item apontando para grupo da origem");
            assertEquals(destino, it.lojaId);
            if (it.insumoId != null) {
                assertTrue(insumosNovos.contains(it.insumoId));
                // A FK fk_comp_item_insumo (V55) NAO pegaria este caso: o insumo da origem existe.
                assertFalse(insumosDaOrigem.contains(it.insumoId),
                        "adicional da loja nova baixaria estoque da loja velha");
            }
        }
        ComplementoItem granola = itensNovos.stream().filter(i -> "Granola".equals(i.nome)).findFirst().orElseThrow();
        assertNotNull(granola.insumoId, "o adicional que consome insumo tem de continuar consumindo");
        assertEquals(0, new BigDecimal("0.030").compareTo(granola.quantidade),
                "a quantidade que sai do estoque por unidade vendida tem de vir igual");

        List<ProdutoInsumo> fichasNovas = fichas.findByLojaIdAndProdutoIdIn(destino, produtosNovos);
        assertEquals(1, fichasNovas.size());
        assertTrue(insumosNovos.contains(fichasNovas.get(0).insumoId));
        assertFalse(insumosDaOrigem.contains(fichasNovas.get(0).insumoId),
                "ficha tecnica da loja nova consumindo insumo da loja velha");
    }

    /** O cadastro vem; o saldo nao. E nulo continua nulo, porque nulo quer dizer "nao controla". */
    @Test
    void oESTOQUEnasceZEROeoNULOcontinuaNULO() {
        copias.copiar(origem, destino);

        Produto copo500 = produtos.findByLojaIdOrderByNomeAsc(destino).stream()
                .filter(p -> "Copo 500ml".equals(p.nome)).findFirst().orElseThrow();
        Produto copo300 = produtos.findByLojaIdOrderByNomeAsc(destino).stream()
                .filter(p -> "Copo 300ml".equals(p.nome)).findFirst().orElseThrow();
        assertEquals(0, copo500.estoque.intValue(), "controlava estoque: nasce controlando, com saldo zero");
        assertNull(copo300.estoque, "nao controlava estoque: nao pode passar a controlar sozinho");
        assertEquals(new BigDecimal("22.00"), copo500.preco.setScale(2), "o preco e o mesmo do balcao");

        Insumo acai = insumos.findByLojaIdOrderByNomeAsc(destino).stream()
                .filter(i -> "Acai".equals(i.nome)).findFirst().orElseThrow();
        Insumo granola = insumos.findByLojaIdOrderByNomeAsc(destino).stream()
                .filter(i -> "Granola".equals(i.nome)).findFirst().orElseThrow();
        assertEquals(0, acai.estoque.compareTo(BigDecimal.ZERO));
        assertNull(granola.estoque);
        assertEquals(0, new BigDecimal("12.500").compareTo(acai.custo), "o custo e cadastro, vem junto");
    }

    /** Codigo do produto no marketplace e por loja: copiado, o pedido casaria com a loja errada. */
    @Test
    void oCODIGOdoMARKETPLACEnaoEcopiado() {
        Produto comCodigo = produtos.findByIdAndLojaId(produtosDaOrigem.iterator().next(), origem).orElseThrow();
        comCodigo.codigoExterno = "SKU-DA-MATRIZ";
        produtos.save(comCodigo);

        copias.copiar(origem, destino);

        assertTrue(produtos.findByLojaIdOrderByNomeAsc(destino).stream().allMatch(p -> p.codigoExterno == null),
                "o codigo externo da matriz faria o pedido do marketplace achar o produto da outra loja");
    }

    @Test
    void aMARCAvemMASoNOMEdaUNIDADEfica() {
        copias.copiar(origem, destino);

        ConfiguracaoLoja c = configs.findByLojaId(destino).orElseThrow();
        assertEquals("#7C3AED", c.corPrimaria, "a unidade nova nasce com a marca do cliente");
        assertEquals("https://exemplo/logo.png", c.logoUrl);
        assertEquals(0, new BigDecimal("3.00").compareTo(c.cashbackPercentual));
        assertEquals("Unidade Nova", c.nomeExibicao, "o nome de exibicao e da unidade, nao da matriz");
    }

    @Test
    void aSEGUNDAcopiaEhRECUSADAemVEZdeDUPLICARoCARDAPIO() {
        copias.copiar(origem, destino);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> copias.copiar(origem, destino));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertTrue(e.getReason().contains("produto"), "a recusa diz o que ja esta la: " + e.getReason());
        assertEquals(2, produtos.countByLojaId(destino), "nada foi duplicado");
    }

    @Test
    void cardapioDEoutroCLIENTEnaoSEcopia() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> copias.copiar(origem, deOutroCliente));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertTrue(e.getReason().contains("MESMO cliente"), e.getReason());
        assertEquals(0, produtos.countByLojaId(deOutroCliente), "nada atravessou a fronteira do cliente");
    }

    @Test
    void copiarParaSImesmaEhRECUSADO() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> copias.copiar(origem, origem));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertEquals(2, produtos.countByLojaId(origem), "o cardapio da origem nao dobrou");
    }

    @Test
    void lojaQUEnaoEXISTEdaNAOencontrado() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> copias.copiar(99777666L, destino));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    // ---------- montagem ----------

    private Long novaLoja(String nome, Long empresaId) {
        Loja l = new Loja();
        l.nome = nome;
        l.empresaId = empresaId;
        return lojas.save(l).id;
    }

    private Long novoInsumo(Long lojaId, String nome, BigDecimal estoque) {
        Insumo i = new Insumo();
        i.lojaId = lojaId;
        i.nome = nome;
        i.unidade = "kg";
        i.custo = new BigDecimal("12.500");
        i.estoque = estoque;
        i.estoqueMinimo = new BigDecimal("1.000");
        return insumos.save(i).id;
    }

    private Long novoProduto(Long lojaId, String nome, String categoria, BigDecimal preco, Integer estoque) {
        Produto p = new Produto();
        p.lojaId = lojaId;
        p.nome = nome;
        p.categoria = categoria;
        p.preco = preco;
        p.custo = new BigDecimal("8.00");
        p.estoque = estoque;
        p.estoqueMinimo = estoque == null ? null : 2;
        p.ativo = true;
        return produtos.save(p).id;
    }

    private Long novoGrupo(Long lojaId, Long produtoId, String nome, int min, int max) {
        ComplementoGrupo g = new ComplementoGrupo();
        g.lojaId = lojaId;
        g.produtoId = produtoId;
        g.nome = nome;
        g.minimo = min;
        g.maximo = max;
        return grupos.save(g).id;
    }

    private void novoItem(Long lojaId, Long grupoId, String nome, BigDecimal preco,
                          Long insumoId, BigDecimal quantidade) {
        ComplementoItem i = new ComplementoItem();
        i.lojaId = lojaId;
        i.grupoId = grupoId;
        i.nome = nome;
        i.preco = preco;
        i.insumoId = insumoId;
        i.quantidade = quantidade;
        itens.save(i);
    }
}
