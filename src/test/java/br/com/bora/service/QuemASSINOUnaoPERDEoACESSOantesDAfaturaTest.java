package br.com.bora.service;

import br.com.bora.entity.*;
import br.com.bora.repository.AssinaturaRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PagamentoAssinaturaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Quem ASSINOU não pode perder o acesso antes de a fatura dele vencer.
 *
 * <h2>O defeito</h2>
 * <p>Achado na varredura de 18/11/2026 por dois agentes, independentemente, e <b>reconferido por
 * mim no código em 19/11</b>. São duas contagens de sete dias que ninguém juntava:</p>
 *
 * <ul>
 *   <li>o cadastro grava {@code loja.acessoAte = agora + 7} — contando do <b>cadastro</b>
 *       ({@code SignupController:174});</li>
 *   <li>o {@code assinar} cria a primeira fatura vencendo em {@code hoje + 7} — contando do
 *       <b>clique</b> ({@code AssinaturaService:169}) — e <b>não mexia em {@code acessoAte}</b>.</li>
 * </ul>
 *
 * <p>Quem se cadastra no dia 1 e assina no dia 5 ficava com acesso até o dia 8 e fatura no dia 12:
 * <b>quatro dias sem o sistema, sem estar devendo nada</b>. Com o corte por falta de pagamento
 * ligado, o corte cai exatamente sobre quem tentou pagar.</p>
 *
 * <h2>As duas guardas que o conserto não pode atropelar</h2>
 * <p>Estender acesso é mexer em dinheiro, e este arquivo já tem história: o acidente da Zirá
 * (15/10/2026) foi um prazo sendo <b>encurtado</b> por cima de uma decisão humana. Então os dois
 * controles abaixo são parte do conserto, não enfeite: <b>nunca encurtar</b>, e <b>nunca tocar</b>
 * em acesso aberto por {@code PAGAMENTO_MANUAL} ou {@code LIBERACAO}.</p>
 */
class QuemASSINOUnaoPERDEoACESSOantesDAfaturaTest {

    private static final Long LOJA = 18L;
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    private AssinaturaRepository repo;
    private LojaRepository lojas;
    private AsaasClient asaas;
    private AssinaturaService service;
    private Loja loja;

