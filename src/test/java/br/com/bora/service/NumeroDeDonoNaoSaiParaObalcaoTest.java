package br.com.bora.service;

import br.com.bora.repository.*;
import br.com.bora.controller.VendasController;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Faturamento e custo não saem para quem atende o balcão.
 *
 * <h2>O defeito que isto fecha (item 8.3)</h2>
 * <p>Duas portas abertas. O <b>termômetro de vendas</b> (`/api/vendas/termometro`) devolve o
 * faturamento das últimas 24h e a média esperada, em reais, e não tinha checagem de papel nenhuma —
 * e o Dashboard, que o balconista abre, desenha os dois valores em destaque. E a <b>lista de
 * produtos</b> devolvia o {@code custo} de cada item: a tela não o desenha, mas ele chega no
 * navegador, então bastam as ferramentas do desenvolvedor para ler o custo de todo o cardápio — e com
 * ele a margem da loja.</p>
 *
 * <h2>Por que o conserto é diferente nos dois</h2>
 * <p>No termômetro a <b>ação inteira</b> é número de dono: bloqueia (403). Na lista de produtos, não:
 * a <b>Frente de Caixa é do balconista de propósito</b> e precisa de nome, preço e estoque. Barrar a
 * consulta tiraria o PDV de quem trabalha nele. Então ali se <b>omite o campo</b>, não se nega a
 * tela — e há teste cobrando que o resto da lista continua chegando.</p>
 */
class NumeroDeDonoNaoSaiParaObalcaoTest {

    private static final Long LOJA = 18L;

