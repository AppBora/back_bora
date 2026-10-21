package br.com.bora.controller;

import br.com.bora.entity.Loja;
import br.com.bora.repository.LojaRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A tela do split diz o que de fato é cobrado — e o aviso da conta bancária não é mais engolido.
 *
 * <h2>Item 3.9 da varredura</h2>
 * <p>O endpoint respondia {@code efetivo: "2%"} sem olhar se o split <b>tem como acontecer</b>. Ele só
 * sai quando o recebimento é pela <b>subconta</b> da loja: o {@code PixService} exige
 * {@code viaSubconta} mais a carteira da plataforma. Loja que recebe pela chave PIX própria dela
 * <b>nunca paga nada</b> — e a tela respondia que estava cobrando. Quem olha essa tela decide preço em
 * cima disso.</p>
 *
 * <h2>Item 3.7, e o que a documentação mudou no conserto</h2>
 * <p>A varredura dizia que decidir a aprovação pelo resumo {@code general} é defeito, porque deixaria
 * "o cliente pagando para uma conta que não pode sacar". <b>Medindo na documentação do Asaas, isso não
 * se confirma:</b> eles dizem que a conta está 100% aprovada quando o {@code general} volta APPROVED, e
 * a regra do {@code general} cita dados comerciais e documentação — não a conta bancária. Bloquear o
 * PIX por isso seria agir por um receio, e travaria loja legitimamente aprovada.</p>
 *
 * <p><b>O que a documentação confirma:</b> o {@code general} pode ficar APPROVED com pendência, existe
 * webhook próprio para a conta bancária voltar a pendente, e eles instruem a <i>tratar a pendência
 * mesmo que o {@code general} esteja APPROVED</i>. Era isso que o código não fazia: lia o resumo e
 * jogava o resto fora sem nem registrar. Agora avisa, e não bloqueia.</p>
 */
class OsplitDizAverdadeTest {

    private LojaRepository lojas;
    private PlataformaController controller;

    private static final Long LOJA = 18L;

    private Loja loja;

    @BeforeEach
    void montar() {
        lojas = mock(LojaRepository.class);
        AuthContext ctx = mock(AuthContext.class);

        loja = new Loja();
        loja.id = LOJA;
        loja.setNome("Zirá Açaíteria");
        when(lojas.findById(LOJA)).thenReturn(Optional.of(loja));
        when(lojas.save(any(Loja.class))).thenAnswer(i -> i.getArgument(0));

        controller = new PlataformaController(lojas, mock(br.com.bora.repository.UsuarioRepository.class),
                mock(org.springframework.security.crypto.password.PasswordEncoder.class), ctx,
                mock(br.com.bora.repository.UsuarioLojaRepository.class),
                mock(br.com.bora.repository.ConfigPlataformaRepository.class),
                mock(br.com.bora.service.ProvisionamentoService.class),
                mock(br.com.bora.service.AssinaturaService.class),
                mock(br.com.bora.service.IntegracaoService.class),
                mock(br.com.bora.repository.AssinaturaRepository.class),
                mock(br.com.bora.repository.PagamentoAssinaturaRepository.class),
                mock(br.com.bora.repository.PedidoRepository.class),
                mock(br.com.bora.service.EmpresaService.class),
                mock(br.com.bora.security.JwtService.class),
                mock(br.com.bora.repository.ConfiguracaoLojaRepository.class),
                mock(br.com.bora.service.marketplace.CredenciaisMarketplace.class),
                mock(br.com.bora.service.LimpezaDeTestesService.class), "2");
    }

    private Map<String, Object> definirSplit(Object percentual) {
        Map<String, Object> corpo = new java.util.HashMap<>();
        // A chave do corpo e `percentual` (ver PlataformaController.definirSplit), nao
        // `splitPercentual` -- esse e o nome do campo na entidade e da chave da RESPOSTA.
        corpo.put("percentual", percentual);
        return controller.definirSplit(LOJA, corpo);
    }

    // =============================================================== sem subconta, nao incide

    @Test
    void lojaSEMsubcontaOUVEqueOsplitNAOincide() {
        // O caso que a varredura achou: a tela dizia "2%" para loja que nunca pagou nada.
        loja.asaasApiKey = null;

        var r = definirSplit("2");

        assertEquals(false, r.get("recebePelaSubconta"));
        assertEquals("0% (nao incide)", r.get("efetivo"));
    }

    @Test
    void aRESPOSTAdizPORQUEnaoIncide_eOqueFAZER() {
        // "0%" sozinho faria o dono achar que o percentual esta zerado no cadastro.
        loja.asaasApiKey = null;

        var r = definirSplit("2");

        assertTrue(String.valueOf(r.get("porQue")).contains("NAO recebe pela subconta"),
                "foi: " + r.get("porQue"));
        assertTrue(String.valueOf(r.get("porQue")).contains("Integracoes"),
                "tem que dizer onde ativar");
    }

