package br.com.bora.service;

import br.com.bora.entity.*;
import br.com.bora.repository.AssinaturaRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PagamentoAssinaturaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.service.AsaasClient.EstadoDaAssinatura;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * "Ativar assinatura" não pode apagar a fatura atrasada, nem deixar a loja sem cobrança.
 *
 * <h2>Os dois defeitos (medidos em 20/10/2026)</h2>
 *
 * <p><b>1. Teste grátis infinito.</b> O {@code assinar} só saía cedo com status ATIVA. Com PENDENTE,
 * INADIMPLENTE ou CANCELADA, cancelava a assinatura no Asaas e criava outra com vencimento em
 * {@code hoje + 7}. O lojista inadimplente clicava "Reativar assinatura", a fatura atrasada
 * desaparecia e ele ganhava mais 7 dias — quantas vezes quisesse. E o
 * {@code BloqueioPorAssinaturaFilter} libera {@code /api/assinatura} de propósito, para a loja
 * bloqueada poder pagar: o caminho estava aberto justamente para quem tinha motivo de usar.</p>
 *
 * <p><b>2. Cancela aqui, falha ali, loja sem cobrança.</b> Cancelava no Asaas e só depois criava,
 * tudo numa transação. Falha na criação = rollback no nosso banco, apontando para a assinatura que lá
 * fora já estava morta. O painel dizia que estava tudo certo. <b>É o estado da loja da Zirá.</b></p>
 *
 * <h2>O que estes testes travam</h2>
 * <p>Que clicar de novo é inofensivo, que inadimplente é recusado <b>com o caminho para pagar</b>,
 * que a cortesia de 7 dias vale uma vez só, que assinatura apagada por fora ainda pode ser recriada
 * (senão a Zirá ficaria sem como voltar a pagar), e que um Asaas indisponível não faz nada no
 * escuro.</p>
 */
class ReativarAssinaturaNaoApagaFaturaTest {

    /**
     * "Hoje" no fuso de Brasilia, o MESMO que o AssinaturaService usa para montar o vencimento.
     *
     * <p>Estes testes usavam LocalDate.now() sem fuso. Na maquina de desenvolvimento o padrao e
     * America/Sao_Paulo e passavam; o container do deploy roda em <b>UTC</b>, e das 21h a meia-noite
     * de Brasilia o "hoje" sem fuso ja e amanha -- a data esperada ficava um dia a frente da que o
     * servico produz. Ver OfusoNaoPodeSerOdoSistemaTest, que proibe a classe toda do defeito.</p>
     */
    private static java.time.LocalDate hojeEmBrasilia() {
        return java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo"));
    }

    private AssinaturaRepository repo;
    private AsaasClient asaas;
    private AssinaturaService service;

    private static final Long LOJA = 18L;

