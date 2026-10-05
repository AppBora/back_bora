package br.com.bora.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A versão gravada no aceite tem que apontar para o texto que está no ar.
 *
 * <p>O sistema guarda, por loja, a data da versão dos Termos que o lojista aceitou — é a prova de que
 * ele concordou com <i>aquele</i> texto. Durante um bom tempo a constante dizia "2026-10-02" enquanto
 * a página publicada dizia "28 de agosto": o comprovante apontava para um texto que ninguém sabia
 * qual era. Num documento que fala de pagamento, suspensão e reembolso, isso é o que mais importa.</p>
 *
 * <p>Mudou o texto dos Termos? Mude a data da página E a constante, juntas. Este teste existe para
 * ninguém esquecer a metade.</p>
 */
class VersaoDosTermosTest {

    private static final String[] MESES = {"janeiro", "fevereiro", "março", "abril", "maio", "junho",
            "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"};

    @Test
    void aConstanteBateComADataPublicadaNosTermos() throws IOException {
        Path termos = Paths.get("..", "bora-landing", "termos.html");
        assumeTrue(Files.exists(termos), "termos.html nao esta neste checkout");

        String constante = Files.readString(
                Paths.get("src/main/java/br/com/bora/service/TermosService.java"));
        Matcher mc = Pattern.compile("VERSAO_VIGENTE = \"(\\d{4})-(\\d{2})-(\\d{2})\"").matcher(constante);
        assertTrue(mc.find(), "constante VERSAO_VIGENTE sumiu do TermosService");

        Matcher mp = Pattern.compile("Última atualização:</b> (\\d{1,2}) de (\\p{L}+) de (\\d{4})")
                .matcher(Files.readString(termos));
        assertTrue(mp.find(), "a pagina de Termos perdeu a linha de Última atualização");

        int mes = java.util.Arrays.asList(MESES).indexOf(mp.group(2).toLowerCase()) + 1;
        String naPagina = String.format("%s-%02d-%02d", mp.group(3), mes, Integer.parseInt(mp.group(1)));
        String noCodigo = mc.group(1) + "-" + mc.group(2) + "-" + mc.group(3);

        assertEquals(naPagina, noCodigo,
                "mudou o texto dos Termos e esqueceu metade: a prova de aceite fica apontando para "
                        + "um texto que nao existe mais");
    }
}
