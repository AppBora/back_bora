package br.com.bora.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * O cardapio publico tem teto de chamadas por origem.
 *
 * <p><b>O que isto fecha (09/10/2026):</b> {@code POST /public/loja/{id}/pedido} e sem autenticacao, os
 * ids de loja sao sequenciais, e cada chamada criava pedido, baixava estoque, consumia cashback e abria
 * cobranca no Asaas DA LOJA. Nada limitava: um laco de curl inundava a cozinha de um concorrente.</p>
 */
class FreioDoCardapioTest {

    private HttpServletRequest de(String ip) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getHeader("X-Forwarded-For")).thenReturn(ip);
        when(r.getRemoteAddr()).thenReturn("172.18.0.2"); // o Caddy, igual para todos
        return r;
    }

    @Test
    void dezPedidosPassamEODecimoPrimeiroNao() {
        FreioDoCardapio freio = new FreioDoCardapio(10, 30);
        HttpServletRequest req = de("177.45.197.230");

        for (int i = 1; i <= 10; i++) {
            int n = i;
            assertDoesNotThrow(() -> freio.pedido(req), "o pedido " + n + " e de cliente de verdade");
        }

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> freio.pedido(req));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
    }

    @Test
    void oTetoEhPorOrigemENaoUmBaldeSo() {
        FreioDoCardapio freio = new FreioDoCardapio(2, 30);
        HttpServletRequest atacante = de("1.2.3.4");
        HttpServletRequest cliente = de("177.45.197.230");

        freio.pedido(atacante);
        freio.pedido(atacante);
        assertThrows(ResponseStatusException.class, () -> freio.pedido(atacante));

        // O cliente de verdade da loja nao pode pagar pelo atacante.
        assertDoesNotThrow(() -> freio.pedido(cliente));
    }

    @Test
    void oTetoPorHoraTambemVale() {
        FreioDoCardapio freio = new FreioDoCardapio(100, 3); // minuto folgado, hora apertada
        HttpServletRequest req = de("1.2.3.4");

        freio.pedido(req);
        freio.pedido(req);
        freio.pedido(req);

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> freio.pedido(req));
        assertTrue(e.getReason().contains("hora"), "a mensagem tem que dizer qual teto estourou: " + e.getReason());
    }

    @Test
    void limiteZeroDesligaOFreio() {
        FreioDoCardapio freio = new FreioDoCardapio(0, 0);
        HttpServletRequest req = de("1.2.3.4");

        for (int i = 0; i < 500; i++) assertDoesNotThrow(() -> freio.pedido(req));
    }

    @Test
    void oSaldoDeCashbackEOCupomTemTetoProprio() {
        FreioDoCardapio freio = new FreioDoCardapio(10, 30);
        HttpServletRequest req = de("1.2.3.4");

        // Tetos independentes: estourar a consulta de saldo nao pode barrar o pedido.
        for (int i = 0; i < 20; i++) freio.consultaDeSaldo(req);
        assertThrows(ResponseStatusException.class, () -> freio.consultaDeSaldo(req));

        assertDoesNotThrow(() -> freio.pedido(req));
        for (int i = 0; i < 30; i++) freio.codigoDeDesconto(req);
        assertThrows(ResponseStatusException.class, () -> freio.codigoDeDesconto(req));
    }

    @Test
    void semCabecalhoDeOrigemCaiNoEnderecoDaConexao() {
        FreioDoCardapio freio = new FreioDoCardapio(1, 30);
        HttpServletRequest semHeader = mock(HttpServletRequest.class);
        when(semHeader.getHeader("X-Forwarded-For")).thenReturn(null);
        when(semHeader.getRemoteAddr()).thenReturn("10.0.0.9");

        freio.pedido(semHeader);
        assertThrows(ResponseStatusException.class, () -> freio.pedido(semHeader));
    }

    @Test
    void variosClientesAtrasDoMesmoEnderecoCompartilhamOTeto() {
        // Isto e consequencia conhecida do desenho, nao defeito: wi-fi de predio e operadora de celular
        // saem pelo mesmo endereco, entao clientes diferentes dividem o teto. E a razao de o limite ser
        // 10 por minuto e nao 1 — barrar venda de verdade e pior que o abuso que isto evita.
        FreioDoCardapio freio = new FreioDoCardapio(10, 30);
        HttpServletRequest predio = de("200.200.200.200");

        for (int i = 0; i < 10; i++) freio.pedido(predio);

        assertThrows(ResponseStatusException.class, () -> freio.pedido(predio));
    }
}
