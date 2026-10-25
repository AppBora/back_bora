package br.com.bora.service.marketplace;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Loja;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.security.RegraDeAcesso;
import br.com.bora.service.MarketplaceNormalizer;
import br.com.bora.service.PedidoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integracao ativa que NAO PODE receber pedido para de ser pulada em silencio.
 *
 * <h2>O defeito (achado em 25/10/2026, atacando o item 24)</h2>
 * <p>O poller fazia {@code continue} seco quando {@code prontaParaSincronizar()} era falso. Sem log,
 * sem nada na tela. A integracao ficava <b>ATIVA e marcada CONECTADO para sempre</b>, sem nunca
 * buscar um pedido — um estado que nao tinha como ser descoberto por ninguem.</p>
 *
 * <p><b>Medido na producao:</b> a loja 17 (Pizzaria Bella Napoli, 2 usuarios, 4 pedidos, 18 produtos)
 * estava exatamente assim: iFood {@code CONECTADO}, {@code ativo = true}, {@code merchant_id} vazio
 * e {@code ultimo_polling_em} <b>NULO</b> — nunca buscou nada desde que nasceu. O painel dela dizia
 * conectado. Se chegasse pedido pelo iFood, nao entraria, e nada explicaria.</p>
 *
 * <p>O contraste que denuncia o descuido: o caso vizinho no mesmo laco — "a loja nao pode operar"
 * (suspensa, arquivada, prazo vencido) — <b>ja logava</b>. Este nao.</p>
 *
 * <h2>Onde a mensagem vai, e por que</h2>
 * <p>No {@code ultimo_erro}, que e <b>saude da integracao</b>, e nao no {@code ultimo_aviso}, que e
 * recado sobre um pedido (ver V61). Isto e condicao do presente, e o ciclo de vida fecha certo: o
 * lojista preenche o que falta, o polling passa a rodar, da certo, e o {@code marcarPollingOk} limpa
 * a mensagem sozinho. No {@code ultimo_aviso} ela ficaria la depois de resolvida, esperando alguem
 * clicar em "Ja vi".</p>
 */
class IntegracaoQueNaoPodeReceberNaoFicaCaladaTest {

    private static final long LOJA = 17L;

    private IntegracaoCanalRepository repo;
    private MarketplaceClient client;
    private MarketplacePoller poller;
    private IntegracaoCanal integ;

