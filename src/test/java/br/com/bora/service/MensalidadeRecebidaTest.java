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
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A mensalidade recebida precisa deixar rastro.
 *
 * <p>Até aqui o pagamento virava um status "ATIVA" e nada mais: o identificador da cobrança, o valor e
 * a data iam para o lixo. Não dava para dizer quanto entrou no mês, de quem, nem havia onde pendurar a
 * nota fiscal que o cliente com CNPJ pede.</p>
 */
class MensalidadeRecebidaTest {

    private AssinaturaRepository repo;
    private LojaRepository lojas;
    private PagamentoAssinaturaRepository pagamentos;
    private AssinaturaService service;

    @BeforeEach
    void montar() {
        repo = mock(AssinaturaRepository.class);
        lojas = mock(LojaRepository.class);
        pagamentos = mock(PagamentoAssinaturaRepository.class);
        service = new AssinaturaService(repo, lojas, mock(UsuarioRepository.class),
                mock(AsaasClient.class), mock(AuthContext.class), pagamentos, 10);
        when(repo.save(any(Assinatura.class))).thenAnswer(i -> i.getArgument(0));
        when(lojas.save(any(Loja.class))).thenAnswer(i -> i.getArgument(0));

        Assinatura a = new Assinatura();
        a.setId(5L);
        a.setLojaId(18L);
        a.setStatus(StatusAssinatura.PENDENTE);
        a.setValor(new BigDecimal("199.00"));
        a.setAsaasSubscriptionId("sub_0001");
        when(repo.findByAsaasSubscriptionId("sub_0001")).thenReturn(Optional.of(a));

        Loja l = new Loja();
        l.id = 18L;
        l.nome = "Zirá Açaíteria";
        when(lojas.findById(18L)).thenReturn(Optional.of(l));
    }

    private PagamentoAssinatura capturar() {
        ArgumentCaptor<PagamentoAssinatura> c = ArgumentCaptor.forClass(PagamentoAssinatura.class);
        verify(pagamentos).save(c.capture());
        return c.getValue();
    }

    @Test
    void pagamentoConfirmadoVireLinhaDeFaturamento() {
        OffsetDateTime quando = OffsetDateTime.now().minusDays(1);

        service.processarWebhook("PAYMENT_CONFIRMED", "sub_0001", "pay_123", new BigDecimal("199.00"), quando);

        PagamentoAssinatura p = capturar();
        assertEquals(18L, p.lojaId);
        assertEquals(5L, p.assinaturaId);
        assertEquals("pay_123", p.asaasPaymentId);
        assertEquals(0, new BigDecimal("199.00").compareTo(p.valor));
        assertEquals(quando, p.pagoEm);
        assertEquals("Zirá Açaíteria", p.descricao);
        assertFalse(p.temNota(), "nasce sem nota — é justamente o que a lista do mês precisa mostrar");
    }

    @Test
    void mesmoPagamentoDuasVezesNaoFaturaDobrado() {
        when(pagamentos.existsByAsaasPaymentId("pay_123")).thenReturn(true); // o Asaas reenviou o aviso

        service.processarWebhook("PAYMENT_RECEIVED", "sub_0001", "pay_123", new BigDecimal("199.00"), null);

        verify(pagamentos, never()).save(any(PagamentoAssinatura.class));
    }

    @Test
    void semValorNoAviso_usaOValorDaAssinatura() {
        service.processarWebhook("PAYMENT_CONFIRMED", "sub_0001", "pay_sem_valor", null, null);

        PagamentoAssinatura p = capturar();
        assertEquals(0, new BigDecimal("199.00").compareTo(p.valor), "nunca gravar mensalidade zerada");
        assertNotNull(p.pagoEm, "sem data o pagamento some do faturamento do mês");
    }

    @Test
    void faturaVencidaOuAssinaturaCancelada_naoEntramNoFaturamento() {
        service.processarWebhook("PAYMENT_OVERDUE", "sub_0001", "pay_999", new BigDecimal("199.00"), null);
        service.processarWebhook("SUBSCRIPTION_DELETED", "sub_0001", "pay_998", new BigDecimal("199.00"), null);
        service.processarWebhook("PAYMENT_DELETED", "sub_0001", "pay_997", new BigDecimal("199.00"), null);

        verify(pagamentos, never()).save(any(PagamentoAssinatura.class));
        verify(pagamentos, never()).existsByAsaasPaymentId(anyString());
    }

    @Test
    void avisoSemCobranca_naoGravaLinhaFantasma() {
        service.processarWebhook("PAYMENT_CONFIRMED", "sub_0001", null, new BigDecimal("199.00"), null);
        service.processarWebhook("PAYMENT_CONFIRMED", "sub_0001", "  ", new BigDecimal("199.00"), null);

        verify(pagamentos, never()).save(any(PagamentoAssinatura.class));
    }

    @Test
    void assinaturaDesconhecida_naoCriaFaturamentoDoNada() {
        service.processarWebhook("PAYMENT_CONFIRMED", "sub_que_nao_existe", "pay_1", new BigDecimal("199.00"), null);

        verify(pagamentos, never()).save(any(PagamentoAssinatura.class));
    }
}
