package br.com.bora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Receita e contagem de pedidos precisam usar a MESMA regra.
 *
 * <p>A receita já excluía o PIX que ninguém pagou; a contagem de pedidos válidos, não. Os dois números
 * saem no mesmo painel, e o ticket médio é um dividido pelo outro: com regras diferentes ele saía
 * menor que a realidade, e ninguém tinha como desconfiar olhando a tela.</p>
 *
 * <p>Este teste lê a consulta no código porque a regra está escrita nela. Sem banco de verdade na
 * bateria, é o que garante que as duas não voltem a divergir quando alguém mexer numa só.</p>
 */
class ContasQueFechamEntreSiTest {

    private String fonte() throws IOException {
        return Files.readString(Paths.get("src/main/java/br/com/bora/repository/PedidoRepository.java"));
    }

    private String consulta(String fonte, String metodo) {
        int fim = fonte.indexOf(metodo);
        assertTrue(fim > 0, "metodo " + metodo + " sumiu do repositorio");
        int ini = fonte.lastIndexOf("@Query", fim);
        assertTrue(ini > 0, "consulta de " + metodo + " nao encontrada");
        return fonte.substring(ini, fim);
    }

    @Test
    void receitaEContagemIgnoramPixNaoPago() throws IOException {
        String f = fonte();
        String regra = "aguardandoPagamento";

        assertTrue(consulta(f, "somaReceita").contains(regra),
                "receita nunca pode contar PIX que ninguem pagou");
        assertTrue(consulta(f, "contaPedidosValidos").contains(regra),
                "a contagem tem que usar a MESMA regra da receita, senao o ticket medio mente");
    }

    @Test
    void canceladosSeguemSendoContadosPeloStatus() throws IOException {
        assertTrue(consulta(fonte(), "contaPedidosCancelados").contains("StatusPedido.CANCELADO"));
    }
}