    @BeforeEach
    void montar() {
        repo = mock(AssinaturaRepository.class);
        asaas = mock(AsaasClient.class);
        lojas = mock(LojaRepository.class);
        UsuarioRepository usuarios = mock(UsuarioRepository.class);
        AuthContext ctx = mock(AuthContext.class);

        service = new AssinaturaService(repo, lojas, usuarios, asaas, ctx,
                mock(PagamentoAssinaturaRepository.class), 10);

        when(ctx.lojaId()).thenReturn(LOJA);

        loja = new Loja();
        loja.setId(LOJA);
        loja.setNome("Zira Acaiteria");
        loja.setPlano(Plano.UNICO);
        loja.setPrecoMensal(new BigDecimal("199.00"));
        loja.setDocumento("12345678000190");   // sem documento o assinar recusa antes de chegar aqui

        when(lojas.findById(LOJA)).thenReturn(Optional.of(loja));
        when(lojas.save(any(Loja.class))).thenAnswer(i -> i.getArgument(0));

        Usuario dono = new Usuario();
        dono.setEmail("dono@zira.com");
        when(usuarios.donosDaLoja(LOJA)).thenReturn(List.of(dono));

        when(asaas.configurado()).thenReturn(true);
        when(asaas.criarCliente(anyString(), any(), any())).thenReturn("cus_1");
        when(asaas.criarAssinatura(anyString(), anyDouble(), anyString(), anyString()))
                .thenReturn(Map.of("id", "sub_NOVA"));
        when(repo.findByLojaId(LOJA)).thenReturn(Optional.empty());   // primeira assinatura
        when(repo.save(any(Assinatura.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** A loja como o cadastro a deixa: cortesia contada do CADASTRO, nao do clique. */
    private void cadastradaHa(int dias) {
        loja.acessoAte = OffsetDateTime.now().minusDays(dias).plusDays(7);
        loja.acessoMotivo = MotivoDoAcesso.CORTESIA;
    }

    /** O vencimento que foi mandado ao Asaas. */
    private LocalDate vencimentoDaFatura() {
        var cap = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(asaas).criarAssinatura(anyString(), anyDouble(), anyString(), cap.capture());
        return LocalDate.parse(cap.getValue());
    }

    /** O acesso como ficou gravado na loja. */
    private OffsetDateTime acessoGravado() {
        var cap = org.mockito.ArgumentCaptor.forClass(Loja.class);
        verify(lojas, atLeastOnce()).save(cap.capture());
        return cap.getValue().acessoAte;
    }

    // ----------------------------------------------------------------- a premissa

    /**
     * A PREMISSA: o defeito existe de verdade — a fatura nasce DEPOIS do fim da cortesia.
     *
     * <p>Sem esta medição, o teste principal poderia passar porque as duas datas coincidem por
     * acaso, e não porque o conserto funciona.</p>
     */
    @Test
    void aPREMISSAaFATURAvenceDEPOISdoFIMdaCORTESIA() {
        cadastradaHa(5);
        OffsetDateTime cortesiaTerminaEm = loja.acessoAte;

        service.assinar("12345678000190");

        LocalDate vence = vencimentoDaFatura();
        assertTrue(vence.isAfter(cortesiaTerminaEm.atZoneSameInstant(ZONA).toLocalDate()),
                "a fatura deveria vencer DEPOIS do fim da cortesia (cortesia ate "
                        + cortesiaTerminaEm + ", fatura em " + vence + "). Sem essa diferenca este "
                        + "arquivo nao esta medindo o defeito que diz medir.");
    }

    // ----------------------------------------------------------------- o que importa

    /** O acesso é estendido para cobrir o vencimento da primeira fatura. */
    @Test
    void oACESSOehESTENDIDOateOvencimentoDAfatura() {
        cadastradaHa(5);

        service.assinar("12345678000190");

        LocalDate vence = vencimentoDaFatura();
        OffsetDateTime acesso = acessoGravado();
        assertNotNull(acesso, "a loja ficou sem prazo gravado");
        assertFalse(acesso.atZoneSameInstant(ZONA).toLocalDate().isBefore(vence),
                "o acesso termina em " + acesso + " e a fatura vence em " + vence + ": o lojista "
                        + "perde o sistema ANTES de a fatura dele vencer, sem estar devendo nada.");
    }

    /** E ele cobre o dia INTEIRO do vencimento — cortar às 00:00 é cortar no dia do vencimento. */
    @Test
    void oACESSOcobreOdiaINTEIROdoVENCIMENTO() {
        cadastradaHa(5);

        service.assinar("12345678000190");

        LocalDate vence = vencimentoDaFatura();
        OffsetDateTime acesso = acessoGravado();
        assertEquals(vence, acesso.atZoneSameInstant(ZONA).toLocalDate(),
                "o acesso tem de terminar NO dia do vencimento, nao antes");
        assertTrue(acesso.atZoneSameInstant(ZONA).getHour() >= 23,
                "tem de ser o FIM do dia do vencimento (deu " + acesso + "): fatura que vence no dia "
                        + "12 precisa deixar o lojista trabalhar o dia 12 inteiro");
    }

    /** E o motivo passa a ser ASSINATURA: quem manda no prazo agora é a cobrança. */
    @Test
    void oMOTIVOpassaAserASSINATURA() {
        cadastradaHa(5);

        service.assinar("12345678000190");

        var cap = org.mockito.ArgumentCaptor.forClass(Loja.class);
        verify(lojas, atLeastOnce()).save(cap.capture());
        assertEquals(MotivoDoAcesso.ASSINATURA, cap.getValue().acessoMotivo);
    }

    // ----------------------------------------------------------------- os controles

    /**
     * O CONTROLE 1: NUNCA ENCURTA. Loja com prazo mais longe que a fatura fica como estava.
     *
     * <p>Foi exatamente encurtar por cima que causou o acidente da Zirá.</p>
     */
    @Test
    void oCONTROLEnuncaENCURTAumPRAZOmaisLONGE() {
        loja.acessoAte = OffsetDateTime.now().plusDays(90);
        loja.acessoMotivo = MotivoDoAcesso.CORTESIA;
        OffsetDateTime comoEstava = loja.acessoAte;

        service.assinar("12345678000190");

        verify(lojas, never()).save(argThat(l ->
                l.acessoAte != null && l.acessoAte.isBefore(comoEstava)));
    }

    /** O CONTROLE 2: acesso aberto por PAGAMENTO_MANUAL nao e tocado. */
    @Test
    void oCONTROLEnaoTOCAacessoDEpagamentoMANUAL() {
        loja.acessoAte = OffsetDateTime.now().plusDays(2);   // termina ANTES da fatura, de proposito
        loja.acessoMotivo = MotivoDoAcesso.PAGAMENTO_MANUAL;

        service.assinar("12345678000190");

        verify(lojas, never()).save(argThat(l -> l.acessoMotivo == MotivoDoAcesso.ASSINATURA));
    }

    /** O CONTROLE 3: o mesmo para LIBERACAO, que e decisao do dono. */
    @Test
    void oCONTROLEnaoTOCAacessoDEliberacaoDOdono() {
        loja.acessoAte = OffsetDateTime.now().plusDays(2);
        loja.acessoMotivo = MotivoDoAcesso.LIBERACAO;

        service.assinar("12345678000190");

        verify(lojas, never()).save(argThat(l -> l.acessoMotivo == MotivoDoAcesso.ASSINATURA));
    }

    /** O CONTROLE 4: acesso SEM data de fim e acesso sem limite — nao ha o que estender. */
    @Test
    void oCONTROLEacessoSEMdataDEfimNAOganhaUMA() {
        loja.acessoAte = null;
        loja.acessoMotivo = MotivoDoAcesso.ASSINATURA;

        service.assinar("12345678000190");

        verify(lojas, never()).save(argThat(l -> l.acessoAte != null));
    }
}
