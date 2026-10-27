package br.com.bora.service;

import br.com.bora.entity.Insumo;
import br.com.bora.entity.ProdutoInsumo;
import br.com.bora.repository.InsumoRepository;
import br.com.bora.repository.ProdutoInsumoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Insumo nao vai a saldo negativo calado.
 *
 * <h2>O defeito (achado pelo QA em 26/10/2026)</h2>
 * <p>No balcao e no marketplace a venda passa mesmo sem saldo, de proposito: o operador esta com o
 * copo na mao, e travar a venda com o cliente na frente e pior que o saldo negativo — que pelo menos
 * denuncia que a contagem esta errada. Essa escolha esta documentada no proprio
 * {@code consumirFicha}.</p>
 *
 * <p>O que faltava era o aviso. O produto ja tinha o dele
 * ({@code PedidoService.avisarSeVaiFicarNegativo}) e o adicional tambem; <b>a ficha tecnica nao</b>.
 * O {@code mexerNoEstoque} soma o delta sem piso nenhum, e o saldo do insumo ia a negativo sem uma
 * linha de log. E insumo e justamente o que o lojista NAO confere olhando a prateleira: se mede em
 * quilos, dentro de um pote, e so aparece na contagem do mes.</p>
 *
 * <p><b>Medido na producao em 26/10/2026:</b> existe 1 insumo com saldo negativo hoje, e nada
 * avisou.</p>
 *
 * <h2>O segundo achado: o logger</h2>
 * <p>Os avisos existentes estavam em loggers diferentes — o do produto em {@code AUDITORIA}, o do
 * adicional no logger da classe. Procurar "AUDITORIA" achava um e perdia os outros. Os tres agora
 * saem no mesmo.</p>
 */
class OinsumoNaoVAIaNEGATIVOcaladoTest {

    private static final Long LOJA = 1L;
    private static final Long PRODUTO = 11L;
    private static final Long INSUMO = 50L;

    private InsumoRepository insumos;
    private ProdutoInsumoRepository fichas;
    private InsumoService service;

    private ch.qos.logback.classic.Logger auditoria;
    private ListAppender<ILoggingEvent> ouvinte;

    @BeforeEach
    void montar() {
        insumos = mock(InsumoRepository.class);
        fichas = mock(ProdutoInsumoRepository.class);
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        service = new InsumoService(insumos, fichas, mock(ProdutoRepository.class),
                mock(br.com.bora.repository.ComplementoItemRepository.class), ctx);

        // O aviso E o conserto: sem ler o log, este teste mediria nada.
        auditoria = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("AUDITORIA");
        ouvinte = new ListAppender<>();
        ouvinte.start();
        auditoria.addAppender(ouvinte);
    }

    @AfterEach
    void desmontar() {
        auditoria.detachAppender(ouvinte);
    }

    private void insumoCom(String estoque) {
        Insumo i = new Insumo();
        i.id = INSUMO;
        i.lojaId = LOJA;
        i.nome = "Polpa de acai";
        i.unidade = "kg";
        i.custo = new BigDecimal("20.00");
        i.estoque = estoque == null ? null : new BigDecimal(estoque);
        when(insumos.findByIdAndLojaId(INSUMO, LOJA)).thenReturn(Optional.of(i));
    }

    private void fichaCom(String porUnidade) {
        ProdutoInsumo pi = new ProdutoInsumo();
        pi.lojaId = LOJA;
        pi.produtoId = PRODUTO;
        pi.insumoId = INSUMO;
        pi.quantidade = new BigDecimal(porUnidade);
        when(fichas.findByLojaIdAndProdutoId(LOJA, PRODUTO)).thenReturn(List.of(pi));
    }

