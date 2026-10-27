package br.com.bora.controller;

import br.com.bora.entity.LarguraDeColuna;
import br.com.bora.entity.PagamentoAssinatura;
import br.com.bora.repository.PagamentoAssinaturaRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Registrar a nota da mensalidade com texto longo nao desfaz o registro do pagamento.
 *
 * <h2>O defeito (achado pelo QA em 26/10/2026, medido por mim em 27/10)</h2>
 * <p>O {@code PUT /plataforma/faturamento/{id}/nota} gravava {@code nota_numero} (coluna de 40) e
 * {@code nota_url} (coluna de 300) <b>direto do corpo da requisicao</b>, so com {@code trim()}. E o
 * metodo e {@code @Transactional}: texto longo nao truncava, o Postgres recusava e o registro
 * <b>inteiro</b> era desfeito, com 500 na cara de quem registrou. E o mesmo defeito do item 4.4 da
 * varredura, na mesma tabela.</p>
 *
 * <h2>Por que RECUSAR e nao cortar</h2>
 * <p>O {@code encurtar} e o padrao da casa para texto longo, e e certo para texto <b>descritivo</b> —
 * recado de integracao, descricao de pagamento —, onde perder o fim e melhor que perder tudo. Aqui
 * nao: numero de nota e <b>identificador</b> e url e <b>link</b>. Numero cortado aponta para nota que
 * nao existe; link cortado nao abre. O corte silencioso entregaria, no fechamento do mes, um dado
 * que parece bom e nao e — pior que o erro.</p>
 *
 * <p><b>Nao esta acontecendo:</b> medido na producao em 27/10/2026, existem 2 pagamentos e
 * <b>zero</b> com nota registrada. E a armadilha, nao o estrago.</p>
 */
class AnotaDaMensalidadeNaoDESFAZoPagamentoTest {

    private PagamentoAssinaturaRepository pagamentos;
    private PlataformaController controller;

    /**
     * Monta o controller com o minimo que este endpoint usa.
     *
     * <p>Por reflexao: o {@code PlataformaController} tem muitas dependencias e nenhuma delas entra
     * neste caminho. Construir a lista inteira so para chamar um metodo faria o teste quebrar a cada
     * dependencia nova do controller, por motivo que nao tem a ver com a nota.</p>
     */
    private void montar() {
        pagamentos = mock(PagamentoAssinaturaRepository.class);
        AuthContext ctx = mock(AuthContext.class);
        // SEM `useConstructor()`: o controller nao tem construtor sem argumentos, e a primeira versao
        // deste teste quebrou por isso nos 11 casos. Assim a instancia nasce sem chamar construtor
        // nenhum, com os campos nulos, e eu ponho na mao so os dois que este caminho usa.
        controller = org.mockito.Mockito.mock(PlataformaController.class,
                withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
        trocar("pagamentos", pagamentos);
        trocar("ctx", ctx);
    }

    private void trocar(String campo, Object valor) {
        assertDoesNotThrow(() -> {
            java.lang.reflect.Field f = PlataformaController.class.getDeclaredField(campo);
            f.setAccessible(true);
            f.set(controller, valor);
        });
    }

    private PagamentoAssinatura pagamento() {
        PagamentoAssinatura p = new PagamentoAssinatura();
        p.id = 1L;
        when(pagamentos.findById(1L)).thenReturn(Optional.of(p));
        return p;
    }

    private Map<String, String> corpo(String numero, String url) {
        Map<String, String> m = new HashMap<>();
        m.put("numero", numero);
        if (url != null) m.put("url", url);
        return m;
    }

    // ---------------------------------------------------------------- a premissa
    @Test
    void PREMISSA_aNOTAnormalEHgravada() {
        // Sem isto, um endpoint que recusa tudo faria os testes abaixo passar por motivo errado.
        montar();
        PagamentoAssinatura p = pagamento();

        controller.registrarNota(1L, corpo("2026/000123", "https://nfse.prefeitura.gov.br/nota/123"));

        assertEquals("2026/000123", p.notaNumero);
        assertEquals("https://nfse.prefeitura.gov.br/nota/123", p.notaUrl);
        assertNotNull(p.notaEmitidaEm);
        verify(pagamentos).save(p);
    }

    // ---------------------------------------------------------------- o conserto
    @Test
    void numeroLONGOehRECUSADOcom400_eNAOgravaNADA() {
        montar();
        PagamentoAssinatura p = pagamento();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.registrarNota(1L, corpo("9".repeat(41), null)));

        assertEquals(400, e.getStatusCode().value(),
                "sem o 400 isto virava 500 do banco, e o registro do pagamento era desfeito inteiro");
        assertNull(p.notaNumero);
        verify(pagamentos, never()).save(any());
    }

