package br.com.bora.security;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.service.IntegracaoService;
import br.com.bora.service.PixService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Segredo se compara em tempo constante — e "sem segredo" falha como "segredo errado".
 *
 * <h2>O defeito que isto fecha (item 8.8)</h2>
 * <p>{@code String.equals} para no primeiro caractere diferente. Quem chama o endpoint muitas vezes
 * com palpites mede essa diferença de tempo e descobre o segredo um caractere por vez, sem nunca
 * precisar acertar o todo. Na rede, com um token de 32+ caracteres, o ruído engole o sinal e o ataque
 * é pouco prático — a própria varredura chama de prioridade baixa. O que incomodava era outra coisa:
 * o projeto já fazia certo em <b>três</b> lugares, cada um com a sua cópia do código, e <b>dois</b>
 * ficaram com {@code equals}. Agora há um lugar só.</p>
 *
 * <h2>O caso que importa mais que o tempo</h2>
 * <p>Nulo tem que recusar, <b>inclusive nulo contra nulo</b>. Uma integração sem segredo cadastrado
 * aceitando chamada sem segredo é o webhook aberto para qualquer um — e esse não é um ataque de
 * laboratório, é um `curl`.</p>
 */
class SegredoSeComparaEmTempoConstanteTest {

    // =============================================================== o comparador

    @Test
    void iguaisQUANDOsaoIGUAIS() {
        assertTrue(Segredos.iguais("tok-secreto-123", "tok-secreto-123"));
    }

    @Test
    void diferentesQUANDOsaoDIFERENTES() {
        assertFalse(Segredos.iguais("tok-secreto-123", "tok-secreto-124"));
        assertFalse(Segredos.iguais("tok", "tok-secreto-123"), "prefixo certo nao vale");
        assertFalse(Segredos.iguais("tok-secreto-123", "tok"));
    }

    @Test
    void nuloNUNCAeIGUAL_nemAoutroNULO() {
        // O caso de verdade: integracao sem segredo cadastrado NAO pode aceitar chamada sem segredo.
        assertFalse(Segredos.iguais(null, null), "webhook sem segredo dos dois lados fica ABERTO");
        assertFalse(Segredos.iguais(null, "tok"));
        assertFalse(Segredos.iguais("tok", null));
    }

    @Test
    void vazioNAOeCURINGA() {
        assertFalse(Segredos.iguais("", "tok"));
        assertFalse(Segredos.iguais("tok", ""));
        // vazio contra vazio e igual, e isso esta certo -- quem guarda "" como segredo tem outro
        // problema, e o filtro de quem chama exige o campo nao-nulo antes de chegar aqui.
        assertTrue(Segredos.iguais("", ""));
    }

    @Test
    void acentoENAOeConfundido() {
        // Compara BYTES em UTF-8: dois textos diferentes nao podem colidir por causa da codificacao.
        assertFalse(Segredos.iguais("segredo-café", "segredo-cafe"));
        assertTrue(Segredos.iguais("segredo-café", "segredo-café"));
    }

    // =============================================================== o uso de verdade

    private Optional<IntegracaoCanal> autenticar(String tokenGravado, String tokenRecebido) {
        IntegracaoCanalRepository repo = mock(IntegracaoCanalRepository.class);
        IntegracaoCanal i = new IntegracaoCanal();
        i.lojaId = 18L;
        i.canal = "IFOOD";
        i.webhookToken = tokenGravado;
        when(repo.findByLojaIdAndCanal(18L, "IFOOD")).thenReturn(Optional.of(i));

        return new IntegracaoService(repo, mock(AuthContext.class), mock(PixService.class), List.of())
                .autenticarWebhook(18L, "ifood", tokenRecebido);
    }

    @Test
    void oWEBHOOKaceitaOtokenCERTO() {
        assertTrue(autenticar("tok-da-loja", "tok-da-loja").isPresent());
    }

    @Test
    void oWEBHOOKrecusaOtokenERRADO() {
        assertTrue(autenticar("tok-da-loja", "tok-da-lojb").isEmpty());
        assertTrue(autenticar("tok-da-loja", "tok").isEmpty(), "prefixo certo nao entra");
    }

    @Test
    void oWEBHOOKrecusaChamadaSEMtoken() {
        assertTrue(autenticar("tok-da-loja", null).isEmpty());
    }

    @Test
    void integracaoSEMtokenGRAVADOnaoACEITAninguem() {
        // A pior combinacao: integracao criada sem segredo + chamada sem segredo. Antes, com
        // `token != null && token.equals(...)`, isto ja recusava -- e continua recusando. Fica
        // medido para a troca do comparador nao reabrir a porta.
        assertTrue(autenticar(null, null).isEmpty());
        assertTrue(autenticar(null, "qualquer").isEmpty());
    }
}
