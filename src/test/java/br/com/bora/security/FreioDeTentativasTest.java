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

    /**
     * Como a chamada chega HOJE: o Caddy descarta o cabecalho do cliente e escreve so o IP que ele
     * viu. Medido em 19/10/2026 com o caddy:2.11.4, a mesma versao da producao, sem trusted_proxies.
     *
     * <p>A versao anterior deste teste montava {@code "189.0.0.7, 172.18.0.2"} -- cliente seguido do
     * IP do proprio Caddy -- e afirmava que o primeiro item era o cliente. Esse cenario nao acontece:
     * o Caddy nao se acrescenta na lista, e nao repassa o que o cliente mandou. Era teste verde sobre
     * uma premissa que nao existe.</p>
     */
    @Test
    void comUMitemSO_aOrigemEhEsseItem() {
        jakarta.servlet.http.HttpServletRequest http =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(http.getHeader("X-Forwarded-For")).thenReturn("189.0.0.7");
        org.mockito.Mockito.when(http.getRemoteAddr()).thenReturn("172.18.0.2"); // o container do Caddy

        assertEquals("189.0.0.7", FreioDeTentativas.origem(http),
                "sem olhar o cabecalho, o contador vira um balde so e um atacante tranca o lojista");
    }

    /**
     * E se a topologia mudar (trusted_proxies, CDN na frente, outro proxy): o valor que o CLIENTE
     * escolheu nao pode virar a chave, senao trocar o cabecalho a cada chamada da chave nova toda vez
     * e o freio deixa de frear. O ultimo item e sempre o que o proxy mais proximo escreveu.
     */
    @Test
    void seAlistaTIVERmaisDEum_valeOqueOproxyEscreveu() {
        jakarta.servlet.http.HttpServletRequest http =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(http.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 189.0.0.7");
        org.mockito.Mockito.when(http.getRemoteAddr()).thenReturn("172.18.0.2");

        assertEquals("189.0.0.7", FreioDeTentativas.origem(http));
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
