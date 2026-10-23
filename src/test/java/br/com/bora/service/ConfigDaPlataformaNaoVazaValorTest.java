package br.com.bora.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A listagem de configurações da plataforma era lista NEGRA: devolvia {@code chave -> valor} para
 * tudo o que não começasse com {@code credencial.}. Protegia o que alguém lembrou de proibir, e
 * deixava passar qualquer chave criada depois.
 *
 * <p>O caso concreto: {@code fiscal.focus-token} é o token da API fiscal e não começa com
 * {@code credencial.} — sairia em claro no dia em que o módulo fiscal fosse ligado. A coluna é
 * cifrada em repouso, mas o JPA decifra na leitura: a cifra protege o dump, não a resposta.</p>
 *
 * <p>Achado pelo agente de QA de API em 23/10/2026 (item 28.5). Hoje a tabela só tem
 * {@code fiscal.habilitado}, então ninguém estava vazando — era uma porta aberta esperando o
 * primeiro segredo passar por ela.</p>
 */
class ConfigDaPlataformaNaoVazaValorTest {

    @Test
    void oTokenFiscalNAOsaiEmClaro() {
        assertEquals(ChavesDaPlataforma.MARCA_DE_DEFINIDA,
                ChavesDaPlataforma.paraMostrar("fiscal.focus-token", "tok_vive_aqui_123"),
                "o token da API fiscal saiu em claro na listagem da plataforma");
    }

    @Test
    void aTelaAindaSabeQueAchaveEstaDefinida() {
        // Mostrar o valor e saber que existe sao coisas diferentes. A tela so precisa da segunda.
        assertEquals(ChavesDaPlataforma.MARCA_DE_DEFINIDA,
                ChavesDaPlataforma.paraMostrar("qualquer.coisa", "x"));
        assertEquals("", ChavesDaPlataforma.paraMostrar("qualquer.coisa", ""),
                "chave vazia nao pode parecer definida");
        assertEquals("", ChavesDaPlataforma.paraMostrar("qualquer.coisa", null));
    }

    @Test
    void oInterruptorFiscalContinuaSaindo_porqueATelaPrecisaDele() {
        // configuracoes.html:395 faz c['fiscal.habilitado'] === 'true'. Esconder isto quebraria a tela,
        // e "true"/"false" nao da acesso a nada.
        assertEquals("true", ChavesDaPlataforma.paraMostrar("fiscal.habilitado", "true"));
        assertEquals("false", ChavesDaPlataforma.paraMostrar("fiscal.habilitado", "false"));
    }

    @Test
    void chaveNOVAnasceProtegida_semNinguemPrecisarSeLembrarDeProibir() {
        // E a diferenca entre lista branca e lista negra, e e o ponto inteiro desta mudanca.
        for (String inventada : new String[]{
                "asaas.api-key", "fiscal.certificado-senha", "whatsapp.token",
                "integracao.nova.secret", "qualquer.chave.que.inventarem.amanha"}) {
            assertFalse(ChavesDaPlataforma.valorPodeSair(inventada),
                    "chave nova deveria nascer protegida: " + inventada);
            assertEquals(ChavesDaPlataforma.MARCA_DE_DEFINIDA,
                    ChavesDaPlataforma.paraMostrar(inventada, "valor-secreto"));
        }
    }

    @Test
    void chaveDeCredencialDeMarketplaceContinuaForaDaListagem() {
        // Essa ja era barrada antes, por CredenciaisMarketplace.ehChaveDeCredencial, e continua sendo
        // filtrada no controller. Aqui so garanto que ela tambem nao entrou na lista branca por engano.
        assertFalse(ChavesDaPlataforma.valorPodeSair("credencial.ifood.client-secret"));
    }

    @Test
    void nomeNuloNaoQuebra() {
        assertFalse(ChavesDaPlataforma.valorPodeSair(null));
    }
}
