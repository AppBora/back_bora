package br.com.bora.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/** O freio existe para que ninguém varra senhas de um lojista no /auth/login nem no cadastro público. */
class FreioDeTentativasTest {

    @Test
    void aSextaTentativaErradaEhBarrada() {
        FreioDeTentativas freio = new FreioDeTentativas();
        String chave = FreioDeTentativas.chave("dono@loja.local", "203.0.113.9");

        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> freio.conferir(chave), "as 5 primeiras ainda passam");
            freio.errou(chave);
        }

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> freio.conferir(chave));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
        assertTrue(e.getReason() != null && e.getReason().contains("minuto"), e.getReason());
    }

    @Test
    void quemEntraZeraOPlacar() {
        FreioDeTentativas freio = new FreioDeTentativas();
        String chave = FreioDeTentativas.chave("dono@loja.local", "203.0.113.9");
        for (int i = 0; i < 5; i++) freio.errou(chave);

        freio.acertou(chave);

        assertDoesNotThrow(() -> freio.conferir(chave), "depois de entrar, a conta volta ao normal");
    }

    @Test
    void oPlacarEhPorPessoaEPorOrigem() {
        FreioDeTentativas freio = new FreioDeTentativas();
        String golpista = FreioDeTentativas.chave("dono@loja.local", "203.0.113.9");
        String donoDeVerdade = FreioDeTentativas.chave("dono@loja.local", "189.0.0.7");
        for (int i = 0; i < 6; i++) freio.errou(golpista);

        assertThrows(ResponseStatusException.class, () -> freio.conferir(golpista));
        assertDoesNotThrow(() -> freio.conferir(donoDeVerdade),
                "o dono, de outro lugar, nao pode ficar trancado pelo ataque de terceiro");
    }

    @Test
    void emailComEspacoOuMaiuscula_contaComoAMesmaPessoa() {
        assertEquals(FreioDeTentativas.chave("Dono@Loja.local", "1.2.3.4"),
                FreioDeTentativas.chave("  dono@loja.local  ", "1.2.3.4"));
    }

    @Test
    void aOrigemVemDoClienteOriginal_naoDoProxy() {
        jakarta.servlet.http.HttpServletRequest http =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(http.getHeader("X-Forwarded-For")).thenReturn("189.0.0.7, 172.18.0.2");
        org.mockito.Mockito.when(http.getRemoteAddr()).thenReturn("172.18.0.2"); // o Caddy

        assertEquals("189.0.0.7", FreioDeTentativas.origem(http),
                "sem isto o contador vira um balde so e um atacante tranca a conta do lojista");
    }

    @Test
    void semCabecalhoDeProxy_usaOEnderecoDaConexao() {
        jakarta.servlet.http.HttpServletRequest http =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(http.getHeader("X-Forwarded-For")).thenReturn(null);
        org.mockito.Mockito.when(http.getRemoteAddr()).thenReturn("203.0.113.9");

        assertEquals("203.0.113.9", FreioDeTentativas.origem(http));
    }
}