    @Test
    void oPERCENTUALconfiguradoCONTINUAvisivel() {
        // O numero que ficou no cadastro nao se perde: ele passa a valer no dia que a loja migrar
        // para a subconta.
        loja.asaasApiKey = null;

        var r = definirSplit("3.5");

        assertEquals("3.5%", r.get("percentualConfigurado"));
        assertEquals(0, new java.math.BigDecimal("3.5")
                .compareTo((java.math.BigDecimal) r.get("splitPercentual")));
    }

    // =============================================================== com subconta, incide

    @Test
    void lojaCOMsubcontaOUVEoPERCENTUALdeVERDADE() {
        loja.asaasApiKey = "chave-da-subconta";

        var r = definirSplit("2");

        assertEquals(true, r.get("recebePelaSubconta"));
        assertEquals("2%", r.get("efetivo"));
        assertTrue(String.valueOf(r.get("porQue")).contains("retida em cada PIX"));
    }

    @Test
    void SEMpercentualPROPRIOvaleOpadraoDAplataforma() {
        loja.asaasApiKey = "chave-da-subconta";

        var r = definirSplit(null);

        assertEquals("2%", r.get("efetivo"), "sem valor proprio, vale o padrao global");
        assertNull(r.get("splitPercentual"));
        assertEquals("2%", r.get("padraoDaPlataforma"));
    }

    @Test
    void FUNDADORisentoCOMsubcontaRECEBEzeroHONESTO() {
        // Fundador tem 0% proprio. Aqui o "0%" e verdade, e e diferente do "0% (nao incide)".
        loja.asaasApiKey = "chave-da-subconta";

        var r = definirSplit("0");

        assertEquals("0%", r.get("efetivo"));
        assertEquals(true, r.get("recebePelaSubconta"));
    }

    @Test
    void oPERCENTUALinvalidoCONTINUAsendoRECUSADO() {
        loja.asaasApiKey = "chave-da-subconta";

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> definirSplit("101"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> definirSplit("-1"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> definirSplit("abc"));
    }

    // =============================================================== 3.7 o aviso da conta bancaria

    @Test
    void oGERALaprovadoCONTINUAaprovando_masAcontaBANCARIAeREGISTRADA() throws IOException {
        // Nao bloqueia (a documentacao do Asaas diz que `general` e o sinal oficial), mas tambem nao
        // ignora: eles instruem a tratar a pendencia mesmo com o general APPROVED.
        String fonte = Files.readString(
                Path.of("src/main/java/br/com/bora/service/AsaasSubcontaService.java"),
                StandardCharsets.UTF_8);
        int i = fonte.indexOf("private boolean aprovada(");
        assertTrue(i > 0);
        String metodo = fonte.substring(i, fonte.indexOf("\n    }", i));

        assertTrue(metodo.contains("APPROVED\".equalsIgnoreCase(valor(geral))"),
                "o general continua decidindo -- e o sinal oficial de aprovacao");
        assertTrue(metodo.contains("avisarSeAcontaBancariaEstaPENDENTE"),
                "e o status da conta bancaria tem que ser olhado, nao jogado fora");
    }

    @Test
    void oAVISOnaoBLOQUEIA() throws IOException {
        // Se alguem transformar o aviso em recusa, loja legitimamente aprovada para de receber PIX --
        // e a documentacao nao sustenta esse bloqueio.
        String fonte = Files.readString(
                Path.of("src/main/java/br/com/bora/service/AsaasSubcontaService.java"),
                StandardCharsets.UTF_8);
        int i = fonte.indexOf("private void avisarSeAcontaBancariaEstaPENDENTE(");
        assertTrue(i > 0, "nao achei o aviso");
        String metodo = fonte.substring(i, fonte.indexOf("\n    }", i));

        assertTrue(metodo.contains("log.warn"), "tem que avisar");
        assertFalse(metodo.contains("return false"), "e NAO pode reprovar a conta");
        assertFalse(metodo.contains("throw "), "nem estourar");
    }

    @Test
    void oAVISOsaiSOquandoACONTAnaoESTAaprovada() throws IOException {
        // Avisar em toda loja aprovada faria o aviso deixar de ser lido.
        String fonte = Files.readString(
                Path.of("src/main/java/br/com/bora/service/AsaasSubcontaService.java"),
                StandardCharsets.UTF_8);
        int i = fonte.indexOf("private void avisarSeAcontaBancariaEstaPENDENTE(");
        String metodo = fonte.substring(i, fonte.indexOf("\n    }", i));

        assertTrue(metodo.contains("\"APPROVED\".equalsIgnoreCase(banco)) return"),
                "conta aprovada nao gera aviso");
        assertTrue(metodo.contains("banco.isBlank()"),
                "campo ausente tambem nao gera aviso: nao se avisa sobre o que nao se sabe");
    }
}
