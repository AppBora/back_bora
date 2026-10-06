package br.com.bora.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Foto de produto nao pode virar pagina.
 *
 * <p>O endereco da imagem servia o tipo que estava gravado no proprio {@code data:}, sem lista de
 * permitidos. Um lojista — e qualquer pessoa pode criar uma loja pelo cadastro aberto — salvava
 * {@code data:text/html;base64,...} na foto de um produto e mandava a vitima abrir o endereco da
 * imagem. O navegador executava aquilo como PAGINA, no mesmo dominio do painel, e o script lia o
 * token do {@code localStorage}. Com a vitima sendo o suporte da plataforma, o atacante levava
 * acesso a todas as lojas.</p>
 *
 * <p>SVG fica de fora da lista de proposito: SVG executa script.</p>
 */
class ImagemNaoViraPaginaTest {

    @SuppressWarnings("unchecked")
    private ResponseEntity<byte[]> servir(String dado) throws Exception {
        Method m = PublicController.class.getDeclaredMethod("servirImagem", String.class, String.class);
        m.setAccessible(true);
        try {
            return (ResponseEntity<byte[]>) m.invoke(semDependencias(), dado, null);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw e;
        }
    }

    /** servirImagem nao toca em nenhum colaborador; basta uma instancia sem inicializar. */
    private PublicController semDependencias() throws Exception {
        return (PublicController) sun.misc.Unsafe.class.cast(campoUnsafe()).allocateInstance(PublicController.class);
    }

    private Object campoUnsafe() throws Exception {
        var f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return f.get(null);
    }

    private static String dataUri(String tipo, String conteudo) {
        return "data:" + tipo + ";base64," + Base64.getEncoder().encodeToString(conteudo.getBytes());
    }

    @Test
    void htmlDisfarcadoDeFoto_naoEhServido() {
        var e = assertThrows(ResponseStatusException.class,
                () -> servir(dataUri("text/html", "<script>roubaToken()</script>")));
        assertEquals(404, e.getStatusCode().value(),
                "HTML na foto do produto nao pode sair do servidor");
    }

    @Test
    void svgNaoEntra_porqueSvgExecutaScript() {
        var e = assertThrows(ResponseStatusException.class,
                () -> servir(dataUri("image/svg+xml", "<svg onload=\"roubaToken()\"/>")));
        assertEquals(404, e.getStatusCode().value());
    }

    @Test
    void fotoDeVerdade_continuaFuncionando() throws Exception {
        var r = servir(dataUri("image/png", "nao e png de verdade, mas o tipo e o que importa aqui"));

        assertEquals(200, r.getStatusCode().value());
        assertEquals("image/png", String.valueOf(r.getHeaders().getContentType()));
        assertEquals("nosniff", r.getHeaders().getFirst("X-Content-Type-Options"),
                "o navegador nao pode adivinhar o tipo");
        assertNotNull(r.getHeaders().getFirst("Content-Security-Policy"),
                "cinto e suspensorio: nada daqui pode executar");
    }

    @Test
    void tipoComEspacoOuMaiuscula_naoEscapaDaLista() throws Exception {
        assertEquals(200, servir(dataUri(" IMAGE/PNG ", "x")).getStatusCode().value(),
                "variacao de caixa e espaco e a mesma foto, tem que passar");
        assertThrows(ResponseStatusException.class, () -> servir(dataUri(" TEXT/HTML ", "<script>")),
                "e variacao de caixa nao pode ser usada para escapar da lista");
    }
}
