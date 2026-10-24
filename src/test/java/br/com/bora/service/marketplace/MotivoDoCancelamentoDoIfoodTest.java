package br.com.bora.service.marketplace;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Por que o cliente do iFood desistiu — achado pelo QA em 24/10/2026.
 *
 * <p>O {@code IfoodClient} não sobrescrevia {@code motivoDoCancelamento}, então caía no padrão de
 * {@link MarketplaceClient} que devolve {@code null}. O painel gravava o genérico "Cancelado pelo
 * marketplace", e <b>qualquer motivo que o iFood mandasse era descartado por construção</b>.</p>
 *
 * <p>No mesmo painel, a 99Food explicava e o iFood não. O lojista via o pedido cancelado sem saber
 * se foi o cliente que desistiu, endereço fora de área ou falta de entregador — que é justamente o
 * que decide se ele liga para alguém ou refaz a comida.</p>
 */
class MotivoDoCancelamentoDoIfoodTest {

    // Nada aqui vai à rede: o método só lê o Map do evento. O cliente é montado como os outros
    // testes deste pacote fazem, com a base-url apontando para lugar nenhum.
    private final IfoodClient ifood = new IfoodClient(
            "http://127.0.0.1:1",
            mock(CredenciaisMarketplace.class),
            mock(br.com.bora.repository.IntegracaoCanalRepository.class),
            new MarketplaceHttp());

    private Map<String, Object> evento(Map<String, Object> metadata) {
        Map<String, Object> ev = new HashMap<>();
        ev.put("fullCode", "CANCELLED");
        if (metadata != null) ev.put("metadata", metadata);
        return ev;
    }

    private Map<String, Object> mapa(String... pares) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < pares.length; i += 2) m.put(pares[i], pares[i + 1]);
        return m;
    }

    // ---------------------------------------------------------------- a premissa
    @Test
    void motivoEmMetadata_chegaAoPainel() {
        String r = ifood.motivoDoCancelamento(
                evento(mapa("reason", "CLIENTE DESISTIU", "cancellationCode", "501")));

        assertTrue(r.contains("CLIENTE DESISTIU"),
                "o motivo que o iFood mandou foi descartado: o lojista não sabe por que caiu");
        assertTrue(r.contains("501"), "o código ajuda o suporte a conferir no Portal");
        assertTrue(r.startsWith("Cancelado pelo iFood"), "precisa dizer de onde veio");
    }

    @Test
    void motivoSolto_naRaizDoEvento_tambemEhLido() {
        // Integração mais antiga manda sem o envelope `metadata`. Apostar num só lugar faria o
        // motivo voltar a sumir, que é o defeito que estamos fechando.
        Map<String, Object> ev = new HashMap<>();
        ev.put("fullCode", "CANCELLED");
        ev.put("cancellationReason", "ENDERECO FORA DA AREA");

        assertTrue(ifood.motivoDoCancelamento(ev).contains("ENDERECO FORA DA AREA"));
    }

    @Test
    void semCodigo_oMotivoSozinhoJaServe() {
        String r = ifood.motivoDoCancelamento(evento(mapa("reason", "SEM ENTREGADOR")));
        assertEquals("Cancelado pelo iFood: SEM ENTREGADOR", r);
    }

    // ---------------------------------------------------------------- o que não pode sair feio
    @Test
    void codigoSozinho_NAO_viraTextoParaOlojista() {
        // "Cancelado pelo iFood (501)" não diz nada a quem lê o painel. Melhor o genérico.
        String r = ifood.motivoDoCancelamento(evento(mapa("cancellationCode", "501")));
        assertEquals("Cancelado pelo iFood", r);
        assertFalse(r.contains("501"));
    }

    @Test
    void eventoSemMotivoNenhum_caiNoGenerico() {
        assertEquals("Cancelado pelo iFood", ifood.motivoDoCancelamento(evento(null)));
        assertEquals("Cancelado pelo iFood", ifood.motivoDoCancelamento(evento(mapa())));
    }

    @Test
    void eventoNulo_naoEstoura() {
        // O cancelamento não pode travar por causa de um payload estranho: o pedido PRECISA ser
        // cancelado aqui, com motivo ou sem.
        assertEquals("Cancelado pelo iFood", ifood.motivoDoCancelamento(null));
    }

    @Test
    void motivoEmBranco_contaComoAusente() {
        assertEquals("Cancelado pelo iFood",
                ifood.motivoDoCancelamento(evento(mapa("reason", "   "))));
    }
}
