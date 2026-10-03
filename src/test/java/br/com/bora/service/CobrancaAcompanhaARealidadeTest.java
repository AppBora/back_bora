package br.com.bora.service;

import br.com.bora.entity.Assinatura;
import br.com.bora.entity.Loja;
import br.com.bora.entity.PagamentoAssinatura;
import br.com.bora.entity.StatusAssinatura;
import br.com.bora.repository.AssinaturaRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PagamentoAssinaturaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A cobrança tem que acompanhar a realidade da loja.
 *
 * <p>Quatro buracos da mesma família: o valor gravado aqui podia divergir do que o Asaas cobra, a
 * carência recomeçava a cada aviso de atraso, cancelar não tirava o acesso e estorno não existia.
 * Juntos, davam sistema de graça por tempo indeterminado.</p>
 */
class CobrancaAcompanhaARealidadeTest {

    private AssinaturaRepository repo;
    private LojaRepository lojas;
    private PagamentoAssinaturaRepository pagamentos;
    private AsaasClient asaas;
    private AssinaturaService service;
    private Loja loja;
    private Assinatura assinatura;

    @BeforeEach
    void montar() {
        repo = mock(AssinaturaRepository.class);
        lojas = mock(LojaRepository.class);
        pagamentos = mock(PagamentoAssinaturaRepository.class);
        asaas = mock(AsaasClient.class);
        service = new AssinaturaService(repo, lojas, mock(UsuarioRepository.class), asaas,
                mock(AuthContext.class), pagamentos, 10);

        loja = new Loja();
        loja.id = 18L;
        loja.nome = "Zirá";
        loja.moduloIa = true; // mensalidade de R$ 298
        when(lojas.findById(18L)).thenReturn(Optional.of(loja));
        when(lojas.save(any(Loja.class))).thenAnswer(i -> i.getArgument(0));

        assinatura = new Assinatura();
        assinatura.setLojaId(18L);
        assinatura.setStatus(StatusAssinatura.ATIVA);
        assinatura.setValor(new BigDecimal("199.00"));
        assinatura.setAsaasSubscriptionId("sub_1");
        when(repo.findByLojaId(18L)).thenReturn(Optional.of(assinatura));
        when(repo.findByAsaasSubscriptionId("sub_1")).thenReturn(Optional.of(assinatura));
        when(repo.save(any(Assinatura.class))).thenAnswer(i -> i.getArgument(0));
        when(asaas.configurado()).thenReturn(true);
    }

    // ---------- item 6: o nosso numero so muda quando o Asaas aceita ----------

    @Test
    void asaasRecusou_oNossoValorNaoMente() {
        doThrow(new RuntimeException("502")).when(asaas)
                .atualizarAssinatura(anyString(), anyDouble(), anyString());

        assertEquals("FALHA_NO_ASAAS", service.sincronizarValorComMotivo(18L));
        assertEquals(0, new BigDecimal("199.00").compareTo(assinatura.getValor()),
                "gravar 298 aqui enquanto o Asaas cobra 199 e a divergencia que isto evita");
    }

    @Test
    void semCobrancaNoAsaas_naoGravaValorNovo() {
        assinatura.setAsaasSubscriptionId(null);

        assertEquals("SEM_ID_NO_ASAAS", service.sincronizarValorComMotivo(18L));
        assertEquals(0, new BigDecimal("199.00").compareTo(assinatura.getValor()));
    }

    @Test
    void asaasAceitou_entaoGrava() {
        assertEquals("ATUALIZADA", service.sincronizarValorComMotivo(18L));
        assertEquals(0, new BigDecimal("298.00").compareTo(assinatura.getValor()));
        verify(asaas).atualizarAssinatura(eq("sub_1"), eq(298.00), contains("Modulo IA"));
    }

    // ---------- item 5: a carencia comeca uma vez ----------