    @Test
    void linkLONGOehRECUSADOcom400() {
        montar();
        PagamentoAssinatura p = pagamento();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.registrarNota(1L, corpo("2026/1", "https://x.gov.br/" + "a".repeat(300))));

        assertEquals(400, e.getStatusCode().value());
        assertNull(p.notaNumero, "o numero tambem nao pode ter sido gravado");
        verify(pagamentos, never()).save(any());
    }

    @Test
    void aMENSAGEMdizOtamanhoEoLIMITE() {
        // Recusar sem dizer quanto cabe faz a pessoa tentar de novo no escuro.
        montar();
        pagamento();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.registrarNota(1L, corpo("9".repeat(50), null)));

        assertTrue(e.getReason().contains("50"), "falta o tamanho que veio: " + e.getReason());
        assertTrue(e.getReason().contains("40"), "falta o limite: " + e.getReason());
    }

    @Test
    void aMENSAGEMdoNUMEROsugereOerroPROVAVEL() {
        // Numero de NFS-e nao chega perto de 40: quem passa disso colou outra coisa no campo.
        montar();
        pagamento();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.registrarNota(1L, corpo("9".repeat(41), null)));

        assertTrue(e.getReason().toLowerCase().contains("link")
                        || e.getReason().toLowerCase().contains("xml"),
                "a mensagem devia dizer o engano provavel: " + e.getReason());
    }

    // ---------------------------------------------------------------- os limites exatos
    @Test
    void oTAMANHOexatoDOlimiteCABE() {
        // 40 cabe, 41 nao. Errar por um aqui recusaria nota valida.
        montar();
        PagamentoAssinatura p = pagamento();

        controller.registrarNota(1L, corpo("9".repeat(LarguraDeColuna.PAGAMENTO_NOTA_NUMERO), null));

        assertEquals(LarguraDeColuna.PAGAMENTO_NOTA_NUMERO, p.notaNumero.length());
    }

    @Test
    void oLINKdoTAMANHOexatoCABE() {
        montar();
        PagamentoAssinatura p = pagamento();

        controller.registrarNota(1L, corpo("2026/1", "h".repeat(LarguraDeColuna.PAGAMENTO_NOTA_URL)));

        assertEquals(LarguraDeColuna.PAGAMENTO_NOTA_URL, p.notaUrl.length());
    }

    // ---------------------------------------------------------------- o que continua valendo
    @Test
    void numeroVAZIOcontinuaRECUSADO() {
        montar();
        pagamento();

        assertThrows(ResponseStatusException.class, () -> controller.registrarNota(1L, corpo("  ", null)));
        assertThrows(ResponseStatusException.class, () -> controller.registrarNota(1L, corpo(null, null)));
    }

    @Test
    void semURLcontinuaGRAVANDOsoOnumero() {
        // A url e opcional: a nota pode existir sem link publicado.
        montar();
        PagamentoAssinatura p = pagamento();

        controller.registrarNota(1L, corpo("2026/000123", null));

        assertEquals("2026/000123", p.notaNumero);
        assertNull(p.notaUrl);
    }

    @Test
    void urlEMbrancoVIRAnulo_eNAOtextoVAZIO() {
        montar();
        PagamentoAssinatura p = pagamento();

        controller.registrarNota(1L, corpo("2026/1", "   "));

        assertNull(p.notaUrl, "string vazia na coluna faria a tela desenhar um link que nao vai a parte nenhuma");
    }

    @Test
    void oNUMEROehAPARADO() {
        montar();
        PagamentoAssinatura p = pagamento();

        controller.registrarNota(1L, corpo("  2026/000123  ", null));

        assertEquals("2026/000123", p.notaNumero);
    }
}
