package br.com.bora.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integracao que caiu em ERRO tem que voltar a tentar sozinha.
 *
 * <p>Antes, cair em ERRO era definitivo: o ciclo de polling so olhava quem estava CONECTADO, entao a
 * integracao saia da fila e nunca mais tentava — mesmo depois de o problema ser resolvido. Aconteceu
 * de verdade em 06/10/2026: uma credencial foi trocada por engano, a loja caiu em ERRO, a credencial
 * foi restaurada e ela continuou parada. So voltou quando alguem clicou em Conectar, e isso ninguem
 * descobre pela tela — ela mostra "ultimo erro" sem dizer que o sistema desistiu.</p>
 *
 * <p>Tentar a cada 30s com credencial errada seria martelar o marketplace e arriscar bloqueio. Por
 * isso a nova tentativa e espacada.</p>
 */
class IntegracaoVoltaSozinhaTest {

    private IntegracaoCanal emErro(OffsetDateTime ultimoPolling) {
        IntegracaoCanal i = new IntegracaoCanal();
        i.lojaId = 1L;
        i.canal = "NOVE_NOVE";
        i.merchantId = "boraloja2";
        i.status = "ERRO";
        i.ultimoPollingEm = ultimoPolling;
        return i;
    }

    @Test
    void erroAntigo_mereceNovaTentativa() {
        assertTrue(emErro(OffsetDateTime.now().minusMinutes(15)).mereceNovaTentativa(),
                "15 minutos parada: tem que tentar de novo em vez de ficar presa para sempre");
    }

    @Test
    void erroRecente_esperaAVezDele() {
        assertFalse(emErro(OffsetDateTime.now().minusMinutes(2)).mereceNovaTentativa(),
                "tentar a cada ciclo com credencial errada e martelar o marketplace");
    }

    @Test
    void nuncaTentou_tentaAgora() {
        assertTrue(emErro(null).mereceNovaTentativa());
    }

    @Test
    void semCodigoDaLoja_naoAdiantaTentar() {
        IntegracaoCanal i = emErro(OffsetDateTime.now().minusHours(1));
        i.merchantId = null;

        assertFalse(i.mereceNovaTentativa(), "sem o codigo da loja a chamada nao tem o que buscar");
    }

    @Test
    void quemEstaConectada_naoEntraPorEstaPorta() {
        IntegracaoCanal i = emErro(OffsetDateTime.now().minusHours(1));
        i.status = "CONECTADO";

        assertFalse(i.mereceNovaTentativa(), "quem esta bem ja entra pelo caminho normal");
        assertTrue(i.prontaParaSincronizar());
    }

    @Test
    void aguardandoAutorizacao_naoEhCasoDeRetentativa() {
        IntegracaoCanal i = emErro(OffsetDateTime.now().minusHours(1));
        i.status = "AGUARDANDO_AUTORIZACAO";

        assertFalse(i.mereceNovaTentativa(),
                "aqui falta o lojista autorizar no portal; insistir sozinho nao resolve");
    }
}
