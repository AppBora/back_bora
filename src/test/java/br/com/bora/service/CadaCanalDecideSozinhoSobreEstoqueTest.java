package br.com.bora.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * As três respostas para "faltou estoque" não podem ser unificadas.
 *
 * <h2>Por que isto é teste</h2>
 * <p>Ter dois métodos quase iguais ({@code baixarEstoque} e {@code baixarEstoqueSeTiver}) parece
 * duplicação, e um dia alguém vai querer "limpar" isso deixando só o que recusa. Se fizer, o pedido
 * do iFood passa a ser recusado quando o estoque está errado — e o pedido já foi aceito do outro
 * lado, com o entregador a caminho. E a venda de balcão trava com o cliente na frente.</p>
 *
 * <p>A diferença é <b>quem está com o produto na mão</b>, não um detalhe de implementação:</p>
 * <ul>
 *   <li>cardápio público → o cliente não tem nada na mão → <b>recusa</b></li>
 *   <li>balcão → o operador está com o produto → <b>vende e avisa</b></li>
 *   <li>marketplace → já aceito no iFood → <b>vende e avisa</b></li>
 * </ul>
 *
 * <p>Isto lê o código-fonte de propósito. Não existe como provar "este método NÃO é chamado em lugar
 * nenhum além deste" com mock — e é exatamente essa a regra que precisa ficar travada.</p>
 */
class CadaCanalDecideSozinhoSobreEstoqueTest {

    private static final Path FONTE = Path.of("src/main/java/br/com/bora");

