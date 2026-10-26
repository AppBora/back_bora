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
 * Reativar uma loja NAO encurta o acesso dela, e nao apaga o motivo.
 *
 * <h2>O defeito (achado pelo QA em 26/10/2026)</h2>
 * <p>{@code prazoAoReativar} chamava {@code prazoDeAcesso(...)} direto — que <b>sobrescreve</b> data e
 * motivo. A guarda {@code prazoSemEncurtar}, criada para o acidente da Zira, protegia os caminhos do
 * webhook e <b>nao este</b>.</p>
 *
 * <p>Medido: loja com {@code LIBERACAO} ate 89 dias a frente — o estado da Zira hoje
 * ({@code acesso_ate = 2027-01-24}, assinatura {@code CANCELADA}) — depois de suspender + reativar
 * ficava com <b>hoje + 10 dias</b> e motivo <b>CORTESIA</b>. Com o corte por falta de pagamento
 * ligado em producao, ela sairia do ar dez dias depois de um clique, e o log de auditoria passaria a
 * dizer CORTESIA em vez do motivo real.</p>
 *
 * <p>Estes testes medem as DUAS pontas: o que tem de ser preservado e o que tem de ganhar carencia.
 * Sem a segunda metade, "consertar" viraria loja reativada sem prazo nenhum — o defeito que o
 * {@code prazoAoReativar} nasceu para fechar.</p>
 */
class ReativarNaoEncurtaOacessoTest {

    private static final Long LOJA = 18L;
    private static final int CARENCIA = 10;

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
                mock(br.com.bora.repository.PagamentoAssinaturaRepository.class), CARENCIA);
        when(lojas.save(any(Loja.class))).thenAnswer(i -> i.getArgument(0));

        loja = new Loja();
        loja.id = LOJA;
        loja.setAtivo(true);
        when(lojas.findById(LOJA)).thenReturn(Optional.of(loja));
    }

    private void assinaturaEm(StatusAssinatura st) {
        Assinatura a = new Assinatura();
        a.setLojaId(LOJA);
        a.setStatus(st);
        when(repo.findByLojaId(LOJA)).thenReturn(Optional.of(a));
    }

    // ------------------------------------------------- o que NAO pode ser encurtado
    @Test
    void LIBERACAOlongaNAOeENCURTADA_oCASOdaZIRA() {
        assinaturaEm(StatusAssinatura.CANCELADA);
        OffsetDateTime longe = OffsetDateTime.now().plusDays(89);
        loja.acessoAte = longe;
        loja.acessoMotivo = MotivoDoAcesso.LIBERACAO;

        service.prazoAoReativar(LOJA);

        assertEquals(longe, loja.acessoAte,
                "reativar derrubou 89 dias de acesso para a carencia: com o corte ligado, a loja "
                        + "sai do ar dez dias depois de um clique");
        assertEquals(MotivoDoAcesso.LIBERACAO, loja.acessoMotivo,
                "o motivo foi trocado para CORTESIA: o log passa a mentir sobre por que o acesso "
                        + "esta aberto");
    }

    @Test
    void PAGAMENTO_MANUALsemDATAnaoGANHAdataDEvencimento() {
        // Quem pagou por fora tem acesso sem data de fim, de proposito. Reativar nao pode inventar
        // um vencimento para quem pagou.
        assinaturaEm(StatusAssinatura.CANCELADA);
        loja.acessoAte = null;
        loja.acessoMotivo = MotivoDoAcesso.PAGAMENTO_MANUAL;

        service.prazoAoReativar(LOJA);

        assertNull(loja.acessoAte, "pos vencimento em quem pagou por fora");
        assertEquals(MotivoDoAcesso.PAGAMENTO_MANUAL, loja.acessoMotivo);
    }

    @Test
    void CORTESIAmaisLONGAqueAcarenciaEHmantida() {
        // Mesmo sem motivo intocavel: se a data que esta la e mais longe que a carencia, encurtar
        // seria tirar dias que alguem deu de proposito.
        assinaturaEm(StatusAssinatura.CANCELADA);
        OffsetDateTime em45 = OffsetDateTime.now().plusDays(45);
        loja.acessoAte = em45;
        loja.acessoMotivo = MotivoDoAcesso.CORTESIA;

        service.prazoAoReativar(LOJA);

        assertEquals(em45, loja.acessoAte, "encurtou uma cortesia de 45 dias para 10");
    }

    // ------------------------------------------------- o que PRECISA ganhar carencia
    @Test
    void semNADAmelhor_aLOJAganhaAcarencia() {
        // A outra metade. Sem isto, "consertar" viraria loja reativada com acesso sem fim e sem
        // cobranca — que e o defeito que este metodo nasceu para fechar.
        assinaturaEm(StatusAssinatura.CANCELADA);
        loja.acessoAte = null;
        loja.acessoMotivo = null;

        service.prazoAoReativar(LOJA);

        assertNotNull(loja.acessoAte, "loja reativada ficou sem prazo nenhum: uso gratis sem fim");
        assertTrue(loja.acessoAte.isAfter(OffsetDateTime.now().plusDays(CARENCIA - 1))
                        && loja.acessoAte.isBefore(OffsetDateTime.now().plusDays(CARENCIA + 1)),
                "a carencia deveria ser de " + CARENCIA + " dias; ficou " + loja.acessoAte);
        assertEquals(MotivoDoAcesso.CORTESIA, loja.acessoMotivo);
    }

    @Test
    void acessoJAvencidoGANHAacarencia() {
        // Loja suspensa com prazo que ja passou: a carencia e mais longe, entao vale.
        assinaturaEm(StatusAssinatura.CANCELADA);
        loja.acessoAte = OffsetDateTime.now().minusDays(30);
        loja.acessoMotivo = MotivoDoAcesso.ASSINATURA;

        service.prazoAoReativar(LOJA);

        assertTrue(loja.acessoAte.isAfter(OffsetDateTime.now()),
                "a loja voltou do suspenso ainda vencida: reativar nao serviu para nada");
    }

    // ------------------------------------------------- o que nao muda
    @Test
    void comASSINATURAativa_nadaEHtocado() {
        assinaturaEm(StatusAssinatura.ATIVA);
        OffsetDateTime antes = OffsetDateTime.now().plusDays(3);
        loja.acessoAte = antes;
        loja.acessoMotivo = MotivoDoAcesso.ASSINATURA;

        String r = service.prazoAoReativar(LOJA);

        assertEquals(antes, loja.acessoAte);
        assertEquals(MotivoDoAcesso.ASSINATURA, loja.acessoMotivo);
        assertTrue(r.contains("assinatura ativa"), "o retorno alimenta o log de auditoria: " + r);
    }

    @Test
    void oRETORNOdizOqueFOIfeito_porqueVAIparaOlogDEauditoria() {
        // `PlataformaController.reativar` loga este texto. Se ele disser "liberado por 10 dias"
        // quando nada foi mexido, o log mente sobre uma decisao de acesso.
        assinaturaEm(StatusAssinatura.CANCELADA);
        loja.acessoAte = OffsetDateTime.now().plusDays(89);
        loja.acessoMotivo = MotivoDoAcesso.LIBERACAO;

        String r = service.prazoAoReativar(LOJA);

        assertTrue(r.toLowerCase().contains("mantido"),
                "o log precisa dizer que o acesso foi MANTIDO, e nao que foi liberado: " + r);
    }
}
