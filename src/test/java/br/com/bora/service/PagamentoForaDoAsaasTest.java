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
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Registrar na mão um pagamento que o lojista fez fora do Asaas.
 *
 * <p><b>O problema real que isto resolve (14/10/2026):</b> quem muda o estado da assinatura é o
 * webhook do Asaas. A Zirá pagou direto na conta do dono — fora do Asaas, então sem webhook. O
 * sistema continuou com a assinatura INADIMPLENTE e {@code acesso_ate = 23/10}: ela tinha pagado e
 * ia ser cortada do ar de todo jeito.</p>
 *
 * <p><b>E por que a baixa no Asaas faz parte do conserto:</b> registrar só no nosso banco deixa a
 * cobrança aberta lá. O Asaas reenvia o aviso de atraso, que cai no nosso webhook como
 * {@code PAYMENT_OVERDUE} — marcando INADIMPLENTE de novo e abrindo outra carência de 10 dias. O
 * registro manual seria desfeito pelo próprio Asaas e a loja cortada dez dias depois. Estes testes
 * cobrem os dois lados: a baixa dando certo e a baixa falhando.</p>
 */
class PagamentoForaDoAsaasTest {

    private static final Long LOJA = 18L;
    private static final String ASSINATURA = "sub_cm8016j2p9r674d3";
    private static final String COBRANCA = "pay_aj14zjq8wbcfqhfx";

    private AssinaturaRepository repo;
    private LojaRepository lojas;
    private PagamentoAssinaturaRepository pagamentos;
    private AsaasClient asaas;
    private AssinaturaService service;
    private Assinatura assinatura;
    private Loja loja;

    @BeforeEach
    void montar() {
        repo = mock(AssinaturaRepository.class);
        lojas = mock(LojaRepository.class);
        pagamentos = mock(PagamentoAssinaturaRepository.class);
        asaas = mock(AsaasClient.class);

        assinatura = new Assinatura();
        assinatura.setId(7L);
        assinatura.setLojaId(LOJA);
        assinatura.setValor(new BigDecimal("199.00"));
        assinatura.setStatus(StatusAssinatura.INADIMPLENTE);   // o estado real da Zirá em 14/10
        assinatura.setAsaasSubscriptionId(ASSINATURA);

        loja = new Loja();
        loja.setNome("Zirá Açaíteria");
        loja.setAtivo(true);
        loja.acessoAte = java.time.OffsetDateTime.now().plusDays(9); // a carência correndo

        when(repo.findByLojaId(LOJA)).thenReturn(Optional.of(assinatura));
        when(lojas.findById(LOJA)).thenReturn(Optional.of(loja));
        when(lojas.save(any(Loja.class))).thenAnswer(i -> i.getArgument(0));
        when(pagamentos.existsByAsaasPaymentId(anyString())).thenReturn(false);
        when(pagamentos.findFirstByAsaasPaymentId(anyString())).thenReturn(Optional.empty());
        when(pagamentos.save(any(PagamentoAssinatura.class))).thenAnswer(i -> i.getArgument(0));
        when(asaas.configurado()).thenReturn(true);
        when(asaas.cobrancaAbertaDaAssinatura(ASSINATURA)).thenReturn(COBRANCA);
        when(asaas.receberEmDinheiro(eq(COBRANCA), anyDouble(), anyString())).thenReturn(true);

        service = new AssinaturaService(repo, lojas,
                mock(br.com.bora.repository.UsuarioRepository.class), asaas,
                mock(br.com.bora.security.AuthContext.class), pagamentos, 10);
    }

    private AssinaturaService.PagamentoRegistrado registrar(String valor) {
        return service.registrarPagamentoRecebidoForaDoAsaas(
                LOJA, new BigDecimal(valor), LocalDate.now(), "PIX direto na conta");
    }

    @Test
    void aLojaVoltaATerAcessoSemPrazo() {
        registrar("199.00");

        assertEquals(StatusAssinatura.ATIVA, assinatura.getStatus());
        assertNull(loja.acessoAte, "pagando, o acesso nao tem data de fim — era isto que ia cortar a loja");
        assertTrue(loja.getAtivo());
    }

    @Test
    void aCobrancaEhBAIXADANoAsaas_senaoOAvisoDeAtrasoDesfazIsto() {
        var r = registrar("199.00");

        verify(asaas).receberEmDinheiro(COBRANCA, 199.00, LocalDate.now().toString());
        assertTrue(r.baixadoNoAsaas());
        assertEquals(COBRANCA, r.cobranca());
    }

