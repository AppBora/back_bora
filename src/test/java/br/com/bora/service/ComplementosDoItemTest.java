package br.com.bora.service;

import br.com.bora.service.ComplementoService.Escolhido;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O registro dos complementos no item do pedido: o que entra, volta.
 *
 * <p>Escrever e ler moravam em arquivos diferentes — dois pontos montavam o texto e um terceiro o
 * desmontava. Uma mutacao que apagasse a gravacao passou pela suite inteira sem ninguem reclamar,
 * porque nenhum teste ligava as duas pontas. Estes testes ligam.</p>
 */
class ComplementosDoItemTest {

    private static final Escolhido GRANOLA = new Escolhido(12L, "Granola", new BigDecimal("2.00"));
    private static final Escolhido MORANGO = new Escolhido(15L, "Morango", new BigDecimal("4.00"));

    @Test
    void oQueEntraVolta() {
        List<Escolhido> voltou = ComplementosDoItem.ler(ComplementosDoItem.escrever(List.of(GRANOLA, MORANGO)));

        assertEquals(2, voltou.size());
        assertEquals(12L, voltou.get(0).id());
        assertEquals("Granola", voltou.get(0).nome());
        assertEquals(0, new BigDecimal("2.00").compareTo(voltou.get(0).preco()));
    }

    @Test
    void guardaONomeEOPreco_naoSoOId() {
        // Esta e a razao de existir do formato: o id nao sobrevive a uma regravacao do cardapio.
        String guardado = ComplementosDoItem.escrever(List.of(GRANOLA));

        assertTrue(guardado.contains("Granola"), guardado);
        assertTrue(guardado.contains("2.0"), guardado);
    }

    @Test
    void semComplemento_naoEAMesmaCoisaQueItemAntigo() {
        // "[]" diz "nao tinha adicional". Nulo diz "nao sei o que foi escolhido". Enquanto os dois
        // eram nulo, um pedido antigo com adicionais voltava como produto puro e sem aviso.
        assertEquals(List.of(), ComplementosDoItem.ler(ComplementosDoItem.escrever(List.of())));
        assertNull(ComplementosDoItem.ler(null));
        assertNull(ComplementosDoItem.ler(""));
        assertNull(ComplementosDoItem.ler("   "));
    }

    @Test
    void registroIlegivel_viraItemAntigoEmVezDeQuebrar() {
        // Dado de uma versao anterior, ou corrompido: melhor pedir para escolher de novo do que
        // derrubar o link na cara do cliente.
        assertNull(ComplementosDoItem.ler("12,15"));
        assertNull(ComplementosDoItem.ler("{nao e json"));
    }

    @Test
    void nomeComAspasEAcento_naoQuebraORegistro() {
        var esquisito = new Escolhido(9L, "Creme \"especial\" de avelã", new BigDecimal("3.50"));

        assertEquals("Creme \"especial\" de avelã",
                ComplementosDoItem.ler(ComplementosDoItem.escrever(List.of(esquisito))).get(0).nome());
    }

    @Test
    void listaNula_gravaVazio() {
        assertEquals(List.of(), ComplementosDoItem.ler(ComplementosDoItem.escrever(null)));
    }

    // ---------------------------------------------------------------- o insumo do adicional (V55)

    /**
     * Registro gravado ANTES da V55 continua legivel, com o insumo nulo.
     *
     * <p>Sao os pedidos que ja estao no banco. Se a leitura estourasse, o "repetir pedido" pararia de
     * funcionar em todo pedido antigo -- e o cancelamento deixaria de devolver estoque, porque e a
     * mesma leitura.</p>
     */
    @Test
    void registroANTESdaV55continuaLEGIVEL_comInsumoNULO() {
        String antigo = "[{\"id\":12,\"nome\":\"Granola\",\"preco\":2.00}]";

        var lido = ComplementosDoItem.ler(antigo);

        assertNotNull(lido, "registro antigo nao pode virar \"nao sei o que foi escolhido\"");
        assertEquals("Granola", lido.get(0).nome());
        assertNull(lido.get(0).insumoId());
        assertNull(lido.get(0).quantidade());
        assertFalse(lido.get(0).consomeInsumo(), "sem insumo gravado, nao ha o que devolver");
    }

    /** O insumo e a quantidade sobrevivem a ida e volta — e o que o cancelamento le. */
    @Test
    void oINSUMOeaQUANTIDADEsobrevivemAidaEvolta() {
        var comInsumo = new Escolhido(12L, "Granola", new BigDecimal("3.00"),
                50L, new BigDecimal("0.030"));

        var lido = ComplementosDoItem.ler(ComplementosDoItem.escrever(java.util.List.of(comInsumo)));

        assertEquals(50L, lido.get(0).insumoId());
        assertEquals(0, lido.get(0).quantidade().compareTo(new BigDecimal("0.030")));
        assertTrue(lido.get(0).consomeInsumo());
    }

    /** Adicional sem insumo, gravado AGORA, tambem volta sem insumo — e nao estoura. */
    @Test
    void adicionalSEMinsumoVoltaSEMinsumo() {
        var lido = ComplementosDoItem.ler(ComplementosDoItem.escrever(
                java.util.List.of(new Escolhido(12L, "Borda recheada", new BigDecimal("8.00")))));

        assertNull(lido.get(0).insumoId());
        assertFalse(lido.get(0).consomeInsumo());
    }
}
