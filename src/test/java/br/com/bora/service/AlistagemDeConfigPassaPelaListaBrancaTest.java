package br.com.bora.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A regra de o que pode sair tem teste próprio ({@code ConfigDaPlataformaNaoVazaValorTest}). Este
 * aqui olha a FIAÇÃO, que é onde o defeito costuma morar de verdade.
 *
 * <p>A varredura de 23/10/2026 achou sete endpoints prontos que nenhuma tela chamava: a regra
 * existia, o teste existia, e ninguém ligou os dois. Uma lista branca que o controller não usa é
 * exatamente a mesma armadilha — e passaria num teste de unidade da regra, verdinho.</p>
 *
 * <p>Por isso este teste lê o código do {@code PlataformaController} e exige que a listagem de
 * configurações mande cada valor por {@link ChavesDaPlataforma}, em vez de escrever
 * {@code c.getValor()} direto na resposta.</p>
 */
class AlistagemDeConfigPassaPelaListaBrancaTest {

    private static final Path CONTROLLER =
            Path.of("src/main/java/br/com/bora/controller/PlataformaController.java");

    /** O corpo do método que responde GET /admin-bora/config, por contagem de chaves. */
    private String corpoDaListagem() throws IOException {
        String fonte = Files.readString(CONTROLLER);
        int i = fonte.indexOf("public Map<String, String> configuracoes()");
        assertTrue(i > 0, "nao achei o metodo configuracoes() -- este teste precisa ser reapontado");
        int abre = fonte.indexOf('{', i);
        int nivel = 0;
        for (int j = abre; j < fonte.length(); j++) {
            char c = fonte.charAt(j);
            if (c == '{') nivel++;
            else if (c == '}' && --nivel == 0) return fonte.substring(abre, j + 1);
        }
        return fail("metodo configuracoes() sem fechamento");
    }

    @Test
    void aListagemMandaOvalorPelaListaBranca() throws IOException {
        assertTrue(corpoDaListagem().contains("ChavesDaPlataforma.paraMostrar"),
                "o GET /admin-bora/config nao passa pela lista branca: regra certa, fiacao solta");
    }

    @Test
    void aListagemNAOescreveOvalorCruNaResposta() throws IOException {
        String corpo = corpoDaListagem();
        // "out.put(c.getChave(), c.getValor())" e exatamente a linha antiga que vazava.
        assertFalse(corpo.replaceAll("\\s+", "").contains("out.put(c.getChave(),c.getValor())"),
                "o valor cru voltou para a resposta da listagem");
    }

    @Test
    void credencialDeMarketplaceContinuaFiltradaAntesDeTudo() throws IOException {
        assertTrue(corpoDaListagem().contains("ehChaveDeCredencial"),
                "o filtro que tira credencial de marketplace da listagem sumiu");
    }
}
