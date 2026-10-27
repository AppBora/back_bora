package br.com.bora.service;

import br.com.bora.repository.PedidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O termometro de vendas divide pelas semanas que EXISTIRAM.
 *
 * <h2>O defeito (achado pelo QA em 26/10/2026)</h2>
 * <p>O termometro compara a receita das ultimas 24h com a media das mesmas janelas nas 4 semanas
 * anteriores. A soma era dividida por <b>4 sempre</b>, mesmo quando a loja tinha 2 semanas de vida:
 * a base saia pela metade, o indice (hoje / base) saia pelo dobro, e o termometro dizia "vendas
 * saudaveis" para uma loja em queda de verdade.</p>
 *
 * <p>E loja nova e exatamente a que mais precisa do aviso. Pior: o {@code emQueda} deste servico e o
 * que decide abrir a promocao relampago automatica — com a base pela metade, ela nunca abria.</p>
 *
 * <h2>Por que nao dividir pelas semanas COM VENDA</h2>
 * <p>Era o conserto obvio e esta errado no outro sentido: uma loja aberta ha meses que legitimamente
 * vendeu zero numa quarta-feira teria a base inflada, o indice sairia baixo, e o sistema abriria
 * sozinho uma promocao de 15% — desconto de verdade em cima de um numero errado. O divisor e quantas
 * semanas a loja <b>existiu</b>, contado do primeiro pedido dela.</p>
 */
class OtermometroDivideP0rSemanaQueEXISTIUTest {

    private static final Long LOJA = 1L;

    private PedidoRepository pedidos;
    private VendasSaudeService service;

    @BeforeEach
    void montar() {
        pedidos = mock(PedidoRepository.class);
        when(pedidos.somaReceita(anyLong(), any(), any())).thenReturn(BigDecimal.ZERO);
        service = new VendasSaudeService(pedidos);
    }

    /** A loja nasceu ha {@code dias} dias. */
    private void lojaCom(int dias) {
        when(pedidos.primeiroPedidoEm(LOJA)).thenReturn(OffsetDateTime.now().minusDays(dias));
    }

    /** Receita da janela de 24h de {@code semanasAtras} semanas atras. */
    private void receitaDaSemana(int semanasAtras, String valor) {
        OffsetDateTime fimDaJanela = OffsetDateTime.now().minusDays(7L * semanasAtras);
        when(pedidos.somaReceita(eq(LOJA),
                argThat(i -> i != null && janelaCasa(i, fimDaJanela.minusHours(24))),
                argThat(f -> f != null && janelaCasa(f, fimDaJanela))))
                .thenReturn(new BigDecimal(valor));
    }

    /** Duas datas sao "a mesma janela" com folga de um minuto: o `now()` do teste e o do servico. */
    private static boolean janelaCasa(OffsetDateTime a, OffsetDateTime b) {
        return Math.abs(java.time.Duration.between(a, b).toMinutes()) <= 1;
    }

    private void receitaDeHoje(String valor) {
        OffsetDateTime agora = OffsetDateTime.now();
        when(pedidos.somaReceita(eq(LOJA),
                argThat(i -> i != null && janelaCasa(i, agora.minusHours(24))),
                argThat(f -> f != null && janelaCasa(f, agora))))
                .thenReturn(new BigDecimal(valor));
    }

    // ---------------------------------------------------------------- o defeito
    @Test
    void lojaDEduasSEMANASdivideP0RDOIS() {
        // 15 dias de vida: so as janelas de 1 e 2 semanas atras existiram.
        lojaCom(15);
        receitaDaSemana(1, "400.00");
        receitaDaSemana(2, "400.00");
        receitaDeHoje("400.00");

        Map<String, Object> t = service.termometro(LOJA);

        assertEquals(2, t.get("semanasNaBase"), "contou semana em que a loja nao existia");
        assertEquals(0, new BigDecimal("400.00").compareTo((BigDecimal) t.get("baseline")),
                "a base saiu " + t.get("baseline") + ": dividida por 4, ela vinha pela METADE");
    }

    @Test
    void aQUEDAdeUMAlojaNOVAehVISTA() {
        // O estrago inteiro, num caso: duas semanas vendendo R$ 400 e hoje R$ 200. E uma queda de
        // metade. Com o divisor 4 a base saia R$ 200 e o indice 1,00 -- "vendas saudaveis".
        lojaCom(15);
        receitaDaSemana(1, "400.00");
        receitaDaSemana(2, "400.00");
        receitaDeHoje("200.00");

        Map<String, Object> t = service.termometro(LOJA);

        assertEquals(0, new BigDecimal("0.50").compareTo((BigDecimal) t.get("indice")), "indice: " + t);
        assertEquals("QUEDA", t.get("faixa"),
                "loja nova em queda de metade aparecia como saudavel, e a promocao automatica nunca abria");
        assertTrue(service.emQueda(LOJA));
    }