    @Test
    void oPagamentoEntraNoFaturamentoComOIdDaCobrancaDeVerdade() {
        registrar("199.00");

        ArgumentCaptor<PagamentoAssinatura> c = ArgumentCaptor.forClass(PagamentoAssinatura.class);
        verify(pagamentos, atLeastOnce()).save(c.capture());
        PagamentoAssinatura p = c.getAllValues().get(0);
        assertEquals(COBRANCA, p.asaasPaymentId,
                "com o id real, o webhook PAYMENT_RECEIVED que vem depois nao duplica o registro");
        assertEquals(0, p.valor.compareTo(new BigDecimal("199.00")));
        assertEquals(LOJA, p.lojaId);
    }

    @Test
    void quandoABaixaNoAsaasFALHA_oRegistroEhFeitoEOAvisoEhEXPLICITO() {
        when(asaas.receberEmDinheiro(anyString(), anyDouble(), anyString())).thenReturn(false);

        var r = registrar("199.00");

        // A loja fica no ar, que e o que importa para ela.
        assertEquals(StatusAssinatura.ATIVA, assinatura.getStatus());
        assertNull(loja.acessoAte);
        // Mas o dono tem que saber que a cobranca continua aberta, senao o Asaas desfaz isto em 10 dias.
        assertFalse(r.baixadoNoAsaas());
        assertTrue(r.recado().contains("continua aberta"), r.recado());
        assertTrue(r.recado().contains(COBRANCA), r.recado());
    }

    @Test
    void semCobrancaAbertaNoAsaas_registraEDizQueNaoHaviaOQueBaixar() {
        when(asaas.cobrancaAbertaDaAssinatura(ASSINATURA)).thenReturn(null);

        var r = registrar("199.00");

        verify(asaas, never()).receberEmDinheiro(anyString(), anyDouble(), anyString());
        assertFalse(r.baixadoNoAsaas());
        assertTrue(r.recado().contains("Não havia cobrança aberta"), r.recado());
        // O id sintetico mantem o registro rastreavel e idempotente por dia.
        ArgumentCaptor<PagamentoAssinatura> c = ArgumentCaptor.forClass(PagamentoAssinatura.class);
        verify(pagamentos).save(c.capture());
        assertTrue(c.getValue().asaasPaymentId.startsWith("manual:" + LOJA + ":"), c.getValue().asaasPaymentId);
    }

    @Test
    void comAsaasDesligado_aindaRegistra() {
        when(asaas.configurado()).thenReturn(false);

        var r = registrar("199.00");

        verify(asaas, never()).cobrancaAbertaDaAssinatura(anyString());
        assertEquals(StatusAssinatura.ATIVA, assinatura.getStatus());
        assertFalse(r.baixadoNoAsaas());
    }

    @Test
    void registrarDuasVezesNoMESMODIANaoDuplicaOPagamento() {
        // Idempotencia: o `existsByAsaasPaymentId` do registrarPagamento e a guarda. Aqui simulamos o
        // segundo clique, com o registro ja existindo.
        when(asaas.cobrancaAbertaDaAssinatura(ASSINATURA)).thenReturn(null);
        when(pagamentos.existsByAsaasPaymentId(startsWith("manual:"))).thenReturn(true);

        registrar("199.00");

        verify(pagamentos, never()).save(any(PagamentoAssinatura.class));
    }

    @Test
    void lojaSemAssinaturaNaoAceitaRegistro() {
        when(repo.findByLojaId(LOJA)).thenReturn(Optional.empty());

        var e = assertThrows(ResponseStatusException.class, () -> registrar("199.00"));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void valorZeradoOuNegativoEhRecusado() {
        assertThrows(ResponseStatusException.class, () -> registrar("0"));
        assertThrows(ResponseStatusException.class, () -> registrar("-10"));
        verify(pagamentos, never()).save(any());
    }

    @Test
    void dataNoFuturoEhRecusada() {
        var e = assertThrows(ResponseStatusException.class, () -> service.registrarPagamentoRecebidoForaDoAsaas(
                LOJA, new BigDecimal("199.00"), LocalDate.now().plusDays(1), null));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("futuro"), e.getReason());
    }

    @Test
    void semDataInformada_valeHoje() {
        service.registrarPagamentoRecebidoForaDoAsaas(LOJA, new BigDecimal("199.00"), null, null);

        verify(asaas).receberEmDinheiro(eq(COBRANCA), anyDouble(), eq(LocalDate.now().toString()));
    }

    @Test
    void pagamentoNaoLevantaSuspensaoADMINISTRATIVA() {
        // Quem suspendeu foi a plataforma; pagar a mensalidade nao desfaz essa decisao. A guarda ja
        // existia no ativarLoja e vale para o registro manual tambem.
        loja.setSuspensaPelaPlataforma(true);
        loja.setAtivo(false);

        registrar("199.00");

        assertFalse(loja.getAtivo(), "loja suspensa pela plataforma continua suspensa");
        assertEquals(StatusAssinatura.ATIVA, assinatura.getStatus(), "mas a assinatura esta paga");
    }
}
