package br.com.bora.controller;

import br.com.bora.entity.ConfiguracaoLoja;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Produto;
import br.com.bora.repository.*;
import br.com.bora.security.RegraDeAcesso;
import br.com.bora.service.ComplementoService;
import br.com.bora.service.InsumoService;
import br.com.bora.service.OperacaoService;
import br.com.bora.service.PixService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Loja suspensa sai do ar <b>inteira</b> — inclusive a logo, o banner e as fotos dos produtos.
 *
 * <h2>O defeito que isto fecha (item 8.7)</h2>
 * <p>{@code imagemDaMarca} e {@code imagemDoProduto} eram os <b>únicos</b> endpoints públicos que não
 * chamavam {@code lojaAtiva}. Loja suspensa, arquivada ou com o prazo de pagamento vencido tinha o
 * cardápio devolvendo 404 e a <b>marca dela continuava sendo servida</b> pela infraestrutura da
 * plataforma. Duas coisas erradas nisso: a incoerência (uma porta fechada, a outra aberta, o que
 * deixa página meio carregada em quem tem o link antigo) e a plataforma seguir hospedando a imagem de
 * quem não paga.</p>
 */
class LojaSuspensaSaiDoArInteiraTest {

    private static final Long LOJA = 1L;

    private LojaRepository lojas;
    private ProdutoRepository produtos;
    private ConfiguracaoLojaRepository configuracoes;
    private PublicController controller;

    @BeforeEach
    void montar() {
        lojas = mock(LojaRepository.class);
        produtos = mock(ProdutoRepository.class);
        configuracoes = mock(ConfiguracaoLojaRepository.class);
        OperacaoService operacao = mock(OperacaoService.class);
        when(operacao.abertaAgora(anyLong())).thenReturn(true);

        controller = new PublicController(lojas, produtos, mock(PedidoRepository.class),
                mock(PedidoItemRepository.class), mock(IntegracaoCanalRepository.class),
                mock(PixService.class), mock(ComplementoGrupoRepository.class),
                mock(ComplementoItemRepository.class), mock(ComplementoService.class),
                mock(CupomRepository.class),
                new br.com.bora.service.DescontoService(mock(CupomRepository.class),
                        mock(PromocaoRepository.class)),
                new br.com.bora.security.FreioDoCardapio(0, 0),
                mock(br.com.bora.service.FidelidadeService.class), operacao,
                mock(TaxaEntregaRepository.class), mock(InsumoService.class),
                mock(br.com.bora.service.EstornoDaVendaService.class),
                // corte por assinatura LIGADO: e o estado que faz o prazo vencido valer
                new RegraDeAcesso(true), configuracoes,
                mock(br.com.bora.service.RepetirPedidoService.class),
                mock(br.com.bora.service.CategoriaOrdemService.class),
                mock(PlatformTransactionManager.class), false);

        // uma foto de verdade, para o unico motivo de 404 ser a loja
        ConfiguracaoLoja cfg = new ConfiguracaoLoja();
        cfg.logoUrl = pngDeVerdade();
        cfg.bannerUrl = pngDeVerdade();
        when(configuracoes.findByLojaId(LOJA)).thenReturn(Optional.of(cfg));

        Produto p = new Produto();
        p.id = 10L;
        p.lojaId = LOJA;
        p.imagemUrl = pngDeVerdade();
        when(produtos.findByIdAndLojaId(10L, LOJA)).thenReturn(Optional.of(p));
    }

    private static String pngDeVerdade() {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
    }

    private void loja(boolean ativo, OffsetDateTime acessoAte) {
        Loja l = new Loja();
        l.id = LOJA;
        l.ativo = ativo;
        l.acessoAte = acessoAte;
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));
    }

    private int statusDaLogo() {
        try {
            controller.imagemDaMarca(LOJA, "logo", null);
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    private int statusDaFoto() {
        try {
            controller.imagemDoProduto(LOJA, 10L, null);
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    // =============================================================== a loja que esta bem

    @Test
    void lojaATIVA_serveAmarcaEaFOTO() {
        loja(true, OffsetDateTime.now().plusDays(30));

        assertEquals(200, statusDaLogo(), "loja ativa tem que continuar com a marca no ar");
        assertEquals(200, statusDaFoto());
    }

    @Test
    void lojaATIVAsemPRAZOgravado_continuaNOar() {
        // `acesso_ate` nulo e o estado de quem nunca teve prazo definido: nao pode ser lido como
        // vencido, senao o conserto derruba loja que esta pagando.
        loja(true, null);

        assertEquals(200, statusDaLogo());
        assertEquals(200, statusDaFoto());
    }

    // =============================================================== a loja que saiu

    @Test
    void lojaDESATIVADA_naoSERVEmaisAmarca() {
        loja(false, OffsetDateTime.now().plusDays(30));

        assertEquals(404, statusDaLogo(),
                "o cardapio dela ja devolvia 404; a marca continuava no ar (item 8.7)");
        assertEquals(404, statusDaFoto());
    }

    @Test
    void lojaComPRAZOvencido_naoSERVEmaisAmarca() {
        loja(true, OffsetDateTime.now().minusDays(1));

        assertEquals(404, statusDaLogo());
        assertEquals(404, statusDaFoto());
    }

    @Test
    void lojaQUEnaoEXISTE_da404_eNAOerroDEservidor() {
        when(lojas.findById(LOJA)).thenReturn(Optional.empty());

        assertEquals(404, statusDaLogo());
        assertEquals(404, statusDaFoto());
    }

    // =============================================================== a coerencia que o item cobra

    @Test
    void aMARCAeOcARDAPIOdaoAmesmaRESPOSTA() {
        // O ponto do item 8.7 nao e cada endpoint isolado: e os dois concordarem. Pagina meio
        // carregada (logo aparece, cardapio nao) e pior que pagina que nao carrega.
        loja(false, null);

        int cardapio;
        try {
            controller.cardapio(LOJA);
            cardapio = 200;
        } catch (ResponseStatusException e) {
            cardapio = e.getStatusCode().value();
        }

        assertEquals(cardapio, statusDaLogo(), "a logo tem que responder igual ao cardapio");
        assertEquals(cardapio, statusDaFoto(), "e a foto do produto tambem");
    }
}
