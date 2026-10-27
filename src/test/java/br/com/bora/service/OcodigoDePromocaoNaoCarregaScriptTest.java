package br.com.bora.service;

import br.com.bora.entity.Promocao;
import br.com.bora.repository.PromocaoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Codigo de promocao nao carrega script — nem apostrofe, nem tag.
 *
 * <h2>O defeito (achado pelo QA em 26/10/2026)</h2>
 * <p>O {@code codigo} era texto livre, gravado sem validacao nenhuma. A tela de Promocoes o desenha
 * dentro de uma string JS: {@code onclick="__encerrar(id, '...')"} — e o {@code esc} daquele arquivo
 * escapava {@code & < > "} e <b>nao escapava apostrofe</b>.</p>
 *
 * <p>Medido pelo agente: um GERENTE criava a promocao com {@code codigo = X'+alert(1)+'} (13
 * caracteres, coluna de 40) e quem abrisse Promocoes e clicasse em "Encerrar" executava o codigo do
 * gerente na origem do painel. A CSP de producao tem {@code script-src 'self' 'unsafe-inline'},
 * entao inline executa, e o token do painel mora no {@code localStorage}.</p>
 *
 * <p>As vitimas naturais sao o ADMINISTRADOR_LOJA e o <b>suporte da plataforma</b> operando dentro
 * da loja por "Entrar na loja" — nesse caso o atacante leva um token de ADMINISTRADOR_BORA, a conta
 * que suspende, arquiva e troca a credencial de todos os marketplaces.</p>
 *
 * <h2>Por que as duas pontas</h2>
 * <p>O {@code esc} da tela foi corrigido no mesmo commit. Escapar na tela protege o render de hoje;
 * recusar aqui protege o <b>proximo</b> lugar que mostrar esse valor — relatorio, mensagem de
 * WhatsApp, nota. Uma ponta so deixa a divida esperando.</p>
 */
class OcodigoDePromocaoNaoCarregaScriptTest {

    private static final Long LOJA = 1L;

    private PromocaoRepository repo;
    private PromocaoService service;

    @BeforeEach
    void montar() {
        repo = mock(PromocaoRepository.class);
        when(repo.existsByLojaIdAndAtivaTrue(anyLong())).thenReturn(false);
        when(repo.save(any(Promocao.class))).thenAnswer(i -> i.getArgument(0));
        service = new PromocaoService(repo);
    }

    private Promocao com(String codigo) {
        Promocao p = new Promocao();
        p.setDescricao("15% em tudo");
        p.setCodigo(codigo);
        return p;
    }

    private ResponseStatusException recusa(String codigo) {
        return assertThrows(ResponseStatusException.class, () -> service.criarManual(LOJA, com(codigo)));
    }

    // ---------------------------------------------------------------- o ataque
    @Test
    void aAPOSTROFEehRECUSADA_oCASOqueOqaMEDIU() {
        ResponseStatusException e = recusa("X'+alert(1)+'");
        assertEquals(400, e.getStatusCode().value());
        verify(repo, never()).save(any());
    }

    @Test
    void tagHTMLehRECUSADA() {
        recusa("<img src=x onerror=alert(1)>");
        verify(repo, never()).save(any());
    }

    @Test
    void aspasEcolchetesTAMBEMsaoRECUSADOS() {
        for (String ruim : new String[] { "A\"B", "A<B", "A>B", "A&B", "A;B", "A B", "A/B", "A\\B" }) {
            assertThrows(ResponseStatusException.class,
                    () -> service.criarManual(LOJA, com(ruim)), "aceitou o codigo: " + ruim);
        }
    }

    @Test
    void aMENSAGEMdizOqueVALE_eNAOsoQUErecusou() {
        // Recusar sem dizer o que serve faz o lojista tentar as mesmas coisas de novo.
        ResponseStatusException e = recusa("PROMO 10");
        assertTrue(e.getReason() != null && e.getReason().toLowerCase().contains("letras"),
                "a mensagem precisa dizer o conjunto permitido: " + e.getReason());
    }

    @Test
    void codigoLONGOehRECUSADO_aColunaEHde40() {
        recusa("A".repeat(41));
        verify(repo, never()).save(any());
    }

    // ---------------------------------------------------------------- o que tem de continuar valendo
    @Test
    void codigoNORMALehACEITO() {
        Promocao salva = service.criarManual(LOJA, com("BEMVINDO10"));
        assertEquals("BEMVINDO10", salva.getCodigo());
        verify(repo).save(any());
    }

    @Test
    void pontoHIFENeSUBLINHADOvalem() {
        for (String bom : new String[] { "BLACK-FRIDAY", "NATAL_2026", "v1.0", "a", "ABC123" }) {
            assertDoesNotThrow(() -> service.criarManual(LOJA, com(bom)), "recusou o codigo: " + bom);
        }
    }

    @Test
    void oCODIGOehAPARADO() {
        // Espaco colado na ponta e facil de acontecer no copia-e-cola, e nao e motivo para recusar.
        assertEquals("PROMO10", service.criarManual(LOJA, com("  PROMO10  ")).getCodigo());
    }

    @Test
    void promocaoSEMcodigoCONTINUAvalendo() {
        // Promocao sem codigo existe: e a que vale para todo mundo, sem o cliente digitar nada.
        assertDoesNotThrow(() -> service.criarManual(LOJA, com(null)));
        assertDoesNotThrow(() -> service.criarManual(LOJA, com("   ")));
    }

    @Test
    void aGUARDAdeUMApromocaoATIVAcontinuaDEpe() {
        // Nao e deste conserto, mas esta no mesmo metodo: travando para eu nao ter afrouxado.
        when(repo.existsByLojaIdAndAtivaTrue(LOJA)).thenReturn(true);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.criarManual(LOJA, com("OUTRA")));
        assertEquals(409, e.getStatusCode().value());
    }

    @Test
    void aDESCRICAOcontinuaOBRIGATORIA() {
        Promocao p = new Promocao();
        p.setCodigo("PROMO10");
        assertThrows(ResponseStatusException.class, () -> service.criarManual(LOJA, p));
    }
}
