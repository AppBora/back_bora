package br.com.bora.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ficha sem custo cadastrado nao faz o item custar zero.
 *
 * <h2>O defeito (achado pelo QA em 26/10/2026)</h2>
 * <p>Os tres canais decidiam o custo do item com {@code custoFicha != null ? custoFicha :
 * prod.custo}. O {@code consumirFicha} comeca a soma em {@code ZERO} e trata insumo sem custo como
 * zero, entao uma ficha cujos insumos nao tem custo devolve <b>{@code 0,00}, nao nulo</b> — e
 * {@code 0,00} passa o teste do {@code != null}. Resultado: o custo do produto era jogado fora e o
 * item ficava gravado custando nada. No relatorio o produto aparece com <b>margem de 100%</b>, e o
 * CMV do mes sai menor do que e.</p>
 *
 * <p>Zero e nulo dizem coisas diferentes neste sistema, e o proprio
 * {@code somarCustoDoAdicional} ja respeitava isso: nulo e "custo nao cadastrado", nao "de graca".</p>
 *
 * <p><b>Nao esta acontecendo hoje:</b> medido na producao em 26/10/2026, nenhuma linha de ficha
 * aponta para insumo sem custo — 42 dos 47 insumos nao tem custo cadastrado, mas nenhum deles esta
 * em ficha. E a armadilha, nao o estrago. Com 42 insumos sem custo, basta o lojista ligar um deles a
 * uma ficha.</p>
 */
class OcustoDOitemNaoVIRAzeroTest {

    private static BigDecimal custo(String ficha, String produto) {
        return PedidoService.custoDaFichaOuDoProduto(
                ficha == null ? null : new BigDecimal(ficha),
                produto == null ? null : new BigDecimal(produto));
    }

    // ---------------------------------------------------------------- o defeito
    @Test
    void fichaCOMcustoZEROcaiNOcustoDOproduto() {
        assertEquals(0, new BigDecimal("12.50").compareTo(custo("0.00", "12.50")),
                "o custo do produto era jogado fora e o item ficava custando nada");
    }

    @Test
    void semCUSTOemLUGARnenhumFICAnulo_eNAOzero() {
        // Nulo e "nao cadastrado", e o relatorio ja sabe tratar isso. Zero parece medido e nao e.
        assertNull(custo("0.00", null));
    }

    // ---------------------------------------------------------------- o que tem de continuar valendo
    @Test
    void fichaCOMcustoVALEsobreOproduto() {
        // A ficha e mais precisa: ela soma o que cada insumo realmente custa naquela receita.
        assertEquals(0, new BigDecimal("4.00").compareTo(custo("4.00", "99.00")));
    }

    @Test
    void produtoSEMfichaUSAoCUSTOdeleMESMO() {
        assertEquals(0, new BigDecimal("7.00").compareTo(custo(null, "7.00")));
    }

    @Test
    void produtoSEMfichaESEMcustoFICAnulo() {
        assertNull(custo(null, null));
    }

    @Test
    void custoDEcentavosNAOehCONFUNDIDOcomZERO() {
        // signum() > 0, nao compareTo com um piso: um pao de R$ 0,01 tem custo cadastrado.
        assertEquals(0, new BigDecimal("0.01").compareTo(custo("0.01", "50.00")));
    }

    @Test
    void oZEROdaFICHAnaoVIRAzeroQUANDOoPRODUTOtemCUSTO() {
        // A mesma coisa dita pelo outro lado, porque e o caso que o relatorio ve: nunca sai 0,00 com
        // um custo de produto disponivel.
        for (String z : new String[] { "0", "0.00", "0.000" }) {
            assertEquals(0, new BigDecimal("30.00").compareTo(custo(z, "30.00")), "falhou com " + z);
        }
    }

    // ---------------------------------------------------------------- o ajudante do adicional
    @Test
    void oADICIONALcontinuaSOMANDOsobreOcustoESCOLHIDO() {
        // Os dois ajudantes se compoem, e e assim que os tres canais chamam.
        BigDecimal base = PedidoService.custoDaFichaOuDoProduto(BigDecimal.ZERO, new BigDecimal("10.00"));
        assertEquals(0, new BigDecimal("10.60").compareTo(
                PedidoService.somarCustoDoAdicional(base, new BigDecimal("0.60"))));
    }

    @Test
    void semADICIONALoCUSTOnaoMUDA() {
        BigDecimal base = PedidoService.custoDaFichaOuDoProduto(null, null);
        assertNull(PedidoService.somarCustoDoAdicional(base, null),
                "nulo tem que atravessar: transformar em zero mentiria que o custo e conhecido");
    }

    // ---------------------------------------------------------------- os tres canais usam o mesmo
    @Test
    void osTRESCANAISchamamOmesmoAJUDANTE() {
        // A regra do custo tem de existir num lugar so, igual a do adicional -- foi a divergencia
        // entre canais que criou metade dos defeitos de estoque deste mes.
        String[][] onde = {
            { "src/main/java/br/com/bora/service/PedidoService.java", "balcao e marketplace" },
            { "src/main/java/br/com/bora/controller/PublicController.java", "cardapio publico" },
        };
        for (String[] alvo : onde) {
            String fonte = semComentarios(assertDoesNotThrow(() -> java.nio.file.Files.readString(
                    java.nio.file.Path.of(alvo[0]), java.nio.charset.StandardCharsets.UTF_8)));
            assertTrue(fonte.contains("custoDaFichaOuDoProduto("),
                    alvo[1] + " nao usa o ajudante: a regra do custo voltou a ter duas versoes");
            assertFalse(fonte.contains("custoFicha != null ? custoFicha : prod.custo"),
                    alvo[1] + " voltou a decidir o custo na mao, e o zero da ficha joga o custo do "
                            + "produto fora");
        }
    }

    /**
     * O codigo sem os comentarios.
     *
     * <p>Primeira versao deste teste reprovou o conserto CERTO: o javadoc do ajudante novo cita o
     * trecho antigo para explicar o defeito, e o {@code contains} nao distingue codigo de texto. Ja
     * aconteceu tres vezes neste projeto; o lado do frontend tem o mesmo ajudante
     * ({@code semComentarios} no harness).</p>
     */
    private static String semComentarios(String fonte) {
        StringBuilder out = new StringBuilder();
        boolean emBloco = false;
        for (String linha : fonte.split("\n", -1)) {
            String t = linha.trim();
            if (emBloco) {
                if (t.contains("*/")) emBloco = false;
                continue;
            }
            if (t.startsWith("/*")) {
                if (!t.contains("*/")) emBloco = true;
                continue;
            }
            if (t.startsWith("//") || t.startsWith("*")) continue;
            out.append(linha).append('\n');
        }
        return out.toString();
    }
}
