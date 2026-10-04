package br.com.bora.controller;

import br.com.bora.repository.ComplementoGrupoRepository;
import br.com.bora.repository.ComplementoItemRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Mexer em complemento é mexer em preço.
 *
 * <p>Este endpoint não pedia papel nenhum, enquanto mudar o produto exige gerente. Um atendente
 * reescrevia o preço dos adicionais — e numa açaiteria o adicional é boa parte do valor da venda. É
 * daqui que sai também o "mínimo" do grupo, que decide se o cliente é obrigado a comprar um extra
 * para conseguir pedir (foi exatamente o que travou 12 produtos da Zirá).</p>
 */
class PapelParaMexerEmComplementoTest {

    private AuthContext ctx;
    private ComplementoController controller;

    @BeforeEach
    void montar() {
        ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(1L);
        controller = new ComplementoController(mock(ComplementoGrupoRepository.class),
                mock(ComplementoItemRepository.class), mock(ProdutoRepository.class), ctx);
    }

    @Test
    void semPapelSuficiente_naoSalva() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Ação não permitida para o seu perfil"))
                .when(ctx).requirePapel("ADMINISTRADOR_LOJA", "GERENTE");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.salvar(7L, List.of()));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
    }

    @Test
    void oPapelEhConferidoAntesDeQualquerEscrita() {
        ComplementoGrupoRepository grupos = mock(ComplementoGrupoRepository.class);
        ComplementoItemRepository itens = mock(ComplementoItemRepository.class);
        controller = new ComplementoController(grupos, itens, mock(ProdutoRepository.class), ctx);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "nao")).when(ctx)
                .requirePapel("ADMINISTRADOR_LOJA", "GERENTE");

        assertThrows(ResponseStatusException.class, () -> controller.salvar(7L, List.of()));

        // a tela substitui o conjunto inteiro: apagar primeiro e conferir depois destruiria o cardapio
        verify(grupos, never()).deleteByLojaIdAndProdutoId(anyLong(), anyLong());
        verify(itens, never()).deleteByLojaIdAndGrupoIdIn(anyLong(), anyList());
    }
}
