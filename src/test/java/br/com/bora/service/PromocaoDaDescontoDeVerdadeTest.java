package br.com.bora.service;

import br.com.bora.entity.Cupom;
import br.com.bora.entity.Promocao;
import br.com.bora.repository.CupomRepository;
import br.com.bora.repository.PromocaoRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Promocao tem que dar desconto de verdade no checkout.
 *
 * <p><b>O defeito que isto fecha (09/10/2026):</b> o checkout publico so lia a tabela {@code cupom}. A
 * tela de Promocoes e o botao do Dashboard gravavam na tabela {@code promocao}, que ninguem lia. O
 * botao dizia "Abrir o cupom BORA15 (15% de desconto, 3 horas, ate 20 usos) no seu cardapio agora?" e
 * o cliente que digitasse BORA15 recebia "Cupom invalido ou vencido". O termometro de vendas ainda
 * abria uma dessas sozinho, todo dia, para toda loja em queda.</p>
 *
 * <p>Os dois limites que a promocao tem e o cupom nao tem — a janela em HORAS e o teto de usos — sao a
 * razao de ela nao ter virado simplesmente um cupom. Sao as duas protecoes que o botao promete.</p>
 */
class PromocaoDaDescontoDeVerdadeTest {

    private static final Long LOJA = 18L;

    private final CupomRepository cupons = mock(CupomRepository.class);
    private final PromocaoRepository promocoes = mock(PromocaoRepository.class);
    /** Ver {@link #oUsoEhContadoDENTROdeUmaTransacaoABERTAaqui()}: a transacao e o conserto. */
    private final org.springframework.transaction.PlatformTransactionManager gerente = gerenciadorFalso();
    private final DescontoService svc = new DescontoService(cupons, promocoes, gerente);

