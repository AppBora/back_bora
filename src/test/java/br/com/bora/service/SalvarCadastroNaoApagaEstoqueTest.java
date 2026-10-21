package br.com.bora.service;

import br.com.bora.dto.MexerNoEstoqueRequest;
import br.com.bora.dto.MexerNoEstoqueRequest.Tipo;
import br.com.bora.entity.Insumo;
import br.com.bora.entity.Produto;
import br.com.bora.repository.InsumoRepository;
import br.com.bora.repository.ProdutoInsumoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Salvar o cadastro não pode mexer no estoque. Nem no do produto, nem no do insumo.
 *
 * <h2>O defeito que isto trava (medido em 20/10/2026)</h2>
 * <p>{@code ProdutoService.atualizar} fazia {@code p.estoque = dados.estoque} <b>sem condição</b>, e o
 * formulário mandava o número que a tela havia carregado — talvez horas antes. O lojista abria
 * Produtos às 18h, a loja vendia 12 durante a noite, ele corrigia só o <b>preço</b> às 21h, e as 12
 * baixas desapareciam. Sem log, sem aviso. {@code InsumoService.salvar} tinha o mesmo
 * {@code alvo.estoque = i.estoque}.</p>
 *
 * <h2>E o outro lado do mesmo cano, que eu mesmo criei na v119</h2>
 * <p>As telas de Estoque e de Contagem ajustavam estoque mandando o <b>cadastro inteiro</b> de volta
 * pelo mesmo {@code PUT /api/produtos/{id}}. O cadastro que elas conhecem não tem todos os campos:
 * nenhuma das duas manda {@code codigoExterno}. Como o {@code atualizar} gravava esse campo sem
 * condição, <b>cada ajuste de estoque apagaria o código do marketplace</b> que entrou na v119 — e com
 * ele a baixa de estoque dos pedidos de iFood/99.</p>
 *
 * <h2>Por que a solução foi separar e não proteger</h2>
 * <p>Eram duas operações passando pelo mesmo cano: "editar o cadastro" e "dizer quanto tem na
 * prateleira". Enquanto compartilhassem a requisição, qualquer tela com um cadastro incompleto apaga o
 * que não conhece — e o próximo campo que alguém adicionar cai no mesmo buraco. Separando, o cadastro
 * <b>não tem como</b> mexer no estoque.</p>
 */
class SalvarCadastroNaoApagaEstoqueTest {

    private ProdutoRepository repo;
    private ProdutoService produtos;
    private InsumoRepository insumoRepo;
    private InsumoService insumos;

    private static final Long LOJA = 1L;

