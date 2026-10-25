package br.com.bora.service.marketplace;

import br.com.bora.entity.ConfigPlataforma;
import br.com.bora.repository.ConfigPlataformaRepository;
import br.com.bora.repository.IntegracaoCanalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * O TAMANHO do segredo da plataforma sai para a tela; o VALOR, nunca.
 *
 * <h2>Por que o tamanho passou a sair (25/10/2026)</h2>
 * <p>O 99Food da loja 1 passou dias devolvendo 401 e o painel nao tinha como denunciar: a tela dizia
 * "segredo salvo ✓" e ponto. A causa so ficou visivel comparando TAMANHOS no banco — o segredo da
 * plataforma tem 13 caracteres e o do unico app de 99Food que funciona neste sistema tem 32.</p>
 *
 * <p>Sem esse numero, a tela nova nao serviria para o caso que a fez existir: o dono veria "salvo ✓"
 * nos dois e nao teria motivo para desconfiar.</p>
 *
 * <h2>O que isso custa, dito com honestidade</h2>
 * <p>Tamanho de segredo e informacao a mais do que zero: estreita de leve uma adivinhacao. Mas o
 * endpoint exige {@code ADMINISTRADOR_BORA}, o segredo e do proprio dono, e sao chaves de API — nao
 * senha de pessoa, nao reusada em outro lugar. O que NAO pode acontecer, e e o que estes testes
 * trancam, e o valor sair.</p>
 */
class OtamanhoDoSecretSaiMasOvalorNuncaTest {

    private static final String CHAVE_ID = "credencial.opendelivery.client-id";
    private static final String CHAVE_SECRET = "credencial.opendelivery.client-secret";
    private static final String SEGREDO = "um-segredo-de-32-caracteres-aqui";   // 32

    private Map<String, ConfigPlataforma> tabela;
    private CredenciaisMarketplace credenciais;

    @BeforeEach
    void montar() {
        tabela = new HashMap<>();
        ConfigPlataformaRepository configs = mock(ConfigPlataformaRepository.class);
        when(configs.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(tabela.get(inv.getArgument(0))));
        credenciais = new CredenciaisMarketplace(configs, mock(IntegracaoCanalRepository.class),
                "", "", "", "");
    }

    private void salvoNaTela(String chave, String valor, OffsetDateTime quando) {
        ConfigPlataforma c = new ConfigPlataforma();
        c.setChave(chave);
        c.setValor(valor);
        c.setAtualizadoEm(quando);
        tabela.put(chave, c);
    }

    // ---------------------------------------------------------------- o tamanho
    @Test
    void oTAMANHOehOdoSEGREDOsalvo() {
        salvoNaTela(CHAVE_SECRET, SEGREDO, OffsetDateTime.now());
        assertEquals(32, credenciais.tamanhoDoSecret("NOVE_NOVE"));
    }

    @Test
    void oCASOdaLOJA1_umSEGREDOcurtoDENUNCIAsozinho() {
        // Reproduz o que estava gravado: 13 caracteres onde o que funciona tem 32. O numero e a
        // unica coisa que permite o dono olhar a tela e desconfiar.
        salvoNaTela(CHAVE_SECRET, "BR_xxxxxxxxxx", OffsetDateTime.now());   // 13
        assertEquals(13, credenciais.tamanhoDoSecret("NOVE_NOVE"));
        assertNotEquals(32, credenciais.tamanhoDoSecret("NOVE_NOVE"));
    }

    @Test
    void semSEGREDOsalvo_oTAMANHOehZERO_eNAOestoura() {
        assertEquals(0, credenciais.tamanhoDoSecret("NOVE_NOVE"),
                "tamanho nulo na tela viraria \"NaN caracteres\" na cara do dono");
    }

    // ---------------------------------------------------------------- o valor
    @Test
    void oVALORnaoSAIporNENHUMdosMETODOSdaTELA() {
        salvoNaTela(CHAVE_ID, "5764-app-id", OffsetDateTime.now());
        salvoNaTela(CHAVE_SECRET, SEGREDO, OffsetDateTime.now());

        // O que a tela consome: existe? de onde vem? que tamanho tem? quando foi gravado?
        assertTrue(credenciais.secretNaTela("NOVE_NOVE"));
        assertEquals("TELA", credenciais.origem("NOVE_NOVE"));
        assertEquals(32, credenciais.tamanhoDoSecret("NOVE_NOVE"));
        assertNotNull(credenciais.secretAtualizadoEm("NOVE_NOVE"));

        // Nenhuma dessas respostas pode CONTER o segredo. O `clientSecret` existe e devolve o valor,
        // mas e para os clients de marketplace usarem na autenticacao -- o controller nao o poe na
        // resposta, e e isso que o teste do controller trava.
        for (Object resposta : new Object[] {
                credenciais.secretNaTela("NOVE_NOVE"),
                credenciais.origem("NOVE_NOVE"),
                credenciais.tamanhoDoSecret("NOVE_NOVE"),
                credenciais.secretAtualizadoEm("NOVE_NOVE"),
        }) {
            assertFalse(String.valueOf(resposta).contains(SEGREDO),
                    "o segredo saiu numa resposta que a tela consome: " + resposta);
        }
    }

    @Test
    void oIDsaiEMclaro_dePROPOSITO() {
        // Client ID / App ID NAO e segredo: e o identificador publico do aplicativo, e o dono precisa
        // VER para conferir contra o portal. Confundir os dois faria a tela virar inutil.
        salvoNaTela(CHAVE_ID, "5764-app-id", OffsetDateTime.now());
        assertEquals("5764-app-id", credenciais.clientId("NOVE_NOVE"));
    }

    // ---------------------------------------------------------------- a data
    @Test
    void aDATAehAdoSEGREDO_eNAOadoID() {
        // Trocar so o id e deixar o segredo velho e um caminho real (o campo do segredo em branco
        // mantem o atual). Se a tela mostrasse a data do ID, ela mentiria sobre a idade do segredo
        // exatamente no caso em que a idade importa.
        OffsetDateTime velha = OffsetDateTime.now().minusDays(60);
        salvoNaTela(CHAVE_SECRET, SEGREDO, velha);
        salvoNaTela(CHAVE_ID, "5764-app-id", OffsetDateTime.now());

        assertEquals(velha, credenciais.secretAtualizadoEm("NOVE_NOVE"));
    }

    @Test
    void semSEGREDOnaTELA_aDATAehNULA() {
        assertNull(credenciais.secretAtualizadoEm("NOVE_NOVE"),
                "data inventada faria a tela dizer que ha segredo salvo quando nao ha");
    }

    @Test
    void oTAMANHOtambemVALEparaOsegredoDOSERVIDOR() {
        // Credencial que vem do api.env tem de aparecer com tamanho tambem: senao a tela diz
        // "vem do servidor" e nao ajuda a desconfiar dela.
        CredenciaisMarketplace doServidor = new CredenciaisMarketplace(
                mock(ConfigPlataformaRepository.class), mock(IntegracaoCanalRepository.class),
                "", "", "id-do-servidor", "segredo-do-servidor-23c");
        assertEquals(23, doServidor.tamanhoDoSecret("NOVE_NOVE"));
        assertEquals("SERVIDOR", doServidor.origem("NOVE_NOVE"));
        assertNull(doServidor.secretAtualizadoEm("NOVE_NOVE"),
                "o do servidor nao tem data de gravacao: ele vive no api.env, nao na tabela");
    }
}