    @Test
    void faturaVencidaDuasVezes_naoRenovaACarencia() {
        service.processarWebhook("PAYMENT_OVERDUE", "sub_1");
        OffsetDateTime primeiroPrazo = loja.acessoAte;
        assertNotNull(primeiroPrazo, "o primeiro atraso tem que comecar a contar");

        service.processarWebhook("PAYMENT_OVERDUE", "sub_1"); // reenvio, ou a fatura do mes seguinte

        assertEquals(primeiroPrazo, loja.acessoAte,
                "cada aviso renovando o prazo dava ~10 dias de graca por mes, para sempre");
    }

    @Test
    void pagou_zeraOPrazo_eUmNovoAtrasoVoltaAContar() {
        service.processarWebhook("PAYMENT_OVERDUE", "sub_1");
        service.processarWebhook("PAYMENT_CONFIRMED", "sub_1");
        assertNull(loja.acessoAte, "quem esta pagando nao tem data de fim");

        service.processarWebhook("PAYMENT_OVERDUE", "sub_1");
        assertNotNull(loja.acessoAte);
    }

    // ---------- item 3: cancelar fecha o acesso ----------

    @Test
    void cancelarAssinatura_poeDataDeFimNoAcesso() {
        service.processarWebhook("SUBSCRIPTION_DELETED", "sub_1");

        assertEquals(StatusAssinatura.CANCELADA, assinatura.getStatus());
        assertNotNull(loja.acessoAte, "sem data, o painel ficava aberto para sempre de graca");
        assertTrue(loja.acessoAte.isAfter(OffsetDateTime.now()), "nao corta na hora: respeita a carencia");
    }

    @Test
    void reativarSemAssinaturaViva_devolveAcessoComPrazo() {
        assinatura.setStatus(StatusAssinatura.CANCELADA);

        String recado = service.prazoAoReativar(18L);

        assertNotNull(loja.acessoAte, "voltava com acesso sem fim e sem cobranca");
        assertTrue(recado.contains("10"));
    }

    @Test
    void reativarComAssinaturaAtiva_naoPoePrazoNenhum() {
        service.prazoAoReativar(18L);
        assertNull(loja.acessoAte, "quem esta pagando nao pode ganhar data de corte");
    }

    // ---------- item 4: estorno sai do faturamento ----------

    @Test
    void estorno_marcaAMensalidadeComoDevolvida() {
        PagamentoAssinatura pg = new PagamentoAssinatura();
        pg.lojaId = 18L;
        pg.asaasPaymentId = "pay_1";
        pg.valor = new BigDecimal("199.00");
        when(pagamentos.findByAsaasPaymentId("pay_1")).thenReturn(Optional.of(pg));
        when(pagamentos.save(any(PagamentoAssinatura.class))).thenAnswer(i -> i.getArgument(0));

        service.processarWebhook("PAYMENT_REFUNDED", null, "pay_1", null, null);

        assertTrue(pg.estornado(), "dinheiro devolvido seguia contando como faturado e pedindo nota");
        verify(pagamentos).save(pg);
    }

    @Test
    void estornoSemNumeroDeAssinatura_aindaAssimEhTratado() {
        PagamentoAssinatura pg = new PagamentoAssinatura();
        pg.asaasPaymentId = "pay_9";
        when(pagamentos.findByAsaasPaymentId("pay_9")).thenReturn(Optional.of(pg));
        when(pagamentos.save(any(PagamentoAssinatura.class))).thenAnswer(i -> i.getArgument(0));

        // o aviso de devolucao nem sempre carrega o numero da assinatura
        service.processarWebhook("PAYMENT_CHARGEBACK_REQUESTED", null, "pay_9", null, null);

        assertTrue(pg.estornado());
    }

    @Test
    void estornoReenviado_naoMudaNada() {
        PagamentoAssinatura pg = new PagamentoAssinatura();
        pg.asaasPaymentId = "pay_1";
        pg.estornadoEm = OffsetDateTime.now().minusDays(1);
        when(pagamentos.findByAsaasPaymentId("pay_1")).thenReturn(Optional.of(pg));

        service.processarWebhook("PAYMENT_REFUNDED", null, "pay_1", null, null);

        verify(pagamentos, never()).save(any(PagamentoAssinatura.class));
    }
}
