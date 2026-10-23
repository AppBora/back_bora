package br.com.bora.service;

import br.com.bora.entity.Assinatura;
import br.com.bora.entity.Loja;
import br.com.bora.entity.StatusAssinatura;
import br.com.bora.repository.AssinaturaRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PagamentoAssinaturaRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Dá para devolver o acesso de uma loja <b>sem dizer que entrou dinheiro</b>.
 *
 * <h2>O que faltava, e o caso que mostrou</h2>
 * <p>Até aqui a única forma de devolver acesso era {@code registrarPagamentoRecebidoForaDoAsaas} —
 * ou seja, <b>lançar uma receita</b>. Para cortesia, para negociação em andamento, ou para consertar
 * um prazo que o Asaas encurtou, isso obrigava a inventar dinheiro: o faturamento da plataforma
 * passaria a mentir para resolver um problema de acesso.</p>
 *
 * <p>O caso real (medido em 23/10/2026): a Zirá pagou <b>R$ 398</b> em 15/10, os dois pagamentos
 * estão registrados, e o registro manual liberou o acesso <b>sem data de fim</b>. Depois o Asaas
 * mandou {@code SUBSCRIPTION_DELETED} e aquele ramo passou por cima com "hoje + carência" — o acesso
 * dela voltou a ter data: <b>26/10</b>. Quem lesse o banco veria uma loja que pagou e vence em três
 * dias, sem nada explicando o porquê.</p>
 */
class LiberarAcessoNaoInventaPagamentoTest {

    private static final Long LOJA = 18L;

    private LojaRepository lojas;
    private AssinaturaRepository repo;
    private PagamentoAssinaturaRepository pagamentos;
    private AssinaturaService service;
    private Loja loja;

    @BeforeEach
    void montar() {
        lojas = mock(LojaRepository.class);
        repo = mock(AssinaturaRepository.class);
        pagamentos = mock(PagamentoAssinaturaRepository.class);
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, null, "ADMINISTRADOR_BORA", "adm@bora.app"));

        loja = new Loja();
        loja.id = LOJA;
        loja.setNome("Zirá Açaíteria");
        loja.setAtivo(true);
        loja.acessoAte = OffsetDateTime.now().plusDays(3);
        when(lojas.findById(LOJA)).thenReturn(Optional.of(loja));
        when(lojas.save(any())).thenAnswer(i -> i.getArgument(0));

        Assinatura a = new Assinatura();
        a.setLojaId(LOJA);
        a.setStatus(StatusAssinatura.CANCELADA);
        when(repo.findByLojaId(LOJA)).thenReturn(Optional.of(a));

        service = new AssinaturaService(repo, lojas, mock(br.com.bora.repository.UsuarioRepository.class),
                mock(br.com.bora.service.AsaasClient.class), ctx, pagamentos, 10);
    }

    private ResponseStatusException recusa(LocalDate ate, String motivo) {
        return assertThrows(ResponseStatusException.class, () -> service.liberarAcesso(LOJA, ate, motivo));
    }

    // =============================================================== o que ele faz

    @Test
    void liberaOacesso_eNAOregistraPAGAMENTOnenhum() {
        // O coracao: mexe no acesso e NAO toca em dinheiro.
        var r = service.liberarAcesso(LOJA, LocalDate.now().plusDays(30), "pagou R$ 398, Asaas cancelou");

        assertNotNull(loja.acessoAte);
        assertTrue(loja.acessoAte.isAfter(OffsetDateTime.now().plusDays(29)));
        verifyNoInteractions(pagamentos);
        assertEquals("pagou R$ 398, Asaas cancelou", r.get("motivo"));
    }

    @Test
    void naoMEXEnoSTATUSdaASSINATURA() {
        // Liberar acesso nao e dizer que a assinatura voltou: ela continua cancelada no Asaas, e o
        // painel tem que continuar mostrando isso.
        service.liberarAcesso(LOJA, LocalDate.now().plusDays(30), "cortesia");

        assertEquals(StatusAssinatura.CANCELADA, repo.findByLojaId(LOJA).orElseThrow().getStatus());
    }

    @Test
    void aRESPOSTAdizDEondeVEIOePARAonde() {
        OffsetDateTime antes = loja.acessoAte;

        var r = service.liberarAcesso(LOJA, LocalDate.now().plusDays(30), "cortesia");

        assertEquals(antes, r.get("acessoAteAntes"));
        assertNotNull(r.get("acessoAte"));
        assertTrue(String.valueOf(r.get("aviso")).contains("Nenhum pagamento"));
    }

    // =============================================================== as guardas

    @Test
    void semMOTIVOnaoLIBERA() {
        // Acesso liberado sem motivo vira misterio em tres meses: quem procurar no log acha o efeito
        // e nao acha a decisao.
        assertEquals(400, recusa(LocalDate.now().plusDays(30), null).getStatusCode().value());
        assertEquals(400, recusa(LocalDate.now().plusDays(30), "   ").getStatusCode().value());
    }

    @Test
    void semDATAnaoLIBERA() {
        assertEquals(400, recusa(null, "cortesia").getStatusCode().value());
    }

    @Test
    void dataNOpassadoNAOlibera() {
        // "Liberar" para tras seria TIRAR acesso por um caminho que nao avisa ninguem.
        var e = recusa(LocalDate.now().minusDays(1), "cortesia");
        assertTrue(e.getReason().contains("suspenda"), "foi: " + e.getReason());
    }

    @Test
    void dataDEhojeNAOlibera() {
        assertEquals(400, recusa(LocalDate.now(), "cortesia").getStatusCode().value());
    }

    @Test
    void acimaDOtetoNAOlibera() {
        // "Liberar para sempre" por engano e facil de fazer e dificil de notar: a loja some da
        // cobranca e ninguem repara.
        var e = recusa(LocalDate.now().plusDays(AssinaturaService.MAXIMO_DIAS_DE_LIBERACAO + 1), "cortesia");

        assertTrue(e.getReason().contains(String.valueOf(AssinaturaService.MAXIMO_DIAS_DE_LIBERACAO)),
                "foi: " + e.getReason());
        assertEquals(OffsetDateTime.now().plusDays(3).toLocalDate(), loja.acessoAte.toLocalDate(),
                "e nao pode ter mexido em nada");
    }

    @Test
    void noTETOexatoLIBERA() {
        assertDoesNotThrow(() ->
                service.liberarAcesso(LOJA, LocalDate.now().plusDays(AssinaturaService.MAXIMO_DIAS_DE_LIBERACAO),
                        "negociacao"));
    }

    @Test
    void lojaQUEnaoEXISTEda404() {
        when(lojas.findById(LOJA)).thenReturn(Optional.empty());

        assertEquals(404, recusa(LocalDate.now().plusDays(10), "cortesia").getStatusCode().value());
    }
}
