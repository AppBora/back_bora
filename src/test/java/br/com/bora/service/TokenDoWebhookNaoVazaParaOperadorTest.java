package br.com.bora.service;

import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Listar integrações é de gerente para cima, porque a resposta carrega o token do webhook.
 *
 * <p><b>O furo que isto fecha:</b> o {@code IntegracaoService.listar()} devolve, para cada canal, o
 * {@code webhookPath} — e dentro dele vai o token do webhook da loja, em claro. Esse metodo era o
 * unico do servico SEM {@code requirePapel}: salvar, desconectar e os demais ja exigiam. Um OPERADOR
 * abria {@code GET /api/integracoes}, copiava o token e injetava pedido falso de marketplace na
 * propria loja por {@code POST /webhooks/{canal}?loja=&token=}.</p>
 *
 * <p><b>O que este teste prova, e o que nao prova:</b> prova que a chamada de verificacao de papel
 * existe e com quais papeis — ou seja, pega quem apagar a linha. NAO prova que o
 * {@code AuthContext.requirePapel} de verdade barra, porque aqui ele e mock. Essa parte e do
 * AuthContext e vale para os 39 lugares que o chamam.</p>
 */
class TokenDoWebhookNaoVazaParaOperadorTest {

    private IntegracaoService servicoCom(AuthContext ctx) {
        return new IntegracaoService(mock(IntegracaoCanalRepository.class), ctx,
                mock(PixService.class), List.of());
    }

    @Test
    void operadorNaoListaIntegracoes() {
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(18L);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(ctx).requirePapel("ADMINISTRADOR_LOJA", "GERENTE");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> servicoCom(ctx).listar());
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
    }

    @Test
    void gerenteLista() {
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(18L);

        assertDoesNotThrow(() -> servicoCom(ctx).listar());
        verify(ctx).requirePapel("ADMINISTRADOR_LOJA", "GERENTE");
    }
}
