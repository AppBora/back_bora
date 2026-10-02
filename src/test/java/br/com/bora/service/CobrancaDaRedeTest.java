package br.com.bora.service;

import br.com.bora.entity.Assinatura;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Papel;
import br.com.bora.entity.Usuario;
import br.com.bora.repository.AssinaturaRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PagamentoAssinaturaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Numa rede, a cobrança da 2ª loja em diante nascia sem e-mail.
 *
 * <p>O dono de várias lojas tem uma conta só, presa pela coluna {@code loja_id} à primeira loja e
 * ligada às outras pelos vínculos. Quem procurava o dono olhando só a coluna não achava ninguém nas
 * demais, e o cliente era criado no Asaas com e-mail vazio: a fatura não chegava a lugar nenhum. É o
 * caso das três lojas da Zirá, onde só a primeira tem o dono na coluna.</p>
 */
class CobrancaDaRedeTest {

    private AssinaturaRepository repo;
    private LojaRepository lojas;
    private UsuarioRepository usuarios;
    private AsaasClient asaas;
    private AuthContext ctx;
    private AssinaturaService service;

    @BeforeEach
    void montar() {
        repo = mock(AssinaturaRepository.class);
        lojas = mock(LojaRepository.class);
        usuarios = mock(UsuarioRepository.class);
        asaas = mock(AsaasClient.class);
        ctx = mock(AuthContext.class);
        service = new AssinaturaService(repo, lojas, usuarios, asaas, ctx,
                mock(PagamentoAssinaturaRepository.class), 10);

        when(ctx.lojaId()).thenReturn(19L); // "Zirá Centro": a segunda loja da rede
        Loja l = new Loja();
        l.id = 19L;
        l.nome = "Zirá Centro";
        when(lojas.findById(19L)).thenReturn(Optional.of(l));
        when(repo.findByLojaId(19L)).thenReturn(Optional.empty());
        when(repo.save(any(Assinatura.class))).thenAnswer(i -> i.getArgument(0));
        when(asaas.configurado()).thenReturn(true);
        when(asaas.criarCliente(anyString(), any(), anyString())).thenReturn("cus_novo");
        when(asaas.criarAssinatura(anyString(), anyDouble(), anyString(), anyString()))
                .thenReturn(java.util.Map.of("id", "sub_novo"));

        Usuario dona = new Usuario();
        dona.setId(32L);
        dona.setEmail("dona@zira.local");
        dona.setPapel(Papel.ADMINISTRADOR_LOJA);
        dona.setAtivo(true);
        dona.setLojaId(18L); // a conta dela nasceu na PRIMEIRA loja
        when(usuarios.donosDaLoja(19L)).thenReturn(List.of(dona));
        when(usuarios.findByLojaId(19L)).thenReturn(List.of()); // pela coluna, ninguem mora aqui
    }

    @Test
    void aCobrancaDaSegundaLojaVaiParaOEmailDoDono() {
        service.assinar("53953786000128");

        ArgumentCaptor<String> email = ArgumentCaptor.forClass(String.class);
        verify(asaas).criarCliente(anyString(), email.capture(), anyString());
        assertEquals("dona@zira.local", email.getValue(),
                "sem isto a fatura da 2a loja da rede nao chega a ninguem");
    }

    @Test
    void naoVoltaAOlharSoParaAColunaLojaId() {
        service.assinar("53953786000128");

        verify(usuarios).donosDaLoja(19L);
        verify(usuarios, never()).findByLojaId(19L);
    }

    @Test
    void semDonoAtivo_aCobrancaAindaNasceMasSemEmail() {
        when(usuarios.donosDaLoja(19L)).thenReturn(List.of());

        Assinatura a = service.assinar("53953786000128");

        ArgumentCaptor<String> email = ArgumentCaptor.forClass(String.class);
        verify(asaas).criarCliente(anyString(), email.capture(), anyString());
        assertNull(email.getValue());
        assertNotNull(a.getAsaasSubscriptionId(), "a assinatura nao pode deixar de existir por isso");
    }

    @Test
    void oValorCobradoInclui_oModuloIaQuandoLigado() {
        Loja comIa = lojas.findById(19L).orElseThrow();
        comIa.moduloIa = true;

        service.assinar("53953786000128");

        ArgumentCaptor<Double> valor = ArgumentCaptor.forClass(Double.class);
        verify(asaas).criarAssinatura(anyString(), valor.capture(), anyString(), anyString());
        assertEquals(298.00, valor.getValue(), 0.001,
                "modulo ligado por engano vira R$ 99/mes cobrados do cliente sem ele saber");
    }
}
