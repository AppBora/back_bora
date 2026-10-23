package br.com.bora.service;

import br.com.bora.entity.Assinatura;
import br.com.bora.entity.Loja;
import br.com.bora.entity.MotivoDoAcesso;
import br.com.bora.entity.StatusAssinatura;
import br.com.bora.repository.AssinaturaRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * O acidente da Zirá, de 15/10/2026, e a parte dele que continuou aberta.
 *
 * <p>Ela pagou R$ 398. O registro manual deixou o acesso <b>sem data de fim</b> ({@code acessoAte}
 * nulo), que é o que "está pago" significa aqui. Depois o Asaas mandou {@code SUBSCRIPTION_DELETED}
 * e esse ramo gravou "hoje + carência": uma loja que pagou passou a vencer em 26/10, e quem lesse o
 * banco não tinha como saber por quê.</p>
 *
 * <p>A guarda que foi posta na v139 compara datas — {@code atual.isAfter(comCarencia)} — e por isso
 * só protege prazo <b>explícito e maior</b>. Acesso nulo, que é exatamente o que o pagamento
 * deixa, não entra na comparação e continuava sendo encurtado. Era o mesmo acidente, de novo.</p>
 *
 * <p>Nulo sozinho não resolve: ele também é o estado de quem paga pelo Asaas, e para esse o prazo
 * na hora do cancelamento é <b>certo</b> — senão a loja usaria de graça para sempre. O que faltava
 * era o sistema saber <b>por que</b> o acesso está aberto. É isso que o {@link MotivoDoAcesso} diz.</p>
 */
class QuemPagouNaoPerdeAcessoTest {

    private AssinaturaRepository repo;
    private LojaRepository lojas;
    private AssinaturaService service;