    // ---------------------------------------------------------------- o outro sentido
    @Test
    void semanaSEMvendaDEumaLOJAantigaCONTAnoDIVISOR() {
        // Loja de 60 dias: as 4 semanas existiram. Duas tiveram venda, duas venderam zero -- e zero
        // e informacao, a loja estava de pe. Dividir por 2 inflaria a base, o indice sairia baixo, e
        // o sistema abriria sozinho uma promocao de 15%.
        lojaCom(60);
        receitaDaSemana(1, "400.00");
        receitaDaSemana(2, "400.00");
        receitaDeHoje("200.00");

        Map<String, Object> t = service.termometro(LOJA);

        assertEquals(4, t.get("semanasNaBase"));
        assertEquals(0, new BigDecimal("200.00").compareTo((BigDecimal) t.get("baseline")),
                "800 / 4 = 200: as duas semanas de zero contam");
        assertEquals("SAUDAVEL", t.get("faixa"), t.toString());
    }

    @Test
    void lojaDEtresSEMANASdivideP0RTRES() {
        lojaCom(22);
        receitaDaSemana(1, "300.00");
        receitaDaSemana(2, "300.00");
        receitaDaSemana(3, "300.00");
        receitaDeHoje("300.00");

        Map<String, Object> t = service.termometro(LOJA);

        assertEquals(3, t.get("semanasNaBase"));
        assertEquals(0, new BigDecimal("300.00").compareTo((BigDecimal) t.get("baseline")));
    }

    // ---------------------------------------------------------------- sem historico
    @Test
    void lojaSEMpedidoNENHUMdizSEMdados() {
        when(pedidos.primeiroPedidoEm(LOJA)).thenReturn(null);
        receitaDeHoje("100.00");

        Map<String, Object> t = service.termometro(LOJA);

        assertEquals("SEM_DADOS", t.get("faixa"));
        assertNull(t.get("baseline"));
        assertNull(t.get("indice"));
    }

    @Test
    void lojaDEumaSEMANAsoDIZsemDADOS() {
        // Uma semana de historico nao da para comparar, e e melhor dizer isso do que chutar.
        lojaCom(8);
        receitaDaSemana(1, "500.00");
        receitaDeHoje("100.00");

        assertEquals("SEM_DADOS", service.termometro(LOJA).get("faixa"));
        assertFalse(service.emQueda(LOJA), "nao se abre promocao sobre historico insuficiente");
    }

    @Test
    void lojaNOVAsemVENDAnasSEMANASanterioresNAOexplode() {
        // Divisao por zero: o divisor e `semanasQueExistiram`, e aqui ele e 0.
        lojaCom(2);
        receitaDeHoje("100.00");

        Map<String, Object> t = assertDoesNotThrow(() -> service.termometro(LOJA));
        assertEquals("SEM_DADOS", t.get("faixa"));
    }

    // ---------------------------------------------------------------- o que continua valendo
    @Test
    void naoCONSULTAaSEMANAqueNAOexistiu() {
        // Quatro consultas por loja, uma por semana, e isto roda em laco no painel. Pular a semana
        // que nao existiu tambem economiza a consulta.
        lojaCom(15);
        receitaDaSemana(1, "400.00");
        receitaDaSemana(2, "400.00");
        receitaDeHoje("400.00");

        service.termometro(LOJA);

        // 1 de hoje + 2 das semanas que existiram = 3, e nao 5.
        verify(pedidos, times(3)).somaReceita(anyLong(), any(), any());
    }

    @Test
    void aFAIXAdeATENCAOcontinuaNOmeio() {
        lojaCom(60);
        receitaDaSemana(1, "400.00");
        receitaDaSemana(2, "400.00");
        receitaDaSemana(3, "400.00");
        receitaDaSemana(4, "400.00");
        receitaDeHoje("320.00"); // 80% da base

        Map<String, Object> t = service.termometro(LOJA);
        assertEquals("ATENCAO", t.get("faixa"), t.toString());
        assertFalse(service.emQueda(LOJA), "queda leve nao abre promocao sozinha");
    }
}
