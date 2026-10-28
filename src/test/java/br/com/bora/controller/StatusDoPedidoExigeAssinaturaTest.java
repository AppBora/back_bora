package br.com.bora.controller;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.service.RepetirPedidoService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * O status do pedido só responde a quem tem o link assinado.
 *
 * <p><b>O furo que isto fecha:</b> {@code GET /public/loja/{id}/pedido/{id}/status} nao pedia nada. Os
 * ids de pedido sao {@code bigserial} — sequenciais e globais, nao por loja. Quem varresse
 * {@code 1..N} lia, de QUALQUER pedido de QUALQUER loja: o codigo, o status, o horario de criacao e o
 * {@code motivoCancelamento}, que e texto livre digitado pela equipe da loja ("cliente nao atende",
 * "endereco nao encontrado"). Dava para medir o movimento diario de um concorrente contando pedidos.</p>
 *
 * <p>A assinatura reusa o HMAC do "repetir pedido", que ja existia. Aqui o {@code RepetirPedidoService}
 * e o de VERDADE, com uma chave fixa de teste: assinatura e a regra em jogo, e mock dela provaria
 * apenas que alguem chamou alguem.</p>
 */
class StatusDoPedidoExigeAssinaturaTest {

    private static final Long LOJA = 18L;
    private static final Long PEDIDO = 4242L;

    private final PedidoRepository pedidos = mock(PedidoRepository.class);

    /** O servico de assinatura e o DE VERDADE: assinatura e a regra em jogo aqui. */
    private final RepetirPedidoService repetir = new RepetirPedidoService(
            mock(PedidoRepository.class), mock(br.com.bora.repository.PedidoItemRepository.class),
            mock(br.com.bora.repository.ProdutoRepository.class),
            mock(br.com.bora.repository.ComplementoGrupoRepository.class),
            mock(br.com.bora.repository.ComplementoItemRepository.class),
            "uma-chave-de-teste-com-mais-de-32-bytes-aqui-ok");

    private final PublicController controller = new PublicController(
            mock(LojaRepository.class), mock(br.com.bora.repository.ProdutoRepository.class), pedidos,
            mock(br.com.bora.repository.PedidoItemRepository.class),
            mock(br.com.bora.repository.IntegracaoCanalRepository.class),
            mock(br.com.bora.service.PixService.class),
            mock(br.com.bora.repository.ComplementoGrupoRepository.class),
            mock(br.com.bora.repository.ComplementoItemRepository.class),
            mock(br.com.bora.service.ComplementoService.class),
            mock(br.com.bora.repository.CupomRepository.class),
            mock(br.com.bora.service.DescontoService.class),
            new br.com.bora.security.FreioDoCardapio(0, 0),
            mock(br.com.bora.service.FidelidadeService.class),
            mock(br.com.bora.service.OperacaoService.class),
            mock(br.com.bora.repository.TaxaEntregaRepository.class),
            mock(br.com.bora.service.InsumoService.class),
            mock(br.com.bora.service.EstornoDaVendaService.class),
            mock(br.com.bora.security.RegraDeAcesso.class),
            mock(br.com.bora.repository.ConfiguracaoLojaRepository.class),
            repetir,
            mock(br.com.bora.service.CategoriaOrdemService.class),
            mock(org.springframework.transaction.PlatformTransactionManager.class),
            false);

    private PublicController controller() {
        return controller;
    }

    private void pedidoExiste() {
        Pedido p = new Pedido();
        p.id = PEDIDO; p.lojaId = LOJA; p.codigo = "CD-4242";
        p.status = StatusPedido.EM_PREPARO;
        p.valorTotal = new BigDecimal("43.40");
        p.criadoEm = OffsetDateTime.now();
        p.motivoCancelamento = null;
        when(pedidos.findByIdAndLojaId(PEDIDO, LOJA)).thenReturn(Optional.of(p));
    }

    @Test
    void semTokenNaoResponde() {
        pedidoExiste();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().statusPedido(LOJA, PEDIDO, null));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode(),
                "404 igual ao de pedido inexistente: a resposta nao pode dizer se o pedido existe");
    }

    @Test
    void comTokenErradoNaoResponde() {
        pedidoExiste();

        assertThrows(ResponseStatusException.class,
                () -> controller().statusPedido(LOJA, PEDIDO, "nao-e-o-token"));
    }

    @Test
    void comOTokenDeOutroPedidoNaoResponde() {
        pedidoExiste();
        String tokenDeOutro = repetir.token(LOJA, 9999L);

        assertThrows(ResponseStatusException.class,
                () -> controller().statusPedido(LOJA, PEDIDO, tokenDeOutro));
    }

    @Test
    void comOTokenDaOutraLojaNaoResponde() {
        pedidoExiste();
        String tokenDeOutraLoja = repetir.token(LOJA + 1, PEDIDO);

        assertThrows(ResponseStatusException.class,
                () -> controller().statusPedido(LOJA, PEDIDO, tokenDeOutraLoja));
    }

    @Test
    void comOTokenCertoResponde() {
        pedidoExiste();

        Map<String, Object> r = controller().statusPedido(LOJA, PEDIDO, repetir.token(LOJA, PEDIDO));

        assertEquals("CD-4242", r.get("codigo"));
        assertEquals("EM_PREPARO", r.get("status"));
    }

    @Test
    void nemComOTokenCertoVaiAlemDaLoja() {
        // O token confere, mas o pedido nao e desta loja: o filtro de tenant continua valendo.
        when(pedidos.findByIdAndLojaId(PEDIDO, LOJA)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> controller().statusPedido(LOJA, PEDIDO, repetir.token(LOJA, PEDIDO)));
    }

    @Test
    void oTokenNaoVazaOIdSequencial() {
        // O token e assinatura, nao codificacao: nao da para derivar o token do pedido 4243 a partir
        // do 4242, que e exatamente o que permitia a varredura.
        assertNotEquals(repetir.token(LOJA, 4242L), repetir.token(LOJA, 4243L));
        assertNotEquals(repetir.token(LOJA, PEDIDO), repetir.token(LOJA + 1, PEDIDO));
    }
}
