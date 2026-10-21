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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Estorno e chargeback têm que mexer no acesso — e cada evento do Asaas tem a sua resposta.
 *
 * <h2>O defeito (medido em 20/10/2026)</h2>
 * <p>Os eventos de estorno eram tratados antes de tudo e <b>saíam da função</b>. A assinatura
 * continuava ATIVA e {@code loja.acessoAte} continuava NULO — e o bloqueio só olha essa data
 * ({@code Loja.acessoVencido()}, lido pelo {@code BloqueioPorAssinaturaFilter}, pelo
 * {@code PublicController} e pela {@code RegraDeAcesso}). Pagar, usar, pedir estorno, receber o
 * dinheiro de volta e <b>ficar com acesso sem prazo para sempre</b>. Repetível todo mês.</p>
 *
 * <p>Conferido na produção em 21/10/2026: o corte está <b>LIGADO</b>
 * ({@code BORA_COBRANCA_CORTE_POR_ASSINATURA=true}, confirmado no log da subida da v123) e a carência
 * é de 10 dias. Então o que estes testes exercitam é o que realmente acontece com a loja.</p>
 *
 * <h2>Os oito eventos, e por que não são a mesma coisa</h2>
 * <p>Transcritos de docs.asaas.com/docs/payment-events (conferido em 21/10/2026). O código tratava
 * <b>três</b>, e tratava os três igual. Um diz que o dinheiro foi embora; outro que foi embora em
 * parte; outro que a <b>disputa foi ganha</b> e o dinheiro está voltando. Responder igual aos três é
 * errar em dois.</p>
 */
class EstornoCortaOAcessoTest {

    private AssinaturaRepository repo;
    private LojaRepository lojas;
    private PagamentoAssinaturaRepository pagamentos;
    private AssinaturaService service;

    private static final Long LOJA = 18L;
    private static final String SUB = "sub_0001";
    private static final String PAY = "pay_0009";

