package br.com.bora.service;

import br.com.bora.entity.Assinatura;
import br.com.bora.entity.Loja;
import br.com.bora.repository.AssinaturaRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PagamentoAssinaturaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Ligar o Módulo IA tem que chegar na cobrança — e, quando não chega, o administrador precisa saber
 * por quê.
 *
 * <p>"Não atualizou" juntava dois casos muito diferentes: a loja que nem assinou ainda (normal) e a
 * loja que assinou mas ficou com a cobrança antiga (o cliente usando R$ 99/mês de graça). A tela
 * mostrava a mesma frase para os dois, então o segundo passava despercebido.</p>
 */
class MotivoDaCobrancaTest {

    private AssinaturaRepository repo;
    private LojaRepository lojas;
    private AsaasClient asaas;
    private AssinaturaService service;
    private Loja loja;

    @BeforeEach
    void montar() {
        repo = mock(AssinaturaRepository.class);
        lojas = mock(LojaRepository.class);
        asaas = mock(AsaasClient.class);
        service = new AssinaturaService(repo, lojas, mock(UsuarioRepository.class), asaas,
                mock(AuthContext.class), mock(PagamentoAssinaturaRepository.class), 10);
        loja = new Loja();
        loja.id = 18L;
        loja.nome = "Zirá Açaíteria";
        loja.moduloIa = true;
        when(lojas.findById(18L)).thenReturn(Optional.of(loja));
        when(repo.save(any(Assinatura.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Assinatura assinatura(String subscriptionId) {
        Assinatura a = new Assinatura();
        a.setLojaId(18L);
        a.setAsaasSubscriptionId(subscriptionId);
        when(repo.findByLojaId(18L)).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void comAssinaturaNoAsaas_aCobrancaMudaDeVerdade() {
        assinatura("sub_1");
        when(asaas.configurado()).thenReturn(true);

        assertEquals("ATUALIZADA", service.sincronizarValorComMotivo(18L));
        verify(asaas).atualizarAssinatura(eq("sub_1"), eq(298.00), contains("Modulo IA"));
    }

    @Test
    void lojaQueAindaNaoAssinou_eCasoNormal() {
        when(repo.findByLojaId(18L)).thenReturn(Optional.empty());

        assertEquals("SEM_ASSINATURA", service.sincronizarValorComMotivo(18L));
        verify(asaas, never()).atualizarAssinatura(anyString(), anyDouble(), anyString());
    }

    @Test
    void assinouMasSemCobrancaNoAsaas_naoPodeSerConfundidoComOCasoNormal() {
        Assinatura a = assinatura(null);
        when(asaas.configurado()).thenReturn(true);

        assertEquals("SEM_ID_NO_ASAAS", service.sincronizarValorComMotivo(18L));
        // CORRIGIDO EM 03/10. A versao anterior deste teste exigia o contrario -- que o valor subisse
        // aqui mesmo sem o Asaas aceitar. Estava errado, e era meu: gravar R$ 298 enquanto o Asaas
        // segue cobrando R$ 199 e exatamente a divergencia que este metodo existe para evitar, e a
        // tela de planos passava a mostrar um valor que ninguem cobra. Sem cobranca la, nao grava aqui.
        assertNull(a.getValor(), "nosso numero so muda quando o Asaas aceita");
    }

    @Test
    void cobrancaDesligada_avisaEmVezDeFingirQueDeuCerto() {
        assinatura("sub_1");
        when(asaas.configurado()).thenReturn(false);

        assertEquals("ASAAS_NAO_CONFIGURADO", service.sincronizarValorComMotivo(18L));
        verify(asaas, never()).atualizarAssinatura(anyString(), anyDouble(), anyString());
    }

    @Test
    void desligarOModulo_derrubaACobrancaDeVolta() {
        loja.moduloIa = false;
        assinatura("sub_1");
        when(asaas.configurado()).thenReturn(true);

        assertEquals("ATUALIZADA", service.sincronizarValorComMotivo(18L));
        verify(asaas).atualizarAssinatura(eq("sub_1"), eq(199.00), org.mockito.ArgumentMatchers.argThat(
                d -> !d.contains("Modulo IA")));
    }
}
