package br.com.bora.security;

import br.com.bora.entity.Loja;
import br.com.bora.repository.LojaRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Loja com o prazo vencido não pode operar — mas precisa conseguir pagar.
 *
 * <p>Bloquear o login inteiro deixaria o lojista num beco: a tela de assinatura fica atrás do login.
 * Por isso ele entra e recebe 402 em tudo, menos no caminho que o faz voltar a pagar.</p>
 */
class BloqueioPorAssinaturaFilterTest {

    private final LojaRepository lojas = mock(LojaRepository.class);

    @AfterEach
    void limpar() {
        SecurityContextHolder.clearContext();
    }

    private void logar(Long lojaId, String papel) {
        var p = new BoraPrincipal(7L, lojaId, papel, "dono@loja.local");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, null, List.of()));
    }

    private void comLoja(OffsetDateTime acessoAte) {
        Loja l = new Loja();
        l.id = 18L;
        l.acessoAte = acessoAte;
        when(lojas.findById(18L)).thenReturn(Optional.of(l));
    }

    /** @return o status devolvido, ou 0 quando a chamada passou adiante. */
    private int passar(RegraDeAcesso regra, String caminho) throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(caminho);
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(res.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        FilterChain chain = mock(FilterChain.class);

        new BloqueioPorAssinaturaFilter(lojas, regra).doFilter(req, res, chain);

        var status = org.mockito.ArgumentCaptor.forClass(Integer.class);
        try {
            verify(res).setStatus(status.capture());
            return status.getValue();
        } catch (AssertionError naoBloqueou) {
            verify(chain).doFilter(req, res);
            return 0;
        }
    }

    @Test
    void prazoVencido_tranca_oResto_doPainel() throws Exception {
        logar(18L, "ADMINISTRADOR_LOJA");
        comLoja(OffsetDateTime.now().minusDays(1));

        assertEquals(402, passar(new RegraDeAcesso(true), "/api/pedidos/board"));
    }

    @Test
    void prazoVencido_deixa_passar_o_caminho_de_pagar() throws Exception {
        logar(18L, "ADMINISTRADOR_LOJA");
        comLoja(OffsetDateTime.now().minusDays(1));
        RegraDeAcesso ligado = new RegraDeAcesso(true);

        assertEquals(0, passar(ligado, "/api/assinatura"), "sem isto o lojista nao teria como pagar");
        assertEquals(0, passar(ligado, "/api/plano"));
        assertEquals(0, passar(ligado, "/api/configuracao"), "a tela precisa da marca da loja para abrir");
    }

    @Test
    void dentroDoPrazo_naoBloqueiaNada() throws Exception {
        logar(18L, "ADMINISTRADOR_LOJA");
        comLoja(OffsetDateTime.now().plusDays(5));

        assertEquals(0, passar(new RegraDeAcesso(true), "/api/pedidos/board"));
    }

    @Test
    void semPrazo_naoBloqueiaNada() throws Exception {
        logar(18L, "ADMINISTRADOR_LOJA");
        comLoja(null);

        assertEquals(0, passar(new RegraDeAcesso(true), "/api/pedidos/board"));
    }

    @Test
    void comOInterruptorDesligado_naoBloqueiaNemVencida() throws Exception {
        logar(18L, "ADMINISTRADOR_LOJA");
        comLoja(OffsetDateTime.now().minusDays(30));

        assertEquals(0, passar(new RegraDeAcesso(false), "/api/pedidos/board"));
        verify(lojas, never()).findById(any());
    }

    @Test
    void administradorDaPlataformaPassa_precisaArrumarALojaBloqueada() throws Exception {
        logar(18L, "ADMINISTRADOR_BORA");
        comLoja(OffsetDateTime.now().minusDays(30));

        assertEquals(0, passar(new RegraDeAcesso(true), "/api/pedidos/board"));
    }
}
