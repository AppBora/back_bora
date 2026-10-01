package br.com.bora.security;

import br.com.bora.entity.Loja;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cortar o acesso de um cliente é a coisa mais delicada do sistema: errar para mais tira do ar quem
 * está pagando, errar para menos dá o produto de graça. Estes testes trancam as duas pontas.
 */
class RegraDeAcessoTest {

    private Loja loja(OffsetDateTime acessoAte, boolean suspensaPelaPlataforma) {
        Loja l = new Loja();
        l.id = 18L;
        l.ativo = true;
        l.acessoAte = acessoAte;
        l.suspensaPelaPlataforma = suspensaPelaPlataforma;
        return l;
    }

    @Test
    void comOCorteDesligado_lojaVencidaContinuaEntrando() {
        RegraDeAcesso desligado = new RegraDeAcesso(false);

        assertTrue(desligado.podeUsarOPainel(loja(OffsetDateTime.now().minusDays(30), false)),
                "o interruptor nasce desligado de proposito: ligar tira cliente do ar e e decisao do dono");
        assertFalse(desligado.cortePorAssinaturaLigado());
    }

    @Test
    void comOCorteLigado_prazoVencidoBarra() {
        RegraDeAcesso ligado = new RegraDeAcesso(true);

        assertFalse(ligado.podeUsarOPainel(loja(OffsetDateTime.now().minusMinutes(1), false)));
    }

    @Test
    void comOCorteLigado_quemEstaDentroDoPrazoEntra() {
        RegraDeAcesso ligado = new RegraDeAcesso(true);

        assertTrue(ligado.podeUsarOPainel(loja(OffsetDateTime.now().plusDays(3), false)),
                "ainda dentro da cortesia ou da carencia");
    }

    @Test
    void semPrazo_entraComOCorteLigadoOuDesligado() {
        assertTrue(new RegraDeAcesso(true).podeUsarOPainel(loja(null, false)));
        assertTrue(new RegraDeAcesso(false).podeUsarOPainel(loja(null, false)));
    }

    @Test
    void suspensaPelaPlataformaNaoEntra_mesmoDentroDoPrazo() {
        assertFalse(new RegraDeAcesso(false).podeUsarOPainel(loja(OffsetDateTime.now().plusDays(10), true)),
                "a decisao da plataforma vale acima de qualquer prazo de pagamento");
    }

    @Test
    void lojaInexistenteNaoEntra() {
        assertFalse(new RegraDeAcesso(false).podeUsarOPainel(null));
    }

    @Test
    void moduloIaEntraNaMensalidade() {
        Loja l = loja(null, false);
        assertEquals(0, new java.math.BigDecimal("199.00").compareTo(l.precoComModulos()), "plano único");

        l.moduloIa = true;

        assertEquals(0, new java.math.BigDecimal("298.00").compareTo(l.precoComModulos()),
                "o add-on de R$ 99 era um visto na tela que nunca chegava na cobranca");
        assertEquals(0, new java.math.BigDecimal("199.00").compareTo(l.precoEfetivo()),
                "o preco do plano em si nao muda");
    }
}
