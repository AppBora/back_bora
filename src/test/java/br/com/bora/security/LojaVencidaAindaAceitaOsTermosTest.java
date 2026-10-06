package br.com.bora.security;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Loja com o acesso vencido ainda consegue aceitar os Termos e pagar.
 *
 * <p>Com o corte por falta de pagamento ligado, tudo em {@code /api/**} responde 402 para loja
 * vencida, menos os caminhos desta lista. Os Termos tinham ficado de fora, e isso virava um no: a
 * loja tomava 402 ao consultar o aceite, a faixa do painel nunca aparecia, e ela ficava impedida de
 * aceitar justamente o documento que rege a suspensao que a bloqueou.</p>
 *
 * <p>A correcao foi uma linha, e uma linha some facil. Este teste existe para ela nao sumir.</p>
 */
class LojaVencidaAindaAceitaOsTermosTest {

    private List<String> caminhosLivres() throws Exception {
        Field f = BloqueioPorAssinaturaFilter.class.getDeclaredField("CAMINHOS_LIVRES");
        f.setAccessible(true);
        return Arrays.asList((String[]) f.get(null));
    }

    private boolean liberado(String caminho) throws Exception {
        return caminhosLivres().stream().anyMatch(caminho::startsWith);
    }

    @Test
    void consultarEAceitarOsTermosPassa() throws Exception {
        assertTrue(liberado("/api/termos"), "consultar o aceite nao pode dar 402 em loja vencida");
        assertTrue(liberado("/api/termos/aceitar"), "aceitar nao pode dar 402 em loja vencida");
    }

    @Test
    void oCaminhoDePagarContinuaAberto() throws Exception {
        assertTrue(liberado("/api/assinatura"), "sem isto a loja vencida nao consegue voltar a pagar");
        assertTrue(liberado("/api/plano"));
        assertTrue(liberado("/api/configuracao"), "a marca da loja carrega na tela de bloqueio");
    }

    @Test
    void oRestoDoPainelContinuaBloqueado() throws Exception {
        assertFalse(liberado("/api/pedidos"), "loja vencida nao opera");
        assertFalse(liberado("/api/produtos"));
        assertFalse(liberado("/api/relatorios"));
    }
}