    private List<String> avisos() {
        return ouvinte.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // ---------------------------------------------------------------- o conserto
    @Test
    void saldoQUEvaiFICARnegativoFICAescrito() {
        insumoCom("0.200");
        fichaCom("0.300");

        service.consumirFicha(LOJA, PRODUTO, 1);

        List<String> m = avisos();
        assertEquals(1, m.size(), "o saldo ia a negativo sem uma linha de log: " + m);
        assertTrue(m.get(0).contains("Polpa de acai"), "o aviso tem de nomear o insumo: " + m.get(0));
        assertTrue(m.get(0).contains("negativo"), m.get(0));
    }

    @Test
    void oAVISOdizQUANTOtinha_eNAOsoQUEfaltou() {
        // Sem o saldo de antes, o lojista nao sabe de quanto e o erro de contagem.
        insumoCom("0.200");
        fichaCom("0.300");

        service.consumirFicha(LOJA, PRODUTO, 2);

        String m = avisos().get(0);
        assertTrue(m.contains("0.600"), "falta quanto a venda consumiu: " + m);
        assertTrue(m.contains("0.200"), "falta o saldo que existia: " + m);
        assertTrue(m.contains("kg"), "falta a unidade -- 0,6 de que? " + m);
    }

    @Test
    void aVENDAcontinuaPASSANDO() {
        // Avisar, nao recusar: o operador esta com o copo na mao. Trocar aviso por recusa aqui seria
        // um defeito pior que o que estou consertando.
        insumoCom("0.000");
        fichaCom("0.300");

        assertDoesNotThrow(() -> service.consumirFicha(LOJA, PRODUTO, 1));
        verify(insumos).mexerNoEstoque(eq(INSUMO), eq(LOJA), eq(new BigDecimal("0.300").negate()));
    }

    // ---------------------------------------------------------------- quando NAO avisa
    @Test
    void saldoQUEcobreNAOavisa() {
        // Aviso que aparece sempre nao e aviso: vira ruido e o lojista para de ler.
        insumoCom("100.000");
        fichaCom("0.300");

        service.consumirFicha(LOJA, PRODUTO, 1);

        assertTrue(avisos().isEmpty(), "avisou sem motivo: " + avisos());
    }

    @Test
    void saldoEXATOnaoAVISA() {
        // Zerar nao e ficar negativo. A venda que consome a ultima unidade esta certa.
        insumoCom("0.300");
        fichaCom("0.300");

        service.consumirFicha(LOJA, PRODUTO, 1);

        assertTrue(avisos().isEmpty(), "zerar o estoque nao e erro de contagem: " + avisos());
    }

    @Test
    void insumoQUEnaoCONTROLAestoqueNAOavisa() {
        // Saldo nulo e escolha do lojista: ele decidiu nao controlar aquele insumo.
        insumoCom(null);
        fichaCom("0.300");

        service.consumirFicha(LOJA, PRODUTO, 1);

        assertTrue(avisos().isEmpty(), "insumo sem controle de estoque nao tem saldo para ficar "
                + "negativo: " + avisos());
    }

    @Test
    void oCARDAPIOnaoAVISA_eleRECUSA() {
        // No cardapio publico quem decide e o banco, e a venda nem acontece. Avisar ali seria
        // anunciar um negativo que nunca existiu.
        insumoCom("0.100");
        fichaCom("0.300");
        when(insumos.consumirSeTiver(anyLong(), anyLong(), any())).thenReturn(0);

        assertThrows(InsumoService.SemInsumo.class, () -> service.consumirFicha(LOJA, PRODUTO, 1, true));
        assertTrue(avisos().isEmpty(), "o cardapio recusa, nao deixa negativo: " + avisos());
    }

    // ---------------------------------------------------------------- os tres avisos, uma busca
    @Test
    void osTRESavisosDEsaldoNEGATIVOsaemNOmesmoLUGAR() {
        // Com loggers diferentes, procurar "AUDITORIA" achava um e perdia dois.
        String[][] onde = {
            { "src/main/java/br/com/bora/service/InsumoService.java", "ficha e adicional" },
            { "src/main/java/br/com/bora/service/PedidoService.java", "produto" },
        };
        for (String[] alvo : onde) {
            String fonte = assertDoesNotThrow(() -> java.nio.file.Files.readString(
                    java.nio.file.Path.of(alvo[0]), java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(fonte.contains("AUDITORIA.warn(") && fonte.contains("negativo"),
                    "o aviso de saldo negativo de " + alvo[1] + " saiu do AUDITORIA");
        }
        // E o da ficha, especificamente, tem de estar lá -- é o que não existia.
        String insumoService = assertDoesNotThrow(() -> java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/br/com/bora/service/InsumoService.java"),
                java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(insumoService.contains("avisarSeOINSUMOvaiFicarNegativo("),
                "o aviso da ficha tecnica desapareceu");
    }
}
