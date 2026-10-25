package br.com.bora.controller;

import br.com.bora.entity.ConfigPlataforma;
import br.com.bora.repository.ConfigPlataformaRepository;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.service.marketplace.CredenciaisMarketplace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * O segredo do app da plataforma NAO sai na resposta de {@code GET /admin-bora/credenciais}.
 *
 * <h2>Por que este teste existe agora (25/10/2026)</h2>
 * <p>A tela que consome esse endpoint acabou de ser escrita, e com ela o endpoint passou a devolver
 * duas coisas novas: o TAMANHO do segredo e a DATA em que foi gravado. As duas foram deliberadas — o
 * tamanho e o que permite desconfiar de um segredo errado (foi assim que o 401 do 99Food da loja 1
 * foi diagnosticado: 13 caracteres onde o que funciona tem 32).</p>
 *
 * <p>Mas acrescentar campo a uma resposta que carrega credencial e exatamente o momento em que o
 * valor escapa por descuido. Este teste trava a linha: o que a tela recebe diz <b>se existe</b>,
 * <b>de onde vem</b>, <b>que tamanho tem</b> e <b>de quando e</b> — e nunca o segredo.</p>
 *
 * <p>O Client ID sai em claro de proposito: e o identificador publico do aplicativo, e o dono precisa
 * ve-lo para conferir contra o portal. Confundir os dois tornaria a tela inutil.</p>
 */
class OsegredoDaPlataformaNaoSaiNaRespostaTest {

    private static final String SEGREDO = "este-segredo-nao-pode-sair-nunca";
    private static final String ID = "5764-app-id-publico";

    private PlataformaController controller;

    @BeforeEach
    void montar() {
        Map<String, ConfigPlataforma> tabela = new HashMap<>();
        for (String[] par : new String[][] {
                { "credencial.opendelivery.client-id", ID },
                { "credencial.opendelivery.client-secret", SEGREDO },
        }) {
            ConfigPlataforma c = new ConfigPlataforma();
            c.setChave(par[0]);
            c.setValor(par[1]);
            c.setAtualizadoEm(OffsetDateTime.now().minusDays(30));
            tabela.put(par[0], c);
        }
        ConfigPlataformaRepository configs = mock(ConfigPlataformaRepository.class);
        when(configs.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(tabela.get(inv.getArgument(0))));

        CredenciaisMarketplace credenciais = new CredenciaisMarketplace(
                configs, mock(IntegracaoCanalRepository.class), "", "", "", "");

        // `requireAdminBora` e void: o duble nao estoura, que e o mesmo que "e administrador".
        controller = new PlataformaController(
                mock(br.com.bora.repository.LojaRepository.class),
                mock(br.com.bora.repository.UsuarioRepository.class),
                mock(org.springframework.security.crypto.password.PasswordEncoder.class),
                mock(AuthContext.class),
                mock(br.com.bora.repository.UsuarioLojaRepository.class),
                configs,
                mock(br.com.bora.service.CifragemEmRepousoService.class),
                mock(br.com.bora.service.ProvisionamentoService.class),
                mock(br.com.bora.service.AssinaturaService.class),
                mock(br.com.bora.service.IntegracaoService.class),
                mock(br.com.bora.repository.AssinaturaRepository.class),
                mock(br.com.bora.repository.PagamentoAssinaturaRepository.class),
                mock(br.com.bora.repository.PedidoRepository.class),
                mock(br.com.bora.service.EmpresaService.class),
                mock(br.com.bora.security.JwtService.class),
                mock(br.com.bora.repository.ConfiguracaoLojaRepository.class),
                credenciais,
                mock(br.com.bora.service.LimpezaDeTestesService.class),
                "0");
    }

    private Map<String, Object> doCanal(String canal) {
        List<Map<String, Object>> todos = controller.credenciais();
        return todos.stream().filter(m -> canal.equals(m.get("canal"))).findFirst()
                .orElseThrow(() -> new AssertionError("canal " + canal + " nao veio na listagem"));
    }

    // ---------------------------------------------------------------- a premissa
    @Test
    void PREMISSA_oSEGREDOrealmenteESTAsalvo() {
        // Sem isto, tudo abaixo passaria por nao haver segredo nenhum para vazar — a pior forma de
        // um teste de seguranca passar.
        Map<String, Object> m = doCanal("NOVE_NOVE");
        assertEquals(Boolean.TRUE, m.get("temSecret"), "o cenario nao tem segredo salvo");
        assertEquals(SEGREDO.length(), m.get("tamanhoSecret"));
    }

    // ---------------------------------------------------------------- o que nao pode sair
    @Test
    void oSEGREDOnaoAPARECEemNENHUMcampoDAresposta() {
        for (Map<String, Object> m : controller.credenciais()) {
            for (Map.Entry<String, Object> campo : m.entrySet()) {
                assertFalse(String.valueOf(campo.getValue()).contains(SEGREDO),
                        "o segredo saiu no campo \"" + campo.getKey() + "\" da resposta");
            }
            assertFalse(m.containsKey("clientSecret"),
                    "a resposta ganhou um campo clientSecret — o segredo nao volta para a tela");
        }
    }

    @Test
    void aRESPOSTAinteiraNAOcontemOsegredo_nemPORacidente() {
        // Varre o toString da estrutura toda: pega o caso de alguem aninhar um mapa novo com o valor
        // dentro, que o teste campo-a-campo acima deixaria passar.
        assertFalse(controller.credenciais().toString().contains(SEGREDO),
                "o segredo apareceu em algum lugar da resposta");
    }

    // ---------------------------------------------------------------- o que PRECISA sair
    @Test
    void oIDsaiEMclaro_porqueODONOprecisaCONFERIR() {
        assertEquals(ID, doCanal("NOVE_NOVE").get("clientId"),
                "sem ver o Client ID o dono nao consegue comparar com o portal");
    }

    @Test
    void oTAMANHOeAdataSAEM_porqueSAOoQUEpermitemDESCONFIAR() {
        Map<String, Object> m = doCanal("NOVE_NOVE");
        assertEquals(32, m.get("tamanhoSecret"),
                "sem o tamanho a tela diz \"salvo ✓\" e o dono nao tem como suspeitar do valor");
        assertNotNull(m.get("secretAtualizadoEm"),
                "sem a data, credencial da homologacao e credencial de hoje sao indistinguiveis");
        assertEquals("TELA", m.get("origem"));
    }

    @Test
    void osDOIScanaisVEM_mesmoSEMcredencialSALVA() {
        // O iFood nao tem nada salvo neste cenario. Ele tem de aparecer na lista assim mesmo, senao
        // o dono nao tem onde digitar a primeira credencial.
        Map<String, Object> ifood = doCanal("IFOOD");
        assertEquals(Boolean.FALSE, ifood.get("temSecret"));
        assertEquals(0, ifood.get("tamanhoSecret"), "tamanho nulo viraria \"NaN caracteres\" na tela");
        assertNull(ifood.get("secretAtualizadoEm"));
        assertNull(ifood.get("origem"), "sem credencial nenhuma, a origem e nula");
    }
}
