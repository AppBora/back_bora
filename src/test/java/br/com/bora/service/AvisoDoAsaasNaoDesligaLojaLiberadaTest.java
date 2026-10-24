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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * O acidente da Zirá por OUTRA porta — achado pelo QA em 24/10/2026.
 *
 * <h2>O que já estava protegido</h2>
 * <p>{@code prazoSemEncurtar} impede que um aviso do Asaas encurte o {@code acessoAte} de uma loja
 * cujo acesso foi aberto por {@code PAGAMENTO_MANUAL} ou {@code LIBERACAO}. Entrou dinheiro, ou o
 * dono decidiu: aviso de gateway não desfaz. Essa guarda funciona.</p>
 *
 * <h2>O que NÃO estava</h2>
 * <p>{@code ativo} é <b>outra chave</b>, e o ramo {@code SUBSCRIPTION_DELETED} chamava
 * {@code ativarLoja(loja, false)} sem olhar nada. Resultado medido: loja com {@code LIBERACAO} até
 * dezembro ficava com a <b>data intacta</b> e mesmo assim <b>sumia do aplicativo do cliente</b> —
 * {@code GET /public/loja/{id}/cardapio} respondendo 404 —, enquanto o painel continuava abrindo
 * para o lojista. Nenhuma tela explicava por quê.</p>
 *
 * <p>É o mesmo acidente: o Asaas cancela a assinatura de quem já pagou por fora, e a decisão do
 * dono é desfeita em silêncio. Só que pela chave que ninguém estava olhando.</p>
 */
class AvisoDoAsaasNaoDesligaLojaLiberadaTest {

    private static final Long LOJA = 77L;

    private AssinaturaRepository repo;
    private LojaRepository lojas;
    private AssinaturaService service;
    private Loja loja;

    @BeforeEach
    void montar() {
        repo = mock(AssinaturaRepository.class);
        lojas = mock(LojaRepository.class);
        service = new AssinaturaService(repo, lojas, mock(UsuarioRepository.class),
                mock(AsaasClient.class), mock(AuthContext.class),
                mock(br.com.bora.repository.PagamentoAssinaturaRepository.class), 10);
        when(repo.save(any(Assinatura.class))).thenAnswer(inv -> inv.getArgument(0));
        when(lojas.save(any(Loja.class))).thenAnswer(inv -> inv.getArgument(0));

        loja = new Loja();
        loja.id = LOJA;
        loja.setAtivo(true);
        when(lojas.findById(LOJA)).thenReturn(Optional.of(loja));
    }

    private Assinatura assinaturaAtiva() {
        Assinatura a = new Assinatura();
        a.setLojaId(LOJA);
        a.setStatus(StatusAssinatura.ATIVA);
        a.setAsaasSubscriptionId("sub_1");
        when(repo.findByAsaasSubscriptionId("sub_1")).thenReturn(Optional.of(a));
        when(repo.findByLojaId(LOJA)).thenReturn(Optional.of(a));
        return a;
    }

    private void cancelamentoNoAsaas() {
        service.processarWebhook("SUBSCRIPTION_DELETED", "sub_1", null, null, null, null);
    }

    // ---------------------------------------------------------------- a premissa
    @Test
    void lojaAbertaPorPagamentoManual_NAO_eDesligada() {
        assinaturaAtiva();
        loja.acessoMotivo = MotivoDoAcesso.PAGAMENTO_MANUAL;

        cancelamentoNoAsaas();

        assertTrue(loja.getAtivo(),
                "a loja de quem PAGOU foi desligada por um aviso do Asaas — some do aplicativo do "
                        + "cliente com a data de acesso intacta, e nada na tela explica");
    }

    @Test
    void lojaAbertaPorLiberacaoDoDono_NAO_eDesligada() {
        assinaturaAtiva();
        loja.acessoMotivo = MotivoDoAcesso.LIBERACAO;

        cancelamentoNoAsaas();

        assertTrue(loja.getAtivo(),
                "o dono liberou o acesso de propósito e o aviso do Asaas desfez a decisão dele");
    }

    // ---------------------------------------------------------------- o que NÃO pode mudar
    @Test
    void lojaQuePagavaPeloAsaas_CONTINUA_sendoDesligada() {
        // Sem esta metade, "consertar" viraria uso grátis sem fim: quem pagava pelo gateway e
        // cancelou tem de sair do ar quando a carência acabar.
        assinaturaAtiva();
        loja.acessoMotivo = MotivoDoAcesso.ASSINATURA;

        cancelamentoNoAsaas();

        assertFalse(loja.getAtivo(),
                "assinatura cancelada no Asaas tem de tirar do ar quem dependia dela");
    }

    @Test
    void lojaSemMotivoRegistrado_CONTINUA_sendoDesligada() {
        // Linha antiga, de antes de existir o motivo. O padrão seguro é o comportamento de sempre.
        assinaturaAtiva();
        loja.acessoMotivo = null;

        cancelamentoNoAsaas();

        assertFalse(loja.getAtivo(), "loja sem motivo registrado mudou de comportamento");
    }

    @Test
    void aAssinaturaEhMarcadaComoCANCELADA_emQualquerCaso() {
        // O estado da assinatura é um fato do Asaas e não depende da nossa decisão de acesso:
        // confundir as duas coisas foi o que criou o problema original.
        Assinatura a = assinaturaAtiva();
        loja.acessoMotivo = MotivoDoAcesso.PAGAMENTO_MANUAL;

        cancelamentoNoAsaas();

        assertEquals(StatusAssinatura.CANCELADA, a.getStatus(),
                "a assinatura precisa refletir o que aconteceu no Asaas, mesmo com a loja no ar");
    }
}
