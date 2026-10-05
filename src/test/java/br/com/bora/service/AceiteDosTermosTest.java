package br.com.bora.service;

import br.com.bora.entity.Loja;
import br.com.bora.repository.LojaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * O aceite dos Termos das lojas que ja existiam.
 *
 * <p>O aceite nasceu junto com a tela de cadastro, entao so quem se cadastrou pelo site tem registro.
 * As cinco lojas em producao em 04/10/2026 estavam todas com {@code termos_versao} nulo — nenhum
 * contrato aceito com ninguem, num texto que fala de pagamento, suspensao e reembolso, e isso com o
 * corte por falta de pagamento ja ligado.</p>
 *
 * <p>O conserto NAO e preencher a coluna. Marcar as cinco como tendo aceitado hoje seria inventar um
 * consentimento que nunca houve, e um aceite fabricado vale menos que aceite nenhum. O conserto e o
 * caminho para o lojista aceitar de verdade — e e isso que estes testes guardam, inclusive a parte de
 * quem NAO pode aceitar.</p>
 */
class AceiteDosTermosTest {

    private LojaRepository lojas;
    private TermosService termos;
    private Loja zira;

    @BeforeEach
    void montar() {
        lojas = mock(LojaRepository.class);
        zira = new Loja();
        zira.id = 18L;
        zira.setNome("Zirá Açaíteria");
        when(lojas.findById(18L)).thenReturn(Optional.of(zira));
        when(lojas.save(any(Loja.class))).thenAnswer(i -> i.getArgument(0));
        termos = new TermosService(lojas);
    }

    @Test
    void lojaSemRegistroAparecePendente() {
        var s = termos.situacao(18L, "ADMINISTRADOR_LOJA");

        assertEquals(Boolean.TRUE, s.get("pendente"));
        assertNull(s.get("versaoAceita"), "nao pode inventar versao para quem nunca aceitou");
        assertEquals(Boolean.TRUE, s.get("primeiroAceite"));
        assertEquals(Boolean.TRUE, s.get("podeAceitar"));
    }

    @Test
    void oLojistaAceita_eADataGravadaEhADeVerdade() {
        OffsetDateTime antes = OffsetDateTime.now().minusSeconds(1);

        termos.aceitar(18L, "ADMINISTRADOR_LOJA", "187.11.22.33");

        assertEquals(TermosService.VERSAO_VIGENTE, zira.termosVersao);
        assertEquals("187.11.22.33", zira.termosAceitosDe);
        assertNotNull(zira.termosAceitosEm);
        assertTrue(zira.termosAceitosEm.isAfter(antes),
                "a data do aceite e o momento do clique, nao uma data escolhida a mao");
        verify(lojas).save(zira);
    }

    @Test
    void oSuporteDaPlataformaNaoAceitaPeloCliente() {
        var e = assertThrows(ResponseStatusException.class,
                () -> termos.aceitar(18L, "ADMINISTRADOR_BORA", "10.0.0.1"));

        assertEquals(403, e.getStatusCode().value());
        assertNull(zira.termosVersao, "o suporte clicando por engano nao pode virar aceite do lojista");
        verify(lojas, never()).save(any(Loja.class));
        // A mensagem importa e tem guarda propria: sem a checagem especifica do suporte, a regra
        // seguinte ("so o administrador da loja") recusa igual, mas diz a coisa errada para quem E
        // administrador — da plataforma. O suporte tem que entender que o aceite nao e dele.
        assertTrue(String.valueOf(e.getReason()).toLowerCase().contains("suporte"),
                "o motivo tem que explicar que o aceite e do lojista: " + e.getReason());
    }

    @Test
    void oGerenteNaoObrigaAEmpresaAUmContrato() {
        var e = assertThrows(ResponseStatusException.class,
                () -> termos.aceitar(18L, "GERENTE", "187.11.22.33"));

        assertEquals(403, e.getStatusCode().value());
        assertNull(zira.termosVersao);
    }

    @Test
    void aceitarDeNovo_naoReescreveADataDoAceiteOriginal() {
        termos.aceitar(18L, "ADMINISTRADOR_LOJA", "187.11.22.33");
        OffsetDateTime primeira = zira.termosAceitosEm;
        reset(lojas);
        when(lojas.findById(18L)).thenReturn(Optional.of(zira));

        var s = termos.aceitar(18L, "ADMINISTRADOR_LOJA", "200.9.9.9");

        assertEquals(primeira, zira.termosAceitosEm,
                "um F5 na tela nao pode mover a data do aceite para frente");
        assertEquals("187.11.22.33", zira.termosAceitosDe);
        assertEquals(Boolean.FALSE, s.get("pendente"));
        verify(lojas, never()).save(any(Loja.class));
    }

    @Test
    void textoNovoDosTermos_fazQuemEstavaEmVersaoAntigaAceitarDeNovo() {
        zira.termosVersao = "2026-08-28"; // aceitou o texto antigo
        zira.termosAceitosEm = OffsetDateTime.now().minusDays(40);

        var s = termos.situacao(18L, "ADMINISTRADOR_LOJA");

        assertEquals(Boolean.TRUE, s.get("pendente"));
        assertEquals("2026-08-28", s.get("versaoAceita"));
        assertEquals(Boolean.FALSE, s.get("primeiroAceite"),
                "nao e o primeiro aceite dela: e uma versao nova do texto");
    }

    @Test
    void quemNaoPodeAceitarRecebeUmRecadoQueExplicaOQueFazer() {
        assertTrue(String.valueOf(termos.situacao(18L, "ADMINISTRADOR_BORA").get("recado"))
                        .toLowerCase().contains("suporte"),
                "o suporte precisa entender por que o botao nao e dele");
        assertTrue(String.valueOf(termos.situacao(18L, "GERENTE").get("recado"))
                        .toLowerCase().contains("administrador"),
                "o gerente precisa saber a quem pedir");
    }
}