    @BeforeEach
    void montar() {
        repo = mock(AssinaturaRepository.class);
        asaas = mock(AsaasClient.class);
        LojaRepository lojas = mock(LojaRepository.class);
        UsuarioRepository usuarios = mock(UsuarioRepository.class);
        AuthContext ctx = mock(AuthContext.class);

        service = new AssinaturaService(repo, lojas, usuarios, asaas, ctx,
                mock(PagamentoAssinaturaRepository.class), 10);

        when(ctx.lojaId()).thenReturn(LOJA);

        Loja l = new Loja();
        l.setId(LOJA);
        l.setNome("Zira Acaiteria");
        l.setPlano(Plano.UNICO);
        l.setPrecoMensal(new BigDecimal("199.00"));
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));

        Usuario dono = new Usuario();
        dono.setEmail("dono@zira.com");
        when(usuarios.donosDaLoja(LOJA)).thenReturn(List.of(dono));

        when(asaas.configurado()).thenReturn(true);
        when(asaas.criarCliente(anyString(), any(), any())).thenReturn("cus_1");
        when(asaas.criarAssinatura(anyString(), anyDouble(), anyString(), anyString()))
                .thenReturn(Map.of("id", "sub_NOVA"));
        when(asaas.cancelarAssinatura(anyString())).thenReturn(true);
        when(repo.save(any(Assinatura.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Assinatura comStatus(StatusAssinatura st, String subId) {
        Assinatura a = new Assinatura();
        a.setId(1L);
        a.setLojaId(LOJA);
        a.setPlano(Plano.UNICO);
        a.setStatus(st);
        a.setValor(new BigDecimal("199.00"));
        a.setAsaasCustomerId("cus_1");
        a.setAsaasSubscriptionId(subId);
        when(repo.findByLojaId(LOJA)).thenReturn(Optional.of(a));
        return a;
    }

    /** O vencimento que foi mandado ao Asaas na criação. */
    private String vencimentoCriado() {
        var cap = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(asaas).criarAssinatura(anyString(), anyDouble(), anyString(), cap.capture());
        return cap.getValue();
    }

    // =============================================================== clicar de novo é inofensivo

    @Test
    void ATIVA_clicarDeNovoNaoMexeEmNada() {
        Assinatura a = comStatus(StatusAssinatura.ATIVA, "sub_VELHA");

        assertSame(a, service.assinar(null));

        verify(asaas, never()).criarAssinatura(anyString(), anyDouble(), anyString(), anyString());
        verify(asaas, never()).cancelarAssinatura(anyString());
        verify(repo, never()).save(any(Assinatura.class));
    }

    @Test
    void PENDENTE_clicarDeNovoNaoCriaOutraCobrancaNemMoveOvencimento() {
        // PENDENTE = a assinatura e a primeira fatura JA existem; o lojista so ainda nao pagou.
        // Recriar aqui dava mais 7 dias de cortesia a cada clique.
        Assinatura a = comStatus(StatusAssinatura.PENDENTE, "sub_VELHA");

        assertSame(a, service.assinar(null));

        verify(asaas, never()).criarAssinatura(anyString(), anyDouble(), anyString(), anyString());
        verify(asaas, never()).cancelarAssinatura(anyString());
        assertEquals("sub_VELHA", a.getAsaasSubscriptionId());
    }

    @Test
    void PENDENTEnaoChegaNemAconsultarOasaas() {
        // Clicar num botao nao pode custar uma ida a rede quando a resposta ja esta no nosso banco.
        comStatus(StatusAssinatura.PENDENTE, "sub_VELHA");

        service.assinar(null);

        verify(asaas, never()).estadoDaAssinatura(anyString());
    }

    // =============================================================== inadimplente é recusado

    @Test
    void INADIMPLENTEcomAssinaturaVIVA_ehRECUSADO_eAfaturaCONTINUAla() {
        // O conserto principal. Antes, este clique cancelava a assinatura no Asaas (levando a fatura
        // atrasada com ela) e criava outra com 7 dias de cortesia.
        comStatus(StatusAssinatura.INADIMPLENTE, "sub_VELHA");
        when(asaas.estadoDaAssinatura("sub_VELHA")).thenReturn(EstadoDaAssinatura.VIVA);
        when(asaas.faturaAbertaDaAssinatura("sub_VELHA"))
                .thenReturn(new AsaasClient.FaturaAberta("pay_1", "https://asaas/fatura/1", "2026-10-10", 199.0));

        var erro = assertThrows(ResponseStatusException.class, () -> service.assinar(null));

        assertEquals(HttpStatus.CONFLICT, erro.getStatusCode());
        verify(asaas, never()).cancelarAssinatura(anyString());
        verify(asaas, never()).criarAssinatura(anyString(), anyDouble(), anyString(), anyString());
        verify(repo, never()).save(any(Assinatura.class));
    }

    @Test
    void aRECUSAdizAoLojistaCOMOpagar() {
        // Recusar sem dar o caminho deixaria o lojista bloqueado sem nenhuma acao possivel -- e o
        // botao que ele tinha era justamente o que apagava a divida.
        comStatus(StatusAssinatura.INADIMPLENTE, "sub_VELHA");
        when(asaas.estadoDaAssinatura("sub_VELHA")).thenReturn(EstadoDaAssinatura.VIVA);
        when(asaas.faturaAbertaDaAssinatura("sub_VELHA"))
                .thenReturn(new AsaasClient.FaturaAberta("pay_1", "https://asaas/fatura/1", "2026-10-10", 199.0));

        var erro = assertThrows(ResponseStatusException.class, () -> service.assinar(null));

        assertTrue(erro.getReason().toLowerCase().contains("fatura em aberto"),
                "a mensagem foi: " + erro.getReason());
        assertTrue(erro.getReason().contains("tela de Planos"),
                "tem que apontar onde esta o link; foi: " + erro.getReason());
    }

    @Test
    void semLinkDeFatura_aRECUSAmandaOlharOemail() {
        comStatus(StatusAssinatura.INADIMPLENTE, "sub_VELHA");
        when(asaas.estadoDaAssinatura("sub_VELHA")).thenReturn(EstadoDaAssinatura.VIVA);
        when(asaas.faturaAbertaDaAssinatura("sub_VELHA")).thenReturn(null);

        var erro = assertThrows(ResponseStatusException.class, () -> service.assinar(null));

        assertTrue(erro.getReason().contains("e-mail"), "foi: " + erro.getReason());
    }

    // =============================================================== o caso da Zirá

    @Test
    void assinaturaAPAGADAporFora_PODEserRecriada() {
        // O caso real: a assinatura da Zira esta deletada no Asaas e o nosso banco nao sabe. Se a
        // recusa valesse aqui, a loja ficaria SEM NENHUMA forma de voltar a pagar.
        comStatus(StatusAssinatura.INADIMPLENTE, "sub_MORTA");
        when(asaas.estadoDaAssinatura("sub_MORTA")).thenReturn(EstadoDaAssinatura.NAO_EXISTE);

        Assinatura r = service.assinar(null);

        assertEquals("sub_NOVA", r.getAsaasSubscriptionId());
        assertEquals(StatusAssinatura.PENDENTE, r.getStatus());
    }

    @Test
    void assinaturaAPAGADAporFora_NAOtentaCancelarOqueJAmorreu() {
        // Achado medindo a producao: a assinatura da Zira esta deletada no Asaas. Mandar cancelar o
        // que ja morreu devolve falha, e o codigo trataria essa falha como "nao consegui cancelar" --
        // anunciando COBRANCA EM DUPLICIDADE que nao existe, no log de auditoria e no painel do
        // lojista. Alarme falso e pior que silencio: ensina a ignorar o alarme.
        comStatus(StatusAssinatura.INADIMPLENTE, "sub_MORTA");
        when(asaas.estadoDaAssinatura("sub_MORTA")).thenReturn(EstadoDaAssinatura.NAO_EXISTE);

        Assinatura r = service.assinar(null);

        verify(asaas, never()).cancelarAssinatura(anyString());
        assertNull(r.getAsaasSubscriptionAnterior(),
                "nao ha cobranca duplicada: a anterior ja estava morta");
    }

    @Test
    void oAsaasEhConsultadoUMAvezSO() {
        // Dois `estadoDaAssinatura` na mesma requisicao seriam duas idas a rede para a mesma pergunta,
        // e pior: as duas poderiam responder DIFERENTE e o metodo decidir uma coisa e agir por outra.
        comStatus(StatusAssinatura.CANCELADA, "sub_VELHA");
        when(asaas.estadoDaAssinatura("sub_VELHA")).thenReturn(EstadoDaAssinatura.VIVA);

        service.assinar(null);

        verify(asaas, times(1)).estadoDaAssinatura("sub_VELHA");
    }

    // =============================================================== a cortesia vale uma vez

    @Test
    void aPRIMEIRAassinaturaGanhaOs7diasDeCortesia() {
        when(repo.findByLojaId(LOJA)).thenReturn(Optional.empty());

        service.assinar("12345678901");

        assertEquals(hojeEmBrasilia().plusDays(7).toString(), vencimentoCriado());
    }

    @Test
    void REASSINANDOnaoGanhaOutraCortesia() {
        // Era aqui que nascia o teste gratis infinito: cada clique empurrava o vencimento 7 dias.
        comStatus(StatusAssinatura.CANCELADA, "sub_VELHA");
        when(asaas.estadoDaAssinatura("sub_VELHA")).thenReturn(EstadoDaAssinatura.NAO_EXISTE);

        service.assinar(null);

        assertEquals(hojeEmBrasilia().plusDays(1).toString(), vencimentoCriado(),
                "quem ja usou o periodo de teste nao ganha outro por clicar de novo");
    }

    // =============================================================== cria antes de cancelar

    @Test
    void aORDEMehCRIARdepoisCANCELAR() {
        comStatus(StatusAssinatura.CANCELADA, "sub_VELHA");
        when(asaas.estadoDaAssinatura("sub_VELHA")).thenReturn(EstadoDaAssinatura.VIVA);

        service.assinar(null);

        var ordem = inOrder(asaas);
        ordem.verify(asaas).criarAssinatura(anyString(), anyDouble(), anyString(), anyString());
        ordem.verify(asaas).cancelarAssinatura("sub_VELHA");
    }

    @Test
    void seACRIACAOfalha_NADAehCanceladoEoErroVoltaParaATela() {
        // O defeito da Zira. Na ordem antiga, a assinatura antiga ja estava morta neste ponto e o
        // rollback deixava o nosso banco apontando para ela: loja sem cobranca, painel dizendo que
        // estava tudo certo.
        comStatus(StatusAssinatura.CANCELADA, "sub_VELHA");
        when(asaas.estadoDaAssinatura("sub_VELHA")).thenReturn(EstadoDaAssinatura.VIVA);
        when(asaas.criarAssinatura(anyString(), anyDouble(), anyString(), anyString())).thenReturn(null);

        var erro = assertThrows(ResponseStatusException.class, () -> service.assinar(null));

        assertEquals(HttpStatus.BAD_GATEWAY, erro.getStatusCode());
        verify(asaas, never()).cancelarAssinatura(anyString());
        verify(repo, never()).save(any(Assinatura.class));
    }

    @Test
    void seOCANCELAMENTOfalha_oIdORFAOficaGRAVADO() {
        // O risco que a nova ordem cria: duas assinaturas vivas, lojista cobrado em dobro. Nao da para
        // evitar, mas da para nao PERDER o id -- senao descobrir qual cancelar a mao exige garimpar no
        // Asaas.
        comStatus(StatusAssinatura.CANCELADA, "sub_VELHA");
        when(asaas.estadoDaAssinatura("sub_VELHA")).thenReturn(EstadoDaAssinatura.VIVA);
        when(asaas.cancelarAssinatura("sub_VELHA")).thenReturn(false);

        Assinatura r = service.assinar(null);

        assertEquals("sub_NOVA", r.getAsaasSubscriptionId());
        assertEquals("sub_VELHA", r.getAsaasSubscriptionAnterior(),
                "sem isto, a cobranca duplicada fica so numa linha de log que rotaciona");
    }

    @Test
    void cancelamentoOKlimpaOcampoDoOrfao() {
        Assinatura a = comStatus(StatusAssinatura.CANCELADA, "sub_VELHA");
        a.setAsaasSubscriptionAnterior("sub_ANTIGUISSIMA");   // sobra de uma falha anterior
        when(asaas.estadoDaAssinatura("sub_VELHA")).thenReturn(EstadoDaAssinatura.VIVA);

        Assinatura r = service.assinar(null);

        assertNull(r.getAsaasSubscriptionAnterior(), "cancelou com sucesso: nao ha orfao");
    }

    // =============================================================== nada no escuro

    @Test
    void asaasINDISPONIVEL_naoCancelaNemCria() {
        // A regra mais importante depois da recusa. Decidir sem saber o estado la fora foi o que
        // perdeu a assinatura da Zira.
        comStatus(StatusAssinatura.INADIMPLENTE, "sub_VELHA");
        when(asaas.estadoDaAssinatura("sub_VELHA")).thenReturn(EstadoDaAssinatura.INDISPONIVEL);

        var erro = assertThrows(ResponseStatusException.class, () -> service.assinar(null));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, erro.getStatusCode());
        assertTrue(erro.getReason().contains("Nada foi alterado"), "foi: " + erro.getReason());
        verify(asaas, never()).cancelarAssinatura(anyString());
        verify(asaas, never()).criarAssinatura(anyString(), anyDouble(), anyString(), anyString());
    }

    // =============================================================== a fatura na tela

    @Test
    void faturaEmAberto_soConsultaOasaasQuandoFazDiferenca() {
        // Assinatura em dia nao tem fatura para mostrar, e esta tela o lojista abre a toda hora.
        Assinatura ativa = comStatus(StatusAssinatura.ATIVA, "sub_1");
        assertNull(service.faturaEmAberto(ativa));

        Assinatura cancelada = comStatus(StatusAssinatura.CANCELADA, "sub_1");
        assertNull(service.faturaEmAberto(cancelada));

        verify(asaas, never()).faturaAbertaDaAssinatura(anyString());
    }

    @Test
    void faturaEmAberto_consultaQuandoINADIMPLENTEouPENDENTE() {
        when(asaas.faturaAbertaDaAssinatura("sub_1"))
                .thenReturn(new AsaasClient.FaturaAberta("pay_1", "https://asaas/f/1", "2026-10-10", 199.0));

        assertNotNull(service.faturaEmAberto(comStatus(StatusAssinatura.INADIMPLENTE, "sub_1")));
        assertNotNull(service.faturaEmAberto(comStatus(StatusAssinatura.PENDENTE, "sub_1")));
    }

    @Test
    void faturaEmAberto_semAssinaturaNaoEstoura() {
        assertNull(service.faturaEmAberto(null));
        assertNull(service.faturaEmAberto(comStatus(StatusAssinatura.INADIMPLENTE, null)));
    }
}