    @BeforeEach
    void montar() {
        repo = mock(AssinaturaRepository.class);
        lojas = mock(LojaRepository.class);
        service = new AssinaturaService(repo, lojas, mock(UsuarioRepository.class),
                mock(AsaasClient.class), mock(AuthContext.class),
                mock(br.com.bora.repository.PagamentoAssinaturaRepository.class), 10);
        when(repo.save(any(Assinatura.class))).thenAnswer(inv -> inv.getArgument(0));
        when(lojas.save(any(Loja.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Assinatura assinatura(StatusAssinatura status) {
        Assinatura a = new Assinatura();
        a.setLojaId(18L);
        a.setStatus(status);
        a.setAsaasSubscriptionId("sub_0001");
        when(repo.findByAsaasSubscriptionId("sub_0001")).thenReturn(Optional.of(a));
        when(repo.findByLojaId(18L)).thenReturn(Optional.of(a));
        return a;
    }

    private Loja loja(OffsetDateTime acessoAte, MotivoDoAcesso motivo) {
        Loja l = new Loja();
        l.id = 18L;
        l.ativo = true;
        l.acessoAte = acessoAte;
        l.acessoMotivo = motivo;
        when(lojas.findById(18L)).thenReturn(Optional.of(l));
        return l;
    }

    // ------------------------------------------------------------------ o caso da Zirá

    @Test
    void oCasoDaZira_pagouNaMao_eOcancelamentoDoAsaasNAOcortaOacesso() {
        assinatura(StatusAssinatura.ATIVA);
        Loja l = loja(null, MotivoDoAcesso.PAGAMENTO_MANUAL);

        service.processarWebhook("SUBSCRIPTION_DELETED", "sub_0001");

        assertNull(l.acessoAte,
                "quem pagou na mao ficou com prazo de vencimento: e o acidente da Zira de novo");
        assertEquals(MotivoDoAcesso.PAGAMENTO_MANUAL, l.acessoMotivo,
                "o motivo do acesso nao pode ser apagado pelo cancelamento");
    }

    @Test
    void acessoLiberadoPelaPlataforma_tambemNAOeCortado() {
        assinatura(StatusAssinatura.CANCELADA);
        OffsetDateTime ate = OffsetDateTime.now().plusDays(30);
        Loja l = loja(ate, MotivoDoAcesso.LIBERACAO);

        service.processarWebhook("SUBSCRIPTION_DELETED", "sub_0001");

        assertEquals(ate, l.acessoAte, "liberacao manual do dono nao e desfeita pelo Asaas");
    }

    // ------------------------------------------------- o que o prazo na hora do cancelamento serve

    @Test
    void quemPagavaPeloAsaas_ganhaPrazoAoCancelar_emVezDeUsarDeGracaParaSempre() {
        assinatura(StatusAssinatura.ATIVA);
        Loja l = loja(null, MotivoDoAcesso.ASSINATURA);

        service.processarWebhook("SUBSCRIPTION_DELETED", "sub_0001");

        assertNotNull(l.acessoAte, "assinatura cancelada nao pode virar acesso sem fim");
        assertTrue(l.acessoAte.isAfter(OffsetDateTime.now().plusDays(9)),
                "a carencia combinada e de 10 dias");
        assertTrue(l.acessoAte.isBefore(OffsetDateTime.now().plusDays(11)));
    }

    @Test
    void loja_sem_motivo_registrado_segue_a_regra_antiga_e_ganha_a_carencia() {
        // Linhas antigas, de antes da coluna existir. Na duvida, o comportamento continua o de antes:
        // ganha prazo. Quem pagou na mao a partir de agora fica marcado e nao cai aqui.
        assinatura(StatusAssinatura.ATIVA);
        Loja l = loja(null, null);

        service.processarWebhook("SUBSCRIPTION_DELETED", "sub_0001");

        assertNotNull(l.acessoAte);
    }

    @Test
    void prazoExplicitoMaiorContinuaValendo() {
        assinatura(StatusAssinatura.ATIVA);
        OffsetDateTime cortesia = OffsetDateTime.now().plusDays(45);
        Loja l = loja(cortesia, MotivoDoAcesso.CORTESIA);

        service.processarWebhook("SUBSCRIPTION_DELETED", "sub_0001");

        assertEquals(cortesia, l.acessoAte, "a guarda da v139 nao pode ter se perdido");
    }

    @Test
    void prazoExplicitoMENORqueAcarencia_eEsticadoAteAcarencia() {
        assinatura(StatusAssinatura.ATIVA);
        Loja l = loja(OffsetDateTime.now().plusDays(2), MotivoDoAcesso.ASSINATURA);

        service.processarWebhook("SUBSCRIPTION_DELETED", "sub_0001");

        assertTrue(l.acessoAte.isAfter(OffsetDateTime.now().plusDays(9)),
                "quem tinha menos que a carencia ganha a carencia: a regra e nunca ENCURTAR");
    }

    // ------------------------------------------------------- a fatura vencida segue a mesma regra

    @Test
    void faturaVencida_tambemNAOcortaQuemPagouNaMao() {
        assinatura(StatusAssinatura.ATIVA);
        Loja l = loja(null, MotivoDoAcesso.PAGAMENTO_MANUAL);

        service.processarWebhook("PAYMENT_OVERDUE", "sub_0001");

        assertNull(l.acessoAte,
                "o aviso de atraso chegando depois do pagamento na mao nao pode encurtar nada");
    }

    @Test
    void faturaVencida_deQuemPagaPeloAsaas_ganhaAcarencia() {
        assinatura(StatusAssinatura.ATIVA);
        Loja l = loja(null, MotivoDoAcesso.ASSINATURA);

        service.processarWebhook("PAYMENT_OVERDUE", "sub_0001");

        assertNotNull(l.acessoAte);
        assertTrue(l.acessoAte.isAfter(OffsetDateTime.now().plusDays(9)));
    }

    // ------------------------------------------------------------- pagar de volta limpa o prazo

    // ------------------------------------------------------- devolucao de cobranca (estorno)

    @Test
    void estornoDeOUTRAcobranca_naoPoeData_emQuemPagouNaMao() {
        // A devolucao e de dinheiro que voltou ao cliente numa cobranca do Asaas. Quem pagou FORA
        // dele nao teve nada devolvido -- nao pode ganhar data de vencimento por tabela.
        assinatura(StatusAssinatura.ATIVA);
        Loja l = loja(null, MotivoDoAcesso.PAGAMENTO_MANUAL);

        service.processarWebhook("PAYMENT_REFUNDED", "sub_0001");

        assertNull(l.acessoAte, "quem pagou na mao ganhou prazo por causa de um estorno alheio");
    }

    @Test
    void pagamentoPeloAsaas_devolveAcessoSemFim_eMarcaOmotivo() {
        assinatura(StatusAssinatura.INADIMPLENTE);
        Loja l = loja(OffsetDateTime.now().plusDays(3), MotivoDoAcesso.ASSINATURA);

        service.processarWebhook("PAYMENT_RECEIVED", "sub_0001");

        assertNull(l.acessoAte, "pagando, o acesso volta a nao ter data de fim");
        assertEquals(MotivoDoAcesso.ASSINATURA, l.acessoMotivo);
    }
}