    /** Abre uma transacao de mentira e deixa o trabalho acontecer, para a ordem ser verificavel. */
    private static org.springframework.transaction.PlatformTransactionManager gerenciadorFalso() {
        var g = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(g.getTransaction(any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        return g;
    }

    private Promocao relampago(int percentual, Integer limite, Integer usos, int horasRestantes) {
        Promocao p = new Promocao();
        p.setId(7L);
        p.setLojaId(LOJA);
        p.setCodigo("BORA15");
        p.setTipo("RELAMPAGO");
        p.setPercentualDesconto(new BigDecimal(percentual));
        p.setInicio(OffsetDateTime.now().minusMinutes(10));
        p.setFim(OffsetDateTime.now().plusHours(horasRestantes));
        p.setLimiteUsos(limite);
        p.setUsos(usos);
        p.setAtiva(true);
        when(promocoes.findByLojaIdAndCodigoIgnoreCase(LOJA, "BORA15")).thenReturn(Optional.of(p));
        when(cupons.findByLojaIdAndCodigoIgnoreCase(anyLong(), eq("BORA15"))).thenReturn(Optional.empty());
        return p;
    }

    @Test
    void oCodigoDoBotaoDoDashboardAbateNoCheckout() {
        relampago(15, 20, 0, 3);

        var d = svc.resolver(LOJA, "BORA15", new BigDecimal("100.00"));

        assertTrue(d.isPresent(), "BORA15 tinha que valer: era isto que o botao prometia e nao acontecia");
        assertEquals(0, d.get().valorAbatido().compareTo(new BigDecimal("15.00")));
        assertTrue(d.get().ehPromocao());
    }

    // A busca que ignora a caixa ("bora15" achar "BORA15") e do Spring Data, nao deste servico: mock
    // devolveria o que eu mandasse e nao provaria nada. Isso esta provado contra o banco de verdade em
    // DescontoNoCheckoutIT, junto com o incremento atomico do teto de usos.

    @Test
    void promocaoVencidaNaoVale() {
        Promocao p = relampago(15, 20, 0, 3);
        p.setFim(OffsetDateTime.now().minusMinutes(1)); // as 3 horas acabaram

        assertTrue(svc.resolver(LOJA, "BORA15", new BigDecimal("100.00")).isEmpty());
    }

    @Test
    void promocaoQueAindaNaoComecouNaoVale() {
        Promocao p = relampago(15, 20, 0, 3);
        p.setInicio(OffsetDateTime.now().plusHours(1));

        assertTrue(svc.resolver(LOJA, "BORA15", new BigDecimal("100.00")).isEmpty());
    }

    @Test
    void promocaoDesativadaNaoVale() {
        Promocao p = relampago(15, 20, 0, 3);
        p.setAtiva(false);

        assertTrue(svc.resolver(LOJA, "BORA15", new BigDecimal("100.00")).isEmpty());
    }

    @Test
    void tetoDeUsosEhRespeitado() {
        relampago(15, 20, 20, 3); // os 20 usos acabaram

        assertTrue(svc.resolver(LOJA, "BORA15", new BigDecimal("100.00")).isEmpty(),
                "o 'ate 20 usos' que o botao promete tem que valer");
    }

    @Test
    void umUsoAntesDoTetoAindaVale() {
        relampago(15, 20, 19, 3);

        assertTrue(svc.resolver(LOJA, "BORA15", new BigDecimal("100.00")).isPresent());
    }

    @Test
    void semTetoDeUsosNaoTravaNunca() {
        relampago(15, null, 9999, 3);

        assertTrue(svc.resolver(LOJA, "BORA15", new BigDecimal("100.00")).isPresent());
    }

    @Test
    void oUsoEhContabilizadoNoBancoENaoEmJava() {
        relampago(15, 20, 0, 3);
        var d = svc.resolver(LOJA, "BORA15", new BigDecimal("100.00")).get();
        when(promocoes.somarUmUso(7L)).thenReturn(1);

        svc.contabilizarUso(d);

        // Incremento condicional no banco: dez pedidos ao mesmo tempo liam o mesmo contador antes de
        // qualquer um gravar, e o teto de 20 nao seria 20.
        verify(promocoes).somarUmUso(7L);
        verify(promocoes, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cupomNaoContabilizaUsoDePromocao() {
        Cupom c = new Cupom();
        c.lojaId = LOJA; c.codigo = "DEZ"; c.tipo = "PERCENTUAL"; c.valor = new BigDecimal("10");
        when(cupons.findByLojaIdAndCodigoIgnoreCase(LOJA, "DEZ")).thenReturn(Optional.of(c));

        var d = svc.resolver(LOJA, "DEZ", new BigDecimal("50.00")).get();
        svc.contabilizarUso(d);

        assertFalse(d.ehPromocao());
        assertEquals(0, d.valorAbatido().compareTo(new BigDecimal("5.00")));
        verify(promocoes, never()).somarUmUso(anyLong());
    }

    @Test
    void cupomTemPrecedenciaSobrePromocaoComOMesmoCodigo() {
        relampago(15, 20, 0, 3);
        Cupom c = new Cupom();
        c.lojaId = LOJA; c.codigo = "BORA15"; c.tipo = "VALOR"; c.valor = new BigDecimal("30");
        when(cupons.findByLojaIdAndCodigoIgnoreCase(LOJA, "BORA15")).thenReturn(Optional.of(c));

        var d = svc.resolver(LOJA, "BORA15", new BigDecimal("100.00")).get();

        assertFalse(d.ehPromocao(), "o cupom cadastrado a mao e o que a loja quis");
        assertEquals(0, d.valorAbatido().compareTo(new BigDecimal("30.00")));
    }

    @Test
    void cupomVencidoNaoVale() {
        Cupom c = new Cupom();
        c.lojaId = LOJA; c.codigo = "DEZ"; c.valor = new BigDecimal("10");
        // Fuso explicito: a validade do cupom e uma regra de negocio em horario de Brasilia, e o
        // container roda em UTC. Com LocalDate.now() sem fuso, este teste passava na minha maquina e
        // falhava no build da imagem depois das 21h — o mesmo defeito que o Cupom.valido() tinha.
        c.validade = LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")).minusDays(1);
        when(cupons.findByLojaIdAndCodigoIgnoreCase(LOJA, "DEZ")).thenReturn(Optional.of(c));
        when(promocoes.findByLojaIdAndCodigoIgnoreCase(LOJA, "DEZ")).thenReturn(Optional.empty());

        assertTrue(svc.resolver(LOJA, "DEZ", new BigDecimal("50.00")).isEmpty());
    }

    @Test
    void codigoQueNaoExisteEmNenhumaDasDuasTabelas() {
        when(cupons.findByLojaIdAndCodigoIgnoreCase(LOJA, "NADA")).thenReturn(Optional.empty());
        when(promocoes.findByLojaIdAndCodigoIgnoreCase(LOJA, "NADA")).thenReturn(Optional.empty());

        assertTrue(svc.resolver(LOJA, "NADA", new BigDecimal("50.00")).isEmpty());
    }

    @Test
    void oDescontoNuncaPassaDoTotal() {
        relampago(150, null, 0, 3); // percentual absurdo cadastrado a mao

        var d = svc.resolver(LOJA, "BORA15", new BigDecimal("40.00")).get();

        assertEquals(0, d.valorAbatido().compareTo(new BigDecimal("40.00")),
                "o PublicController recusa o que zera o pedido; aqui o piso e nao passar do total");
    }

    @Test
    void codigoVazioOuTotalZeradoNaoResolveNada() {
        assertTrue(svc.resolver(LOJA, "", new BigDecimal("50.00")).isEmpty());
        assertTrue(svc.resolver(LOJA, null, new BigDecimal("50.00")).isEmpty());
        assertTrue(svc.resolver(LOJA, "BORA15", BigDecimal.ZERO).isEmpty());
    }

    // ============== a contagem do uso abre transacao propria (defeito medido em 10/11/2026)

    @Test
    void oUsoEhContadoDENTROdeUmaTransacaoABERTAaqui() {
        relampago(15, 20, 0, 3);
        var d = svc.resolver(LOJA, "BORA15", new BigDecimal("100.00")).get();
        when(promocoes.somarUmUso(7L)).thenReturn(1);

        svc.contabilizarUso(d);

        // O QUE ESTE TESTE TRANCA: o somarUmUso e @Modifying(flushAutomatically = true), e o flush do
        // JPA EXIGE transacao ativa. O POST do pedido do cardapio nao tem @Transactional de proposito
        // (conserto de 26/10/2026), e esta contagem roda DEPOIS do tx.execute que grava o pedido. Sem
        // nenhuma transacao em volta, o flush estourava TransactionRequiredException com o PEDIDO JA
        // GRAVADO e o desconto aplicado: 500 na cara do cliente, pedido duplicado na tentativa
        // seguinte, e contador de usos parado em zero -- desconto sem teto.
        //
        // Com MOCK do repositorio nao da para reproduzir o estouro, e foi exatamente por isso que
        // este arquivo nao pegou o defeito (o outro teste, DescontoNoCheckoutIT, e @DataJpaTest, que
        // roda DENTRO de uma transacao). O que DA para provar aqui e a estrutura: a transacao e
        // aberta ANTES do incremento. Quem tirar o TransactionTemplate do servico reprova aqui.
        var ordem = inOrder(gerente, promocoes);
        ordem.verify(gerente).getTransaction(any());
        ordem.verify(promocoes).somarUmUso(7L);
        ordem.verify(gerente).commit(any());
    }

    @Test
    void contagemQueFALHAnaoDERRUBAoPEDIDO() {
        relampago(15, 20, 0, 3);
        var d = svc.resolver(LOJA, "BORA15", new BigDecimal("100.00")).get();
        when(promocoes.somarUmUso(7L)).thenThrow(
                new jakarta.persistence.TransactionRequiredException("flush sem transacao"));

        // A promessa que esta no javadoc do metodo: "nao faz o pedido falhar". Quando o QA mediu, ele
        // fazia -- no pior momento possivel, com a venda ja registrada. Mesmo que um dia alguem tire
        // a transacao outra vez, o cliente nao pode receber erro depois de o pedido estar gravado.
        assertDoesNotThrow(() -> svc.contabilizarUso(d));
    }
}