    @BeforeEach
    void montar() {
        repo = mock(IntegracaoCanalRepository.class);
        client = mock(MarketplaceClient.class);
        when(client.canal()).thenReturn("IFOOD");
        when(client.configurado(any())).thenReturn(true);
        when(client.configurado()).thenReturn(true);
        when(client.polling(any())).thenReturn(List.of());

        LojaRepository lojas = mock(LojaRepository.class);
        Loja l = new Loja();
        l.id = LOJA;
        l.setAtivo(true);
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));

        RegraDeAcesso regra = mock(RegraDeAcesso.class);
        when(regra.podeOperar(any())).thenReturn(true);

        integ = new IntegracaoCanal();
        integ.id = 42L;
        integ.lojaId = LOJA;
        integ.canal = "IFOOD";
        integ.ativo = true;
        integ.status = "CONECTADO";
        integ.merchantId = null;               // o estado real da loja 17
        when(repo.findByAtivoTrue()).thenReturn(List.of(integ));

        poller = new MarketplacePoller(List.of(client), repo,
                mock(MarketplaceNormalizer.class), mock(PedidoService.class), lojas, regra, true);
    }

    // ---------------------------------------------------------------- a premissa
    @Test
    void SEMoIDdaLOJA_oPOLLINGnaoACONTECE() {
        // Se o polling rodasse, nao haveria defeito nenhum a consertar. Esta e a premissa do resto.
        poller.rodar();
        verify(client, never()).polling(any());
    }

    @Test
    void eELAdizPORQUE_ondeOlojistaVE() {
        poller.rodar();

        assertNotNull(integ.ultimoErro,
                "pulada em silencio: a integracao fica ATIVA e CONECTADA sem nunca buscar pedido, "
                        + "e nada na tela nem no log diz isso");
        assertTrue(integ.ultimoErro.contains("Merchant ID"),
                "tem de nomear o campo que falta, como ele aparece na tela: " + integ.ultimoErro);
        assertTrue(integ.ultimoErro.contains("iFood"),
                "e de qual canal: " + integ.ultimoErro);
        verify(repo).marcarImpedimento(eq(42L), eq(LOJA), contains("Merchant ID"));
    }

    @Test
    void aMENSAGEMdizAconsequencia_eNAOsoOqueFALTA() {
        // "Falta o Merchant ID" nao diz ao lojista o que ele perde. O que ele precisa entender e que
        // pedido nenhum entra enquanto isso.
        poller.rodar();
        assertTrue(integ.ultimoErro.toLowerCase().contains("nenhum pedido"),
                "a mensagem tem de dizer o estrago: " + integ.ultimoErro);
    }

    // ---------------------------------------------------------------- o que NAO pode acontecer
    @Test
    void NAOreescreveAcadaCICLO() {
        // O poller roda a cada 30 segundos. Gravar sempre seria uma escrita por integracao a cada
        // meio minuto, para sempre, dizendo exatamente a mesma coisa.
        poller.rodar();
        poller.rodar();
        poller.rodar();

        verify(repo, times(1)).marcarImpedimento(anyLong(), anyLong(), anyString());
    }

    @Test
    void NAOmarcaERRO_paraNAOcairNaFILAdeDEZminutos() {
        // Item 5.1: marcar ERRO espaca as tentativas em 10 minutos. Isto nao e falha, e configuracao
        // incompleta -- e, no minuto em que o lojista preencher, o polling tem de voltar no proximo
        // ciclo de 30 segundos, nao daqui a dez minutos.
        poller.rodar();

        assertEquals("CONECTADO", integ.status, "mudou o status: a integracao cai na fila lenta");
        verify(repo, never()).marcarErro(anyLong(), anyLong(), anyString());
    }

    @Test
    void NAOgravaNAcolunaDOrecado() {
        // `ultimo_aviso` e recado sobre um pedido, que SO o lojista apaga (V61). Isto e saude: tem de
        // sumir sozinho quando o problema acabar. Confundir as duas foi o defeito que a V61 fechou.
        poller.rodar();

        verify(repo, never()).marcarAviso(anyLong(), anyLong(), anyString(), any());
        assertNull(integ.ultimoAviso);
    }

    @Test
    void NAOmexeNAlinhaINTEIRA() {
        // Item 5.5: o lojista pode estar salvando a tela de Integracoes neste instante. Gravar a
        // linha inteira desfaria o que ele acabou de salvar.
        poller.rodar();
        verify(repo, never()).save(any());
    }

    // ---------------------------------------------------------------- quando esta tudo certo
    @Test
    void comOidDaLOJA_oPOLLINGroda_eNADAeESCRITO() {
        integ.merchantId = "a092eec4-f8e4-4300-b135-2073fb5ceed3";

        poller.rodar();

        verify(client).polling(any());
        verify(repo, never()).marcarImpedimento(anyLong(), anyLong(), anyString());
        assertNull(integ.ultimoErro, "encheu a tela de aviso numa integracao que esta boa");
    }

    @Test
    void statusQUEnaoEHconectado_tambemEHdito() {
        // Outro jeito de ficar parada em silencio: ativa, com o ID preenchido, e desconectada.
        integ.merchantId = "m-1";
        integ.status = "DESCONECTADO";

        poller.rodar();

        assertNotNull(integ.ultimoErro);
        assertTrue(integ.ultimoErro.contains("DESCONECTADO"),
                "tem de dizer em que estado a conexao esta: " + integ.ultimoErro);
    }

    @Test
    void integracaoDESLIGADAnaoRECEBEaviso() {
        // `ativo = false` e o lojista dizendo "nao quero este canal agora". Encher de aviso um canal
        // que ele desligou de proposito e ensinar o lojista a ignorar a tela.
        integ.ativo = false;
        when(repo.findByAtivoTrue()).thenReturn(List.of());

        poller.rodar();

        verify(repo, never()).marcarImpedimento(anyLong(), anyLong(), anyString());
        assertNull(integ.ultimoErro);
    }
}
