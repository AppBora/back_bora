package br.com.bora.service;

import br.com.bora.entity.Assinatura;
import br.com.bora.entity.Loja;
import br.com.bora.entity.PagamentoAssinatura;
import br.com.bora.entity.StatusAssinatura;
import br.com.bora.repository.AssinaturaRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PagamentoAssinaturaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Estorno parcial desconta o que voltou — não a mensalidade inteira, e não nada.
 *
 * <h2>O item 3.6 da varredura, e as duas coisas que medir corrigiu</h2>
 * <p>A varredura dizia: <i>"um estorno de R$ 10 tira os R$ 199 do mês"</i> e <i>"{@code estornadoEm}
 * nunca é limpo"</i>. <b>As duas estavam erradas</b>, e isso foi medido em 21/10/2026:</p>
 * <ul>
 *   <li>Já existia um ramo próprio para {@code PAYMENT_PARTIALLY_REFUNDED} que <b>recusava</b> marcar
 *       a cobrança como estornada, exatamente para não tirar a mensalidade inteira — com um comentário
 *       citando este item da varredura.</li>
 *   <li>E {@code estornadoEm} volta a nulo quando a disputa de chargeback é vencida.</li>
 * </ul>
 *
 * <p><b>O que sobrava de verdade:</b> o valor devolvido não era gravado em lugar nenhum. A mensalidade
 * contava <b>cheia</b> no faturamento e o único rastro era uma linha de log — que ninguém lê ao fechar
 * o mês. Agora há coluna (V57), o faturamento desconta, e a linha do relatório mostra quanto voltou e
 * quanto ficou.</p>
 *
 * <h2>Quando o Asaas não manda o valor</h2>
 * <p>A página de webhooks do Asaas mostra o campo {@code refunds} no payload mas não descreve a
 * estrutura dele. Sem valor, a cobrança fica <b>cheia</b> com um aviso alto, em vez de receber um
 * número chutado: não se inventa valor em faturamento.</p>
 */
class EstornoParcialDescontaOqueVoltouTest {

    private PagamentoAssinaturaRepository pagamentos;
    private AssinaturaService service;

    private static final Long LOJA = 18L;
    private static final String COBRANCA = "pay_mensalidade";

    private PagamentoAssinatura pg;
    private Loja loja;

    @BeforeEach
    void montar() {
        AssinaturaRepository repo = mock(AssinaturaRepository.class);
        LojaRepository lojas = mock(LojaRepository.class);
        pagamentos = mock(PagamentoAssinaturaRepository.class);

        Assinatura a = new Assinatura();
        a.setId(1L);
        a.setLojaId(LOJA);
        a.setStatus(StatusAssinatura.ATIVA);
        a.setAsaasSubscriptionId("sub_1");

        loja = new Loja();
        loja.id = LOJA;
        loja.setNome("Zirá Açaíteria");
        loja.setAtivo(true);

        pg = new PagamentoAssinatura();
        pg.id = 7L;
        pg.lojaId = LOJA;
        pg.asaasPaymentId = COBRANCA;
        pg.valor = new BigDecimal("199.00");
        pg.pagoEm = OffsetDateTime.now().minusDays(3);

        when(repo.findByAsaasSubscriptionId("sub_1")).thenReturn(Optional.of(a));
        when(repo.findByLojaId(LOJA)).thenReturn(Optional.of(a));
        when(lojas.findById(LOJA)).thenReturn(Optional.of(loja));
        when(lojas.save(any(Loja.class))).thenAnswer(i -> i.getArgument(0));
        when(repo.save(any(Assinatura.class))).thenAnswer(i -> i.getArgument(0));
        when(pagamentos.findByAsaasPaymentId(COBRANCA)).thenReturn(Optional.of(pg));
        when(pagamentos.save(any(PagamentoAssinatura.class))).thenAnswer(i -> i.getArgument(0));

        service = new AssinaturaService(repo, lojas, mock(br.com.bora.repository.UsuarioRepository.class),
                mock(AsaasClient.class), mock(br.com.bora.security.AuthContext.class), pagamentos, 10);
    }

    private void estornoParcialDe(String valor) {
        service.processarWebhook("PAYMENT_PARTIALLY_REFUNDED", "sub_1", COBRANCA,
                valor == null ? null : new BigDecimal(valor), pg.valor, pg.pagoEm);
    }

    // =============================================================== o valor é guardado e descontado

    @Test
    void oVALORdevolvidoEHguardado() {
        estornoParcialDe("10.00");

        assertEquals(0, new BigDecimal("10.00").compareTo(pg.estornadoValor));
        verify(pagamentos).save(pg);
    }