    /**
     * O {@link AuthContext} de VERDADE, com a sessão posta no contexto do Spring Security.
     *
     * <p>A primeira versão deste teste <b>mockava</b> o AuthContext e pedia
     * {@code thenCallRealMethod} nos métodos de papel. Não funcionou — {@code requirePapel} é varargs
     * e o mock não repassava a chamada —, e o efeito foi o pior possível: o mock simplesmente não
     * estourava, então <b>cinco testes passaram verde medindo nada</b>. Com a classe real, o que está
     * sob teste é o que roda em produção.</p>
     */
    private AuthContext ctx(String papel) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new BoraPrincipal(7L, LOJA, papel, "quem@loja.com"), null, List.of()));
        return new AuthContext();
    }

    @AfterEach
    void limparSessao() {
        SecurityContextHolder.clearContext();
    }

    // =============================================================== o termometro

    private ResponseStatusException termometroCom(String papel) {
        AuthContext ctx = ctx(papel);
        VendasController c = new VendasController(mock(VendasSaudeService.class), ctx);
        try {
            c.termometro();
            return null;
        } catch (ResponseStatusException e) {
            return e;
        }
    }

    @Test
    void oOPERADORnaoVEoFATURAMENTOdaLOJA() {
        ResponseStatusException erro = termometroCom("OPERADOR");

        assertNotNull(erro, "o termometro devolve faturamento em reais: operador nao pode");
        assertEquals(403, erro.getStatusCode().value());
    }

    @Test
    void oENTREGADORtambemNAO() {
        assertNotNull(termometroCom("ENTREGADOR"));
    }

    @Test
    void oGERENTEeOdONOveem() {
        assertNull(termometroCom("GERENTE"), "gerente cuida de numero da loja");
        assertNull(termometroCom("ADMINISTRADOR_LOJA"));
    }

    @Test
    void oSUPORTEdaPLATAFORMAve() {
        // ADMINISTRADOR_BORA atende o lojista: se nao ve o numero, nao ajuda.
        assertNull(termometroCom("ADMINISTRADOR_BORA"));
    }

    // =============================================================== o custo do produto

    private List<br.com.bora.dto.ProdutoResumo> produtosCom(String papel) {
        ProdutoRepository repo = mock(ProdutoRepository.class);
        List<Object[]> projecao = new java.util.ArrayList<>();
        projecao.add(new Object[]{10L, "Acai 500ml", "Acai", new BigDecimal("18.00"),
                new BigDecimal("7.50"), 20, 5, true, false, "7470"});
        when(repo.resumoDaLoja(anyLong())).thenReturn(projecao);
        ProdutoService s = new ProdutoService(repo, mock(ProdutoInsumoRepository.class),
                mock(ComplementoGrupoRepository.class), mock(ComplementoItemRepository.class),
                ctx(papel));
        return s.listar();
    }

    @Test
    void oOPERADORrecebeAlistaSEMoCUSTO() {
        var lista = produtosCom("OPERADOR");

        assertEquals(1, lista.size(), "a lista NAO pode ser negada: o PDV e dele");
        assertNull(lista.get(0).custo(), "o custo nao pode chegar no navegador dele");
    }

    @Test
    void masRECEBEtudoOqueOpdvPRECISA() {
        // O outro lado, e o que mais importa: omitir o custo nao pode quebrar a Frente de Caixa.
        var p = produtosCom("OPERADOR").get(0);

        assertEquals("Acai 500ml", p.nome());
        assertEquals(0, new BigDecimal("18.00").compareTo(p.preco()), "preco de venda ele precisa");
        assertEquals(20, p.estoque());
        assertEquals("Acai", p.categoria());
        assertEquals(Boolean.TRUE, p.ativo());
        assertEquals("7470", p.codigoExterno());
    }

    @Test
    void oGERENTErecebeOcUSTO() {
        assertEquals(0, new BigDecimal("7.50").compareTo(produtosCom("GERENTE").get(0).custo()));
    }

    @Test
    void oDONOrecebeOcUSTO() {
        assertEquals(0, new BigDecimal("7.50")
                .compareTo(produtosCom("ADMINISTRADOR_LOJA").get(0).custo()));
    }

    // =============================================================== os insumos e a ficha

    private ResponseStatusException insumosCom(String papel) {
        InsumoRepository insumos = mock(InsumoRepository.class);
        when(insumos.findByLojaIdOrderByNomeAsc(anyLong())).thenReturn(List.of());
        InsumoService s = new InsumoService(insumos, mock(ProdutoInsumoRepository.class),
                mock(ProdutoRepository.class), mock(ComplementoItemRepository.class), ctx(papel));
        try {
            s.listar();
            return null;
        } catch (ResponseStatusException e) {
            return e;
        }
    }

    @Test
    void oOPERADORnaoLISTAinsumos() {
        // Insumo e onde o custo da loja mora de verdade, e a lista inteira e numero de dono: nome e
        // unidade de insumo nao servem ao balcao. Aqui bloqueia, em vez de omitir campo.
        ResponseStatusException erro = insumosCom("OPERADOR");

        assertNotNull(erro);
        assertEquals(403, erro.getStatusCode().value());
    }

    @Test
    void oGERENTElistaINSUMOS() {
        assertNull(insumosCom("GERENTE"));
    }

    private ResponseStatusException fichaCom(String papel) {
        InsumoRepository insumos = mock(InsumoRepository.class);
        when(insumos.findByLojaIdOrderByNomeAsc(anyLong())).thenReturn(List.of());
        ProdutoInsumoRepository fichas = mock(ProdutoInsumoRepository.class);
        when(fichas.findByLojaIdAndProdutoId(anyLong(), anyLong())).thenReturn(List.of());
        InsumoService s = new InsumoService(insumos, fichas,
                mock(ProdutoRepository.class), mock(ComplementoItemRepository.class), ctx(papel));
        try {
            s.ficha(10L);
            return null;
        } catch (ResponseStatusException e) {
            return e;
        }
    }

    @Test
    void oOPERADORnaoABREaFICHAtecnica() {
        // A ficha traz o custo de cada insumo do produto -- e com a ficha de todos os produtos se
        // monta o CMV da loja inteira.
        ResponseStatusException erro = fichaCom("OPERADOR");

        assertNotNull(erro);
        assertEquals(403, erro.getStatusCode().value());
    }

    @Test
    void oGERENTEabreAfICHA() {
        assertNull(fichaCom("GERENTE"));
    }
}