    @BeforeEach
    void montar() {
        repo = mock(AssinaturaRepository.class);
        lojas = mock(LojaRepository.class);
        pagamentos = mock(PagamentoAssinaturaRepository.class);
        service = new AssinaturaService(repo, lojas, mock(UsuarioRepository.class),
                mock(AsaasClient.class), mock(AuthContext.class), pagamentos, 10);
        when(repo.save(any(Assinatura.class))).thenAnswer(i -> i.getArgument(0));
        when(lojas.save(any(Loja.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** A loja que está pagando: ativa, sem prazo de acesso (é o que "em dia" significa). */
    private Loja lojaPagando() {
        Loja l = new Loja();
        l.id = LOJA;
        l.ativo = true;
        l.acessoAte = null;
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));
        return l;
    }

    private Assinatura assinaturaAtiva() {
        Assinatura a = new Assinatura();
        a.setLojaId(LOJA);
        a.setStatus(StatusAssinatura.ATIVA);
        a.setAsaasSubscriptionId(SUB);
        when(repo.findByAsaasSubscriptionId(SUB)).thenReturn(Optional.of(a));
        when(repo.findByLojaId(LOJA)).thenReturn(Optional.of(a));
        return a;
    }

    /** A mensalidade que entrou e está no faturamento. */
    private PagamentoAssinatura mensalidadePaga() {
        PagamentoAssinatura pg = new PagamentoAssinatura();
        pg.lojaId = LOJA;
        pg.asaasPaymentId = PAY;
        pg.valor = new BigDecimal("298.00");
        pg.pagoEm = OffsetDateTime.now().minusDays(3);
        when(pagamentos.findByAsaasPaymentId(PAY)).thenReturn(Optional.of(pg));
        return pg;
    }

    private void chega(String evento) {
        service.processarWebhook(evento, SUB, PAY, new BigDecimal("298.00"), OffsetDateTime.now());
    }

    // ======================================================= o dinheiro foi embora: corta

    @Test
    void ESTORNOintegral_poeAassinaturaINADIMPLENTEeDAprazoAoAcesso() {
        // O conserto. Antes: assinatura seguia ATIVA, acessoAte seguia NULO, loja usava de graca para
        // sempre e dava para repetir todo mes.
        Assinatura a = assinaturaAtiva();
        Loja l = lojaPagando();
        mensalidadePaga();

        chega("PAYMENT_REFUNDED");

        assertEquals(StatusAssinatura.INADIMPLENTE, a.getStatus());
        assertNotNull(l.acessoAte, "sem data de fim, o bloqueio nao corta nada");
        assertTrue(l.acessoAte.isAfter(OffsetDateTime.now().plusDays(9)), "a carencia e de 10 dias");
        assertTrue(l.acessoAte.isBefore(OffsetDateTime.now().plusDays(11)));
    }

    @Test
    void CHARGEBACKrecebido_cortaIgual() {
        Assinatura a = assinaturaAtiva();
        Loja l = lojaPagando();
        mensalidadePaga();

        chega("PAYMENT_CHARGEBACK_REQUESTED");

        assertEquals(StatusAssinatura.INADIMPLENTE, a.getStatus());
        assertNotNull(l.acessoAte);
    }

    @Test
    void recebimentoEmDINHEIROdesfeito_cortaIgual() {
        // Este evento o codigo nao tratava NEM para o faturamento. E o espelho do registro de
        // pagamento manual (v115): se alguem desfaz a baixa no Asaas, o nosso lado continuava
        // achando que estava pago.
        Assinatura a = assinaturaAtiva();
        Loja l = lojaPagando();
        PagamentoAssinatura pg = mensalidadePaga();

        chega("PAYMENT_RECEIVED_IN_CASH_UNDONE");

        assertEquals(StatusAssinatura.INADIMPLENTE, a.getStatus());
        assertNotNull(l.acessoAte);
        assertTrue(pg.estornado(), "e sai do faturamento: o dinheiro nao entrou");
    }

    @Test
    void oEstornoTIRAaMensalidadeDoFaturamento() {
        assinaturaAtiva();
        lojaPagando();
        PagamentoAssinatura pg = mensalidadePaga();

        chega("PAYMENT_REFUNDED");

        assertTrue(pg.estornado());
    }

    @Test
    void aCARENCIAnaoReiniciaQuandoJAhaPrazo() {
        // O mesmo cuidado que o PAYMENT_OVERDUE ja tinha: o Asaas reenvia aviso, e cada reenvio
        // regravando "agora + 10 dias" daria 10 dias de graca indefinidamente.
        assinaturaAtiva();
        Loja l = lojaPagando();
        OffsetDateTime prazoQueJaExiste = OffsetDateTime.now().plusDays(2);
        l.acessoAte = prazoQueJaExiste;
        mensalidadePaga();

        chega("PAYMENT_REFUNDED");

        assertSame(prazoQueJaExiste, l.acessoAte, "o prazo que ja existia nao pode ser empurrado");
    }

    // ======================================================= estorno parcial: NAO corta

    @Test
    void estornoPARCIALnaoCortaOacesso() {
        // O lojista pagou a maior parte. Cortar por uma devolucao de cortesia seria punir quem pagou.
        // Evento proprio no Asaas (PAYMENT_PARTIALLY_REFUNDED), que o codigo nao tratava.
        Assinatura a = assinaturaAtiva();
        Loja l = lojaPagando();

        chega("PAYMENT_PARTIALLY_REFUNDED");

        assertEquals(StatusAssinatura.ATIVA, a.getStatus());
        assertNull(l.acessoAte);
    }

    @Test
    void estornoPARCIALnaoTiraAmensalidadeINTEIRAdoFaturamento() {
        // Marcar como estornada tiraria os R$ 298 inteiros por causa de uma devolucao de R$ 20.
        // Guardar quanto voltou pede coluna propria -- varredura 3.6, que continua aberta.
        assinaturaAtiva();
        lojaPagando();
        PagamentoAssinatura pg = mensalidadePaga();

        chega("PAYMENT_PARTIALLY_REFUNDED");

        assertFalse(pg.estornado(), "a mensalidade continua cheia no faturamento, de proposito");
    }

    // ======================================================= intencao nao e fato

    @Test
    void estornoEMANDAMENTOnaoCortaNada() {
        // Ainda nao aconteceu, e o PAYMENT_REFUNDED vem depois. O proprio Asaas tem
        // PAYMENT_REFUND_DENIED, ou seja: o estorno pode nao sair. Cortar aqui seria cortar por uma
        // intencao.
        Assinatura a = assinaturaAtiva();
        Loja l = lojaPagando();
        PagamentoAssinatura pg = mensalidadePaga();

        chega("PAYMENT_REFUND_IN_PROGRESS");

        assertEquals(StatusAssinatura.ATIVA, a.getStatus());
        assertNull(l.acessoAte);
        assertFalse(pg.estornado());
    }

    @Test
    void estornoNEGADOnaoMexeEmNada() {
        // O dinheiro fica. E como o IN_PROGRESS nao cortou, nao ha nada para desfazer aqui.
        Assinatura a = assinaturaAtiva();
        Loja l = lojaPagando();

        chega("PAYMENT_REFUND_DENIED");

        assertEquals(StatusAssinatura.ATIVA, a.getStatus());
        assertNull(l.acessoAte);
    }

    @Test
    void aDISPUTAnaoReprocessaOcorte() {
        // Nenhuma informacao nova sobre o dinheiro: o chargeback ja foi recebido e ja cortou.
        // Reprocessar reiniciaria a carencia.
        Assinatura a = assinaturaAtiva();
        Loja l = lojaPagando();
        l.acessoAte = OffsetDateTime.now().plusDays(3);
        OffsetDateTime prazo = l.acessoAte;

        chega("PAYMENT_CHARGEBACK_DISPUTE");

        assertSame(prazo, l.acessoAte);
        assertEquals(StatusAssinatura.ATIVA, a.getStatus(), "o status de antes nao e mexido aqui");
    }

    @Test
    void aDISPUTAnaoTiraAmensalidadeDoFaturamento() {
        // Achado por mutacao: sem este teste, deixar a disputa cair no `marcarEstorno` passava verde.
        // Na sequencia normal e inofensivo (o CHARGEBACK_REQUESTED ja marcou, e marcar duas vezes nao
        // muda nada), mas uma disputa que chegue sozinha tiraria do faturamento um dinheiro que ainda
        // pode ser GANHO -- e a disputa existe justamente porque pode ser ganha.
        assinaturaAtiva();
        lojaPagando();
        PagamentoAssinatura pg = mensalidadePaga();

        chega("PAYMENT_CHARGEBACK_DISPUTE");

        assertFalse(pg.estornado(),
                "a disputa nao decide nada sobre o dinheiro; quem decide e o REQUESTED ou o REVERSAL");
    }

    // ======================================================= ganhamos a disputa: devolve

    @Test
    void disputaVENCIDAdevolveOacessoSEMprazo() {
        // Sem isto, o lojista que tinha razao ficaria cortado para sempre -- e o conserto teria
        // trocado "nao cortar quem deve" por "cortar quem nao deve".
        Assinatura a = assinaturaAtiva();
        a.setStatus(StatusAssinatura.INADIMPLENTE);
        Loja l = lojaPagando();
        l.ativo = false;
        l.acessoAte = OffsetDateTime.now().plusDays(3);

        chega("PAYMENT_AWAITING_CHARGEBACK_REVERSAL");

        assertEquals(StatusAssinatura.ATIVA, a.getStatus());
        assertTrue(l.ativo, "a loja volta ao ar");
        assertNull(l.acessoAte, "pagando, o acesso nao tem data de fim");
    }

    @Test
    void disputaVENCIDAdevolveAmensalidadeAOfaturamento() {
        assinaturaAtiva();
        lojaPagando();
        PagamentoAssinatura pg = mensalidadePaga();
        pg.estornadoEm = OffsetDateTime.now().minusDays(1);   // tinha saido no chargeback

        chega("PAYMENT_AWAITING_CHARGEBACK_REVERSAL");

        assertFalse(pg.estornado(), "o dinheiro volta para nos: volta para o faturamento");
    }

    @Test
    void disputaVENCIDAnaoLEVANTAsuspensaoADMINISTRATIVA() {
        // Quem suspendeu foi a plataforma; so a plataforma reativa. A regra ja existia no ativarLoja e
        // nao pode ser furada por este caminho novo.
        assinaturaAtiva();
        Loja l = lojaPagando();
        l.ativo = false;
        l.suspensaPelaPlataforma = true;

        chega("PAYMENT_AWAITING_CHARGEBACK_REVERSAL");

        assertFalse(l.ativo, "loja suspensa pela plataforma continua fora do ar");
    }

    // ======================================================= sem a loja, nao inventa

    @Test
    void cobrancaQueNAOestaNoNossoFaturamento_aindaAchaAlojaPelaASSINATURA() {
        // O aviso de devolucao nem sempre carrega o numero da assinatura, mas quando carrega da para
        // achar a loja mesmo sem a cobranca estar no nosso faturamento.
        Assinatura a = assinaturaAtiva();
        Loja l = lojaPagando();
        when(pagamentos.findByAsaasPaymentId(PAY)).thenReturn(Optional.empty());

        chega("PAYMENT_REFUNDED");

        assertEquals(StatusAssinatura.INADIMPLENTE, a.getStatus());
        assertNotNull(l.acessoAte);
    }

    @Test
    void semCobrancaEsemASSINATURA_naoCortaLojaNENHUMA() {
        // O pior erro possivel aqui seria cortar a loja errada, ou todas. Sem saber de quem e o
        // dinheiro, nao mexe em nada.
        //
        // O dublê responde para QUALQUER id de loja de proposito: a primeira versao deste teste so
        // dublava a loja 18, entao um mutante que chutasse "lojaId = 1" nao achava loja nenhuma e
        // passava verde. Agora qualquer chute acha uma loja -- e cortar qualquer uma reprova.
        assinaturaAtiva();
        Loja qualquer = new Loja();
        qualquer.id = 99L;
        qualquer.ativo = true;
        qualquer.acessoAte = null;
        when(lojas.findById(org.mockito.ArgumentMatchers.anyLong())).thenReturn(Optional.of(qualquer));
        when(pagamentos.findByAsaasPaymentId(any())).thenReturn(Optional.empty());

        service.processarWebhook("PAYMENT_REFUNDED", null, "pay_desconhecido", null, null);

        assertNull(qualquer.acessoAte, "nenhuma loja pode ganhar prazo de acesso aqui");
        verify(lojas, never()).save(any(Loja.class));
        verify(repo, never()).findByLojaId(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void eventoDeEstornoSEMpaymentIdNaoEstoura() {
        assinaturaAtiva();
        lojaPagando();

        assertDoesNotThrow(() -> service.processarWebhook("PAYMENT_REFUNDED", SUB, null, null, null));
    }

    // ======================================================= a lista de eventos

    @Test
    void osOITOeventosDeDevolucaoSaoEXATAMENTEosDaDOCUMENTACAO() throws java.io.IOException {
        // Transcritos de docs.asaas.com/docs/payment-events, conferido em 21/10/2026. Dois erros
        // possiveis aqui, e os dois sao caros:
        //   - FALTAR um: o evento chega, cai no `default`, e nada acontece. Foi o caso do
        //     PAYMENT_RECEIVED_IN_CASH_UNDONE e do PAYMENT_AWAITING_CHARGEBACK_REVERSAL.
        //   - INVENTAR um: codigo morto que PARECE tratado, e alguem confia nele.
        String fonte = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/br/com/bora/service/AssinaturaService.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        int ini = fonte.indexOf("EVENTOS_DE_DEVOLUCAO = java.util.Set.of(");
        assertTrue(ini > 0, "nao achei a lista de eventos");
        String bloco = fonte.substring(ini, fonte.indexOf(");", ini));

        for (String e : new String[]{
                "PAYMENT_REFUNDED", "PAYMENT_PARTIALLY_REFUNDED", "PAYMENT_REFUND_IN_PROGRESS",
                "PAYMENT_REFUND_DENIED", "PAYMENT_RECEIVED_IN_CASH_UNDONE",
                "PAYMENT_CHARGEBACK_REQUESTED", "PAYMENT_CHARGEBACK_DISPUTE",
                "PAYMENT_AWAITING_CHARGEBACK_REVERSAL"}) {
            assertTrue(bloco.contains("\"" + e + "\""), "falta tratar o evento " + e);
        }
        assertEquals(8, bloco.split("PAYMENT_", -1).length - 1,
                "a lista tem que ter EXATAMENTE os 8 eventos da documentacao, sem nome inventado");
    }

    @Test
    void soTRESeventosCORTAMoAcesso() {
        // A lista do que tira dinheiro. Entrar aqui por engano corta loja que pagou; sair daqui por
        // engano deixa usar de graca.
        for (String corta : new String[]{"PAYMENT_REFUNDED", "PAYMENT_CHARGEBACK_REQUESTED",
                "PAYMENT_RECEIVED_IN_CASH_UNDONE"}) {
            Assinatura a = assinaturaAtiva();
            Loja l = lojaPagando();
            mensalidadePaga();

            chega(corta);

            assertEquals(StatusAssinatura.INADIMPLENTE, a.getStatus(), corta + " tinha que cortar");
            assertNotNull(l.acessoAte, corta + " tinha que dar prazo ao acesso");
        }
        for (String naoCorta : new String[]{"PAYMENT_PARTIALLY_REFUNDED", "PAYMENT_REFUND_IN_PROGRESS",
                "PAYMENT_REFUND_DENIED", "PAYMENT_CHARGEBACK_DISPUTE"}) {
            Assinatura a = assinaturaAtiva();
            Loja l = lojaPagando();
            mensalidadePaga();

            chega(naoCorta);

            assertEquals(StatusAssinatura.ATIVA, a.getStatus(), naoCorta + " NAO podia cortar");
            assertNull(l.acessoAte, naoCorta + " NAO podia dar prazo ao acesso");
        }
    }

    // ======================================================= o que ja funcionava

    @Test
    void PAGAMENTOconfirmadoContinuaDevolvendoOacessoSEMprazo() {
        // O conserto nao pode ter mexido no caminho normal.
        Assinatura a = assinaturaAtiva();
        a.setStatus(StatusAssinatura.INADIMPLENTE);
        Loja l = lojaPagando();
        l.acessoAte = OffsetDateTime.now().plusDays(2);

        chega("PAYMENT_CONFIRMED");

        assertEquals(StatusAssinatura.ATIVA, a.getStatus());
        assertNull(l.acessoAte);
    }

    @Test
    void eventoDesconhecidoContinuaSendoIgnorado() {
        Assinatura a = assinaturaAtiva();
        Loja l = lojaPagando();

        chega("PAYMENT_CHECKOUT_VIEWED");

        assertEquals(StatusAssinatura.ATIVA, a.getStatus());
        assertNull(l.acessoAte);
    }
}
