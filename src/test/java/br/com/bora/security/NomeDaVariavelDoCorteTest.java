package br.com.bora.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O nome da variavel que liga o corte por falta de pagamento tem que ser o nome que o Spring entende.
 *
 * <p>O codigo le a propriedade {@code bora.cobranca.corte-por-assinatura}. O Spring traduz variavel
 * de ambiente em propriedade trocando ponto e hifen por sublinhado, entao o nome correto e
 * {@code BORA_COBRANCA_CORTE_POR_ASSINATURA}.</p>
 *
 * <p>O comentario da classe dizia {@code BORA_CORTE_POR_ASSINATURA}, sem o "COBRANCA", e foi esse
 * nome que acabou no servidor. Resultado: o dono decidiu ligar o corte, a variavel estava "true" no
 * arquivo de ambiente, e o corte nunca funcionou — ninguem foi bloqueado por falta de pagamento, e
 * nada no log avisava. Variavel de ambiente com nome errado nao da erro: simplesmente nao existe.</p>
 *
 * <p>Este teste usa a propria classe do Spring que faz a traducao, para o nome documentado nao poder
 * divergir do nome que a aplicacao le.</p>
 */
class NomeDaVariavelDoCorteTest {

    private static final String PROPRIEDADE = "bora.cobranca.corte-por-assinatura";
    private static final String CERTO = "BORA_COBRANCA_CORTE_POR_ASSINATURA";
    private static final String ERRADO = "BORA_CORTE_POR_ASSINATURA";

    private static StandardEnvironment comVariavel(String nome, String valor) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("teste", Map.of(nome, (Object) valor)));
        return env;
    }

    @Test
    void oNomeDocumentadoLigaMesmoOCorte() {
        assertEquals("true", comVariavel(CERTO, "true").getProperty(PROPRIEDADE),
                "o nome documentado da variavel nao chega na propriedade que o codigo le");
    }

    @Test
    void oNomeSemCobrancaNaoLigaNada() {
        assertNull(comVariavel(ERRADO, "true").getProperty(PROPRIEDADE),
                "se este nome passar a funcionar, atualize o comentario da RegraDeAcesso");
    }

    @Test
    void oComentarioDaClasseEnsinaONomeCerto() throws java.io.IOException {
        String fonte = java.nio.file.Files.readString(
                java.nio.file.Paths.get("src/main/java/br/com/bora/security/RegraDeAcesso.java"));
        assertTrue(fonte.contains(CERTO),
                "a RegraDeAcesso tem que ensinar o nome que funciona: " + CERTO);
        // Citar o nome errado e bom: e o historico de por que o corte ficou desligado por dias.
        // O que nao pode e MANDAR usar — ou seja, o nome errado seguido de "=true".
        assertFalse(fonte.contains(ERRADO + "=true"),
                "a RegraDeAcesso ainda manda ligar com o nome que nao funciona: " + ERRADO);
    }
}