    @Test
    void oFATURAMENTOdescontaSOoQUEvoltou() {
        // O coracao do item: R$ 10 de volta nao pode tirar os R$ 199 do mes, e tambem nao pode
        // deixar o mes contando R$ 199.
        estornoParcialDe("10.00");

        assertEquals(0, new BigDecimal("189.00").compareTo(pg.valorQueFicou()));
    }

    @Test
    void aCOBRANCAnaoEHmarcadaComoESTORNADA() {
        // Marcar tiraria os R$ 199 inteiros da conta -- e o cliente PAGOU.
        estornoParcialDe("10.00");

        assertFalse(pg.estornado(), "estorno parcial nao e estorno");
        assertTrue(pg.parcialmenteEstornado());
    }

    @Test
    void oACESSOnaoEHcortado() {
        // Ele pagou. Cortar por uma devolucao parcial derrubaria uma loja que esta em dia.
        estornoParcialDe("10.00");

        assertTrue(loja.getAtivo());
        assertNull(loja.acessoAte, "o prazo de acesso nao pode nascer de um estorno parcial");
    }

    @Test
    void DUASdevolucoesSOMAM() {
        // Podem vir duas no mesmo pagamento.
        estornoParcialDe("10.00");
        estornoParcialDe("15.00");

        assertEquals(0, new BigDecimal("25.00").compareTo(pg.estornadoValor));
        assertEquals(0, new BigDecimal("174.00").compareTo(pg.valorQueFicou()));
    }

    @Test
    void aSOMAqueALCANCAoVALORcheioVIRAestornoINTEGRAL() {
        // Devolveram tudo, em partes. Ai e estorno integral: sai do faturamento, como qualquer outro.
        estornoParcialDe("100.00");
        estornoParcialDe("99.00");

        assertTrue(pg.estornado(), "a soma alcancou o valor cheio");
        assertEquals(0, BigDecimal.ZERO.compareTo(pg.valorQueFicou()));
    }

    // =============================================================== quando o valor nao vem

    @Test
    void SEMvalorNOaviso_aMENSALIDADEficaCHEIA() {
        // O payload do Asaas tem o campo `refunds` mas a documentacao nao descreve a estrutura. Sem
        // valor, nao se chuta: fica cheia, com aviso alto no log.
        estornoParcialDe(null);

        assertNull(pg.estornadoValor);
        assertEquals(0, new BigDecimal("199.00").compareTo(pg.valorQueFicou()));
        verify(pagamentos, never()).save(any(PagamentoAssinatura.class));
    }

    @Test
    void valorZEROouNEGATIVOvaleComoSEMvalor() {
        estornoParcialDe("0");

        assertNull(pg.estornadoValor);
        verify(pagamentos, never()).save(any(PagamentoAssinatura.class));
    }

    // =============================================================== a conta do que ficou

    @Test
    void oQUEficouNUNCAeNEGATIVO() {
        // Devolucao maior que a cobranca (erro no Asaas, ou valor da assinatura mudado depois) nao
        // pode virar receita negativa no relatorio.
        pg.estornadoValor = new BigDecimal("500.00");

        assertEquals(0, BigDecimal.ZERO.compareTo(pg.valorQueFicou()));
    }

    @Test
    void semESTORNOnenhumOvalorFICAcheio() {
        assertEquals(0, new BigDecimal("199.00").compareTo(pg.valorQueFicou()));
        assertFalse(pg.parcialmenteEstornado());
    }

    @Test
    void linhaANTESdaV57contaComoESTORNOintegral() {
        // `estornadoEm` preenchido e `estornadoValor` nulo e o estado de toda linha anterior a V57 --
        // era o unico que o sistema sabia gravar. Tratar como integral e a leitura honesta para o
        // passado; tratar como "sem estorno" faria dinheiro devolvido voltar ao faturamento.
        pg.estornadoEm = OffsetDateTime.now().minusDays(1);
        pg.estornadoValor = null;

        assertTrue(pg.estornado());
        assertEquals(0, BigDecimal.ZERO.compareTo(pg.valorQueFicou()));
        assertFalse(pg.parcialmenteEstornado(), "integral nao e parcial");
    }

    // =============================================================== o integral nao mudou

    @Test
    void oESTORNOintegralCONTINUAtirandoTUDOeCORTANDO() {
        service.processarWebhook("PAYMENT_REFUNDED", "sub_1", COBRANCA, null, pg.valor, pg.pagoEm);

        assertTrue(pg.estornado());
        assertEquals(0, BigDecimal.ZERO.compareTo(pg.valorQueFicou()));
    }
}
