package br.com.bora.security;

import br.com.bora.entity.Loja;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cortar o acesso de um cliente é a coisa mais delicada do sistema: errar para mais tira do ar quem
 * está pagando, errar para menos dá o produto de graça. Estes testes trancam as duas pontas.
 *
 * <p>A regra deixou de valer só para o painel: o robô dos marketplaces também pergunta a ela antes de
 * puxar pedido. Loja suspensa seguia online no iFood, aceitando pedido sozinha, enquanto ninguém
 * conseguia abrir a tela para preparar.</p>
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

        assertTrue(desligado.podeOperar(loja(OffsetDateTime.now().minusDays(30), false)),
                "o interruptor nasce desligado de proposito: ligar tira cliente do ar e e decisao do dono");
        assertFalse(desligado.cortePorAssinaturaLigado());
    }

    @Test
    void comOCorteLigado_prazoVencidoBarra() {
        RegraDeAcesso ligado = new RegraDeAcesso(true);

        assertFalse(ligado.podeOperar(loja(OffsetDateTime.now().minusMinutes(1), false)));
    }

    @Test
    void comOCorteLigado_quemEstaDentroDoPrazoEntra() {
        RegraDeAcesso ligado = new RegraDeAcesso(true);

        assertTrue(ligado.podeOperar(loja(OffsetDateTime.now().plusDays(3), false)),
                "ainda dentro da cortesia ou da carencia");
    }

    @Test
    void semPrazo_entraComOCorteLigadoOuDesligado() {
        assertTrue(new RegraDeAcesso(true).podeOperar(loja(null, false)));
        assertTrue(new RegraDeAcesso(false).podeOperar(loja(null, false)));
    }

    @Test
    void suspensaPelaPlataformaNaoEntra_mesmoDentroDoPrazo() {
        assertFalse(new RegraDeAcesso(false).podeOperar(loja(OffsetDateTime.now().plusDays(10), true)),
                "a decisao da plataforma vale acima de qualquer prazo de pagamento");
    }

    @Test
    void lojaInexistenteNaoEntra() {
        assertFalse(new RegraDeAcesso(false).podeOperar(null));
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

    @Test
    void arquivada_naoOpera_mesmoComOCorteDesligado() {
        Loja l = loja(null, false);
        l.excluidaEm = OffsetDateTime.now();

        assertFalse(new RegraDeAcesso(false).podeOperar(l),
                "arquivar e decisao do dono: nao depende do interruptor de cobranca");
    }

    @Test
    void lojaQueNaoExiste_naoOpera() {
        assertFalse(new RegraDeAcesso(false).podeOperar(null),
                "integracao apontando para loja apagada nao pode virar pedido aceito no marketplace");
    }
}
