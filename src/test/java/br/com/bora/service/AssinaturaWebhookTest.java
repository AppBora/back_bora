package br.com.bora.service;

import br.com.bora.entity.Assinatura;
import br.com.bora.entity.Loja;
import br.com.bora.entity.StatusAssinatura;
import br.com.bora.repository.AssinaturaRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * O webhook do Asaas é quem liga e desliga a loja conforme o pagamento da mensalidade. Um erro aqui
 * trava um lojista que pagou (perde cliente) ou mantém de graça quem não pagou (perde receita) — e
 * até 29/09/2026 não havia teste nenhum.
 *
 * <p>Um dos testes documenta, de propósito, um comportamento que HOJE é assim e o dono precisa
 * decidir se muda: fatura vencida só marca INADIMPLENTE e <b>não</b> corta o acesso.</p>
 */
class AssinaturaWebhookTest {

    private AssinaturaRepository repo;
    private LojaRepository lojas;
    private AssinaturaService service;

    @BeforeEach
    void montar() {
        repo = mock(AssinaturaRepository.class);
        lojas = mock(LojaRepository.class);
        service = new AssinaturaService(repo, lojas, mock(UsuarioRepository.class),
                mock(AsaasClient.class), mock(AuthContext.class));
        when(repo.save(any(Assinatura.class))).thenAnswer(inv -> inv.getArgument(0));
        when(lojas.save(any(Loja.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Assinatura assinatura(StatusAssinatura status) {
        Assinatura a = new Assinatura();
        a.setLojaId(18L);
        a.setStatus(status);
        a.setAsaasSubscriptionId("sub_0001");
        when(repo.findByAsaasSubscriptionId("sub_0001")).thenReturn(Optional.of(a));
        return a;
    }

    private Loja loja(boolean ativa, boolean suspensaPelaPlataforma) {
        Loja l = new Loja();
        l.id = 18L;
        l.ativo = ativa;
        l.suspensaPelaPlataforma = suspensaPelaPlataforma;
        when(lojas.findById(18L)).thenReturn(Optional.of(l));
        return l;
    }

    @Test
    void pagamentoConfirmadoAtivaALoja() {
        Assinatura a = assinatura(StatusAssinatura.PENDENTE);
        Loja l = loja(false, false);

        service.processarWebhook("PAYMENT_CONFIRMED", "sub_0001");

        assertEquals(StatusAssinatura.ATIVA, a.getStatus());
        assertTrue(l.ativo, "loja tem que voltar ao ar depois do pagamento");
        verify(repo).save(a);
    }

    @Test
    void assinaturaCanceladaDerrubaALoja() {
        Assinatura a = assinatura(StatusAssinatura.ATIVA);
        Loja l = loja(true, false);

        service.processarWebhook("SUBSCRIPTION_DELETED", "sub_0001");

        assertEquals(StatusAssinatura.CANCELADA, a.getStatus());
        assertFalse(l.ativo);
    }

    @Test
    void pagamentoNaoLevantaSuspensaoDecididaPelaPlataforma() {
        assinatura(StatusAssinatura.INADIMPLENTE);
        Loja l = loja(false, true); // a plataforma suspendeu este cliente

        service.processarWebhook("PAYMENT_RECEIVED", "sub_0001");

        assertFalse(l.ativo, "quem suspendeu foi a plataforma; só ela reativa");
    }

    @Test
    void faturaVencidaHojeSoMarcaInadimplenteENaoCortaOAcesso() {
        Assinatura a = assinatura(StatusAssinatura.ATIVA);
        Loja l = loja(true, false);

        service.processarWebhook("PAYMENT_OVERDUE", "sub_0001");

        assertEquals(StatusAssinatura.INADIMPLENTE, a.getStatus());
        // Comportamento ATUAL, não ideal: o lojista segue usando o sistema devendo. Se o dono decidir
        // bloquear por atraso, é aqui que muda — e este teste tem que ser atualizado junto.
        assertTrue(l.ativo, "hoje o atraso nao bloqueia; mudar isso e decisao comercial");
    }

    @Test
    void eventoDeAssinaturaDesconhecidaNaoMexeEmNada() {
        when(repo.findByAsaasSubscriptionId("sub_de_outro")).thenReturn(Optional.empty());

        service.processarWebhook("PAYMENT_CONFIRMED", "sub_de_outro");

        verify(repo, never()).save(any(Assinatura.class));
        verify(lojas, never()).save(any(Loja.class));
    }

    @Test
    void webhookSemDadoNaoFazNada() {
        service.processarWebhook(null, "sub_0001");
        service.processarWebhook("PAYMENT_CONFIRMED", null);

        verifyNoInteractions(repo);
        verifyNoInteractions(lojas);
    }
}