    private String ler(String caminho) throws IOException {
        Path p = FONTE.resolve(caminho);
        assertTrue(Files.exists(p), "nao achei " + p.toAbsolutePath());
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void aBaixaQueRECUSAeUsadaSOpeloCardapioPublico() throws IOException {
        int noCardapio = contar(ler("controller/PublicController.java"), "baixarEstoqueSeTiver");
        int noPedidoService = contar(ler("service/PedidoService.java"), "baixarEstoqueSeTiver");

        assertEquals(1, noCardapio, "o cardapio publico tem que usar a baixa que recusa");
        assertEquals(0, noPedidoService,
                "balcao e marketplace NAO podem recusar venda por falta de estoque: no balcao o "
                        + "operador esta com o produto na mao, e no marketplace o pedido ja foi "
                        + "aceito no iFood. Ver o javadoc desta classe antes de mudar.");
    }

    @Test
    void oConsumoDeFichaQueRECUSAeUsadoSOpeloCardapioPublico() throws IOException {
        assertEquals(1, contar(ler("controller/PublicController.java"), "consumirFicha(lojaId, prod.id, qtd, true)"),
                "o cardapio publico tem que consumir a ficha no modo que recusa");
        // Especifico de proposito: um ", true)" solto tambem casaria com
        // devolucao.devolver(salvo, anterior, true), que e outra coisa.
        String pedidoService = ler("service/PedidoService.java");
        assertEquals(2, contar(pedidoService, "insumos.consumirFicha(lojaId, prod.id, qtd)"),
                "balcao e marketplace tem que usar a sobrecarga de 3 argumentos, a que deixa passar");
        assertEquals(0, contar(pedidoService, "consumirFicha(lojaId, prod.id, qtd, true)"),
                "PedidoService nao pode exigir estoque: no balcao o operador esta com o produto na "
                        + "mao, e no marketplace o pedido ja foi aceito no iFood");
    }

    @Test
    void oBalcaoEoMarketplaceAVISAMquandoOsaldoVaiFicarNegativo() throws IOException {
        String s = ler("service/PedidoService.java");

        assertTrue(s.contains("private void avisarSeVaiFicarNegativo"),
                "o aviso tem que existir: ir a negativo esta certo, ir CALADO nao");
        assertEquals(2, contar(s, "avisarSeVaiFicarNegativo(lojaId, prod, qtd)"),
                "os dois canais que deixam passar (balcao e marketplace) tem que avisar");
    }

    @Test
    void oEndpointDoPedidoDoCardapioEhTRANSACIONAL() throws IOException {
        String s = ler("controller/PublicController.java");
        int i = s.indexOf("@PostMapping(\"/loja/{lojaId}/pedido\")");
        assertTrue(i > 0, "nao achei o endpoint do pedido do cardapio");

        // A transacao e PRE-REQUISITO da recusa, nao enfeite: o metodo grava o pedido, depois os itens
        // num laco, e agora o laco pode recusar no meio. Sem transacao, recusar o terceiro item
        // deixaria o pedido gravado, dois itens gravados e o estoque dos dois ja baixado -- lixo no
        // painel e estoque errado por um pedido que o cliente nunca recebeu.
        String depois = s.substring(i, Math.min(s.length(), i + 900));
        assertTrue(depois.contains("@Transactional"),
                "o endpoint do pedido do cardapio PRECISA ser @Transactional; sem isso, recusar no "
                        + "meio do pedido deixa pedido orfao e estoque baixado");
    }

    @Test
    void aDevolucaoDeEstoqueNUNCApassaPelaBaixaQueRecusa() throws IOException {
        // Cancelar um pedido tem que devolver estoque mesmo com o saldo em zero ou negativo: foi a
        // venda que nao aconteceu. Se a devolucao usasse um metodo com trava de saldo, o cancelamento
        // falharia calado justamente nas lojas em que o estoque esta errado.
        String s = ler("service/EstornoDaVendaService.java");

        assertEquals(0, contar(s, "SeTiver"),
                "o estorno nao pode usar metodo com trava de saldo");
        assertTrue(s.contains("devolverEstoque"), "o estorno continua devolvendo pelo banco");
    }

    // =============================================================================================
    // Rede de seguranca para a CLAUSULA, nao substituto do teste de banco.
    //
    // A trava de verdade e a clausula SQL, e ela esta provada contra Postgres em
    // EstoqueNaoPerdeBaixaIT. O problema: os *IT sao EXCLUIDOS do `mvn test` (pom.xml, linha 34) e nao
    // rodam no build da imagem do deploy, porque precisam de um Postgres no ar.
    //
    // Medido em 20/10/2026: apaguei a clausula das duas consultas e a suite de 451 testes passou
    // VERDE. So o IT acusou. Ou seja, a trava mais importante deste conserto nao tinha nenhuma
    // protecao no caminho que o deploy percorre.
    //
    // Estes dois testes leem a consulta no fonte. E pouco -- nao provam comportamento nenhum -- mas
    // pegam exatamente a mutacao que passou: alguem "simplificar" a clausula de volta.
    // =============================================================================================

    @Test
    void aClausulaQueRECUSAcontinuaNaConsultaDoProduto() throws IOException {
        String s = ler("repository/ProdutoRepository.java");
        int i = s.indexOf("int baixarEstoqueSeTiver");
        assertTrue(i > 0, "nao achei o baixarEstoqueSeTiver");

        String consulta = s.substring(Math.max(0, i - 400), i);
        assertTrue(consulta.contains("p.estoque >= :qtd"),
                "sem esta comparacao a baixa volta a deixar o estoque ir a negativo sem limite");
        assertTrue(consulta.contains("p.estoque is null or"),
                "sem o 'is null or', produto que nao controla estoque passa a ser tratado como "
                        + "esgotado e o cardapio PARA DE VENDER -- hoje nenhum produto da producao "
                        + "controla estoque");
    }

    @Test
    void aClausulaQueRECUSAcontinuaNaConsultaDoInsumo() throws IOException {
        String s = ler("repository/InsumoRepository.java");
        int i = s.indexOf("int consumirSeTiver");
        assertTrue(i > 0, "nao achei o consumirSeTiver");

        String consulta = s.substring(Math.max(0, i - 400), i);
        assertTrue(consulta.contains("i.estoque >= :qtd"), "a trava do insumo saiu da consulta");
        assertTrue(consulta.contains("i.estoque is null or"),
                "insumo sem controle de estoque nao pode bloquear venda");
    }

    private static int contar(String texto, String agulha) {
        int n = 0, i = 0;
        while ((i = texto.indexOf(agulha, i)) >= 0) { n++; i += agulha.length(); }
        return n;
    }
}