    @BeforeEach
    void montar() {
        repo = mock(ProdutoRepository.class);
        insumoRepo = mock(InsumoRepository.class);
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        produtos = new ProdutoService(repo, mock(br.com.bora.repository.ProdutoInsumoRepository.class), mock(br.com.bora.repository.ComplementoGrupoRepository.class), mock(br.com.bora.repository.ComplementoItemRepository.class), ctx);
        insumos = new InsumoService(insumoRepo, mock(ProdutoInsumoRepository.class), repo, mock(br.com.bora.repository.ComplementoItemRepository.class), ctx);
        when(repo.save(any(Produto.class))).thenAnswer(i -> i.getArgument(0));
        when(insumoRepo.save(any(Insumo.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** O produto como está no banco AGORA: 18 em estoque, depois das vendas da noite. */
    private Produto noBanco() {
        Produto p = new Produto();
        p.id = 11L;
        p.lojaId = LOJA;
        p.nome = "Açaí 500ml";
        p.preco = new BigDecimal("24.00");
        p.estoque = 18;
        p.estoqueMinimo = 5;
        p.codigoExterno = "7470";
        when(repo.findByIdAndLojaId(11L, LOJA)).thenReturn(Optional.of(p));
        return p;
    }

    /** O que o formulário manda: carregado às 18h, quando o estoque era 30. */
    private Produto formularioDas18h() {
        Produto f = new Produto();
        f.nome = "Açaí 500ml";
        f.preco = new BigDecimal("26.00");    // o lojista veio corrigir O PRECO
        f.estoque = 30;                        // numero velho da tela
        f.estoqueMinimo = 5;
        f.codigoExterno = "7470";
        return f;
    }

    // ======================================================================= produto

    @Test
    void corrigirOPRECOas21hNAOapagaAsVendasDaNoite() {
        Produto p = noBanco();

        Produto salvo = produtos.atualizar(11L, formularioDas18h());

        assertEquals(18, salvo.estoque,
                "o estoque tem que continuar o do BANCO (18), nao o da tela (30)");
        assertEquals(0, salvo.preco.compareTo(new BigDecimal("26.00")),
                "e o preco, que era o que ele veio mudar, tem que ter mudado");
        assertSame(p, salvo);
    }

    @Test
    void oCadastroNAOtemComoChamarAsConsultasDeEstoque() {
        noBanco();

        produtos.atualizar(11L, formularioDas18h());

        verify(repo, never()).definirEstoque(anyLong(), anyLong(), any());
        verify(repo, never()).somarAoEstoque(anyLong(), anyLong(), anyInt());
        verify(repo, never()).baixarEstoque(anyLong(), anyLong(), anyInt());
    }

    @Test
    void oCADASTROcontinuaSalvandoTudoQueNAOehEstoque() {
        // O conserto nao pode virar "atualizar nao atualiza nada".
        noBanco();
        Produto f = formularioDas18h();
        f.nome = "Açaí 500ml Premium";
        f.categoria = "Açaí";
        f.custo = new BigDecimal("9.00");
        f.estoqueMinimo = 8;
        f.codigoExterno = "9999";

        Produto salvo = produtos.atualizar(11L, f);

        assertEquals("Açaí 500ml Premium", salvo.nome);
        assertEquals("Açaí", salvo.categoria);
        assertEquals(0, salvo.custo.compareTo(new BigDecimal("9.00")));
        assertEquals(8, salvo.estoqueMinimo, "o estoque MINIMO e cadastro, nao saldo: continua salvando");
        assertEquals("9999", salvo.codigoExterno);
    }

    @Test
    void aCRIACAOcontinuaAceitandoAquantidadeInicial() {
        // Na criacao nao existe numero velho: o que o lojista digitou e o estoque inicial.
        Produto novo = new Produto();
        novo.nome = "Copo 300ml";
        novo.preco = new BigDecimal("12.00");
        novo.estoque = 40;

        Produto salvo = produtos.criar(novo);

        assertEquals(40, salvo.estoque, "quantidade inicial na criacao tem que valer");
    }

    // ======================================================================= o endpoint de estoque

    @Test
    void DEFINIRgravaOvalorContado_mesmoComVendaNoMeio() {
        // O lojista andou na prateleira e contou 25. O numero dele e a verdade, mesmo que o sistema
        // diga 18 -- e o ponto de uma contagem.
        Produto p = noBanco();
        when(repo.findByIdAndLojaId(11L, LOJA)).thenReturn(Optional.of(p));

        produtos.mexerNoEstoque(11L, new MexerNoEstoqueRequest(Tipo.DEFINIR, new BigDecimal("25")));

        verify(repo).definirEstoque(11L, LOJA, 25);
        verify(repo, never()).save(any(Produto.class));
    }

    @Test
    void DEFINIRcomNULOfazOprodutoPARARdeControlarEstoque() {
        // O "vazio = nao controla" da tela. Nulo nao e ausencia aqui: e uma escolha.
        noBanco();

        produtos.mexerNoEstoque(11L, new MexerNoEstoqueRequest(Tipo.DEFINIR, null));

        verify(repo).definirEstoque(11L, LOJA, null);
    }

    @Test
    void DEFINIRnegativoEhRecusado() {
        noBanco();

        var erro = assertThrows(ResponseStatusException.class, () ->
                produtos.mexerNoEstoque(11L, new MexerNoEstoqueRequest(Tipo.DEFINIR, new BigDecimal("-1"))));

        assertEquals(HttpStatus.BAD_REQUEST, erro.getStatusCode());
        verify(repo, never()).definirEstoque(anyLong(), anyLong(), any());
    }

    @Test
    void AJUSTARsomaNOBANCO_naoNoNavegador() {
        // O "+1" da tela de Estoque. A conta tem que ser do banco: antes era
        // `(p.estoque || 0) + delta` no navegador, a partir de uma lista de minutos antes, e gravado
        // como valor ABSOLUTO -- clicar "+1" as 21h com a lista de 18h gravava 31 e apagava as vendas.
        noBanco();
        when(repo.somarAoEstoque(11L, LOJA, 1)).thenReturn(1);

        produtos.mexerNoEstoque(11L, new MexerNoEstoqueRequest(Tipo.AJUSTAR, new BigDecimal("1")));

        verify(repo).somarAoEstoque(11L, LOJA, 1);
        verify(repo, never()).definirEstoque(anyLong(), anyLong(), any());
    }

    @Test
    void AJUSTARqueDeixariaNegativoEhRecusadoComOSALDODEAGORA() {
        Produto p = noBanco();
        when(repo.somarAoEstoque(anyLong(), anyLong(), anyInt())).thenReturn(0);
        p.estoque = 2;   // o que o banco diz agora, depois de mais vendas

        var erro = assertThrows(ResponseStatusException.class, () ->
                produtos.mexerNoEstoque(11L, new MexerNoEstoqueRequest(Tipo.AJUSTAR, new BigDecimal("-5"))));

        assertEquals(HttpStatus.CONFLICT, erro.getStatusCode());
        assertTrue(erro.getReason().contains("2"),
                "o lojista precisa ler o saldo DE AGORA, nao o da tela dele; a mensagem foi: "
                        + erro.getReason());
        assertTrue(erro.getReason().contains("Açaí 500ml"), "e qual produto");
    }

    @Test
    void AJUSTARemProdutoQueNAOcontrolaEstoqueExplicaOqueFazer() {
        Produto p = noBanco();
        p.estoque = null;

        var erro = assertThrows(ResponseStatusException.class, () ->
                produtos.mexerNoEstoque(11L, new MexerNoEstoqueRequest(Tipo.AJUSTAR, new BigDecimal("1"))));

        assertEquals(HttpStatus.CONFLICT, erro.getStatusCode());
        assertTrue(erro.getReason().contains("quantidade inicial"),
                "a mensagem tem que dizer o que fazer; foi: " + erro.getReason());
    }

    @Test
    void AJUSTARdeZEROehRecusado() {
        // Somar zero nao e ajuste nenhum, e deixaria o lojista achando que fez algo.
        noBanco();

        assertThrows(ResponseStatusException.class, () ->
                produtos.mexerNoEstoque(11L, new MexerNoEstoqueRequest(Tipo.AJUSTAR, BigDecimal.ZERO)));
        assertThrows(ResponseStatusException.class, () ->
                produtos.mexerNoEstoque(11L, new MexerNoEstoqueRequest(Tipo.AJUSTAR, null)));
        verify(repo, never()).somarAoEstoque(anyLong(), anyLong(), anyInt());
    }

    @Test
    void semTIPOehRecusado() {
        noBanco();

        assertThrows(ResponseStatusException.class, () ->
                produtos.mexerNoEstoque(11L, new MexerNoEstoqueRequest(null, new BigDecimal("5"))));
        assertThrows(ResponseStatusException.class, () -> produtos.mexerNoEstoque(11L, null));
    }

    // ======================================================================= a consulta em si
    //
    // Rede de seguranca, nao substituto do teste de banco. As consultas estao provadas contra Postgres
    // em EstoqueNaoPerdeBaixaIT -- mas os *IT sao EXCLUIDOS do `mvn test` (pom.xml:34) e do build da
    // imagem do deploy, porque precisam de um Postgres no ar.
    //
    // Medido em 20/10/2026: fiz o `definirEstoque` gravar tambem `codigoExterno = null`, e a suite de
    // 468 passou VERDE; so o IT acusou. Mesma coisa tirando a clausula `>= 0` do ajuste. Estes dois
    // testes leem a consulta no fonte e pegam exatamente essas mutacoes.

    @Test
    void aConsultaDoDEFINIRmexeEmUMAcolunaSO() throws java.io.IOException {
        String consulta = consultaDe("repository/ProdutoRepository.java", "int definirEstoque");

        assertTrue(consulta.contains("set p.estoque = :valor"), "o definir grava o estoque");
        assertEquals(1, consulta.split("set ", -1).length - 1, "um unico set");
        assertFalse(consulta.contains(","),
                "o definir NAO pode gravar mais nenhuma coluna: era assim que a tela de Estoque "
                        + "apagava o codigo do marketplace. Se precisar de outra coluna, faca outra "
                        + "consulta com outro nome.");
    }

    @Test
    void aConsultaDoAJUSTARrecusaFicarNegativa() throws java.io.IOException {
        String produto = consultaDe("repository/ProdutoRepository.java", "int somarAoEstoque");
        assertTrue(produto.contains("p.estoque + :delta >= 0"),
                "ajuste a mao nao pode deixar o saldo negativo; saldo negativo so vem de venda que "
                        + "nao podia ser recusada");

        String insumo = consultaDe("repository/InsumoRepository.java", "int somarAoEstoque");
        assertTrue(insumo.contains("i.estoque + :delta >= 0"), "mesma regra no insumo");
    }

    /** O texto da consulta que vem imediatamente antes da assinatura do metodo. */
    private String consultaDe(String caminho, String assinatura) throws java.io.IOException {
        String fonte = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/br/com/bora").resolve(caminho),
                java.nio.charset.StandardCharsets.UTF_8);
        int fim = fonte.indexOf(assinatura);
        assertTrue(fim > 0, "nao achei " + assinatura + " em " + caminho);
        int ini = fonte.lastIndexOf("@org.springframework.data.jpa.repository.Query", fim);
        assertTrue(ini > 0, "nao achei a @Query de " + assinatura);
        return fonte.substring(ini, fim);
    }

    // ======================================================================= insumo

    private Insumo insumoNoBanco() {
        Insumo i = new Insumo();
        i.id = 50L;
        i.lojaId = LOJA;
        i.nome = "Polpa de Acai";
        i.unidade = "kg";
        i.custo = new BigDecimal("15.00");
        i.estoque = new BigDecimal("3.500");   // depois do consumo da noite
        when(insumoRepo.findByIdAndLojaId(50L, LOJA)).thenReturn(Optional.of(i));
        return i;
    }

    @Test
    void corrigirOCUSTOdoInsumoNAOapagaOconsumoDaNoite() {
        insumoNoBanco();
        Insumo form = new Insumo();
        form.id = 50L;
        form.nome = "Polpa de Acai";
        form.unidade = "kg";
        form.custo = new BigDecimal("17.00");          // o que ele veio mudar
        form.estoque = new BigDecimal("10.000");       // numero velho da tela

        Insumo salvo = insumos.salvar(form);

        assertEquals(0, salvo.estoque.compareTo(new BigDecimal("3.500")),
                "o estoque tem que continuar o do banco");
        assertEquals(0, salvo.custo.compareTo(new BigDecimal("17.00")));
    }

    @Test
    void aCRIACAOdoInsumoAceitaAquantidadeInicial() {
        Insumo novo = new Insumo();
        novo.nome = "Granola";
        novo.estoque = new BigDecimal("8.000");

        Insumo salvo = insumos.salvar(novo);

        assertEquals(0, salvo.estoque.compareTo(new BigDecimal("8.000")));
    }

    @Test
    void oInsumoTambemTemOendpointDeEstoque() {
        insumoNoBanco();

        insumos.mexerNoEstoque(50L, new MexerNoEstoqueRequest(Tipo.DEFINIR, new BigDecimal("6.250")));

        verify(insumoRepo).definirEstoque(50L, LOJA, new BigDecimal("6.250"));
    }
}
