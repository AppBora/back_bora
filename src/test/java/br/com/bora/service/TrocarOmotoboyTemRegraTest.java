package br.com.bora.service;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.*;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Trocar o motoboy de um pedido tem regra — e deixa rastro.
 *
 * <h2>Item 6.5 da varredura</h2>
 * <p>Não havia regra nenhuma: qualquer OPERADOR trocava o motoboy de qualquer pedido, inclusive de um
 * já entregue, com texto livre e <b>sem deixar rastro nenhum</b>. E isso é dinheiro de outra pessoa:
 * o acerto paga por entregador, então trocar o nome move a taxa de um motoboy para outro.</p>
 *
 * <h2>As duas guardas, e por que são diferentes</h2>
 * <p><b>Pedido já ACERTADO não muda nunca</b>, nem para gerente. Se {@code acertoId} está preenchido,
 * aquela entrega já foi paga a alguém e o recibo está impresso — trocar o nome depois faria o
 * histórico do acerto mentir sobre quem recebeu, e não existe conserto para isso.</p>
 *
 * <p><b>Pedido já ENTREGUE exige gerente.</b> Durante a entrega, trocar o motoboy é operação de
 * balcão: o primeiro não pôde ir, vai o segundo. Depois de entregue, a única razão de mexer é
 * corrigir para quem vai receber — decisão de quem responde pelo caixa.</p>
 */
class TrocarOmotoboyTemRegraTest {

    private PedidoRepository repo;
    private AuthContext ctx;
    private PedidoService service;

    private static final Long LOJA = 1L;

    @BeforeEach
    void montar() {
        repo = mock(PedidoRepository.class);
        ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, LOJA, "OPERADOR", "balcao@zira.com"));
        service = new PedidoService(repo, mock(PedidoItemRepository.class), mock(ProdutoRepository.class),
                mock(ClienteRepository.class), mock(LogStatusRepository.class), mock(PlanoService.class),
                mock(IntegracaoService.class), mock(TaxaEntregaRepository.class), mock(InsumoService.class),
                mock(FidelidadeService.class), mock(ComplementoService.class), ctx,
                mock(EstornoDaVendaService.class), GerenciadorDeTransacaoFalso.novo());
        when(repo.save(any(Pedido.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Pedido pedido(StatusPedido status, Long acertoId) {
        Pedido p = new Pedido();
        p.id = 500L;
        p.lojaId = LOJA;
        p.status = status;
        p.entregador = "Joao";
        p.acertoId = acertoId;
        when(repo.findByIdAndLojaId(500L, LOJA)).thenReturn(Optional.of(p));
        return p;
    }

    // =============================================================== o que e barrado

    @Test
    void pedidoJAacertadoNAOmudaDEentregador() {
        // A entrega ja foi paga a alguem e o recibo esta impresso.
        Pedido p = pedido(StatusPedido.ENTREGUE, 9L);

        var erro = assertThrows(ResponseStatusException.class,
                () -> service.definirEntregador(500L, "Maria"));

        assertEquals(HttpStatus.CONFLICT, erro.getStatusCode());
        assertEquals("Joao", p.entregador, "o nome nao pode ter mudado");
        verify(repo, never()).save(any(Pedido.class));
    }

    @Test
    void aRECUSAdoACERTADOexplicaOporque() {
        pedido(StatusPedido.ENTREGUE, 9L);

        var erro = assertThrows(ResponseStatusException.class,
                () -> service.definirEntregador(500L, "Maria"));

        assertTrue(erro.getReason().contains("acerto"), "foi: " + erro.getReason());
        assertTrue(erro.getReason().contains("recebeu"), "e dizer o que o acerto registrou");
    }

    @Test
    void nemGERENTEmudaPEDIDOacertado() {
        // Esta guarda nao e de permissao: nao ha conserto depois, para ninguem.
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, LOJA, "ADMINISTRADOR_LOJA", "dono@zira.com"));
        pedido(StatusPedido.ENTREGUE, 9L);

        assertThrows(ResponseStatusException.class, () -> service.definirEntregador(500L, "Maria"));
    }

    @Test
    void operadorNAOmudaOmotoboyDEpedidoJAentregue() {
        pedido(StatusPedido.ENTREGUE, null);

        service.definirEntregador(500L, "Maria");

        verify(ctx).requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
    }

    // =============================================================== o que continua passando

    @Test
    void oUSOnormalCONTINUA_trocarANTESdeENTREGAR() {
        // O primeiro motoboy nao pode ir, vai o segundo. Isso e balcao, e nao pode pedir gerente.
        Pedido p = pedido(StatusPedido.SAIU_PARA_ENTREGA, null);

        assertDoesNotThrow(() -> service.definirEntregador(500L, "Maria"));

        assertEquals("Maria", p.entregador);
        verify(ctx, never()).requirePapel(any(String[].class));
    }

    @Test
    void ESCOLHERoMOTOBOYdeUMpedidoNOVOcontinuaLIVRE() {
        Pedido p = pedido(StatusPedido.PRONTO, null);
        p.entregador = null;

        assertDoesNotThrow(() -> service.definirEntregador(500L, "Joao"));

        assertEquals("Joao", p.entregador);
    }

    @Test
    void LIMPARoMOTOBOYcontinuaFuncionando() {
        // Escolheu errado e quer desfazer antes de despachar.
        Pedido p = pedido(StatusPedido.PRONTO, null);

        service.definirEntregador(500L, "   ");

        assertNull(p.entregador, "espaco em branco vira nulo, como antes");
    }

    @Test
    void oNOMEcontinuaSENDOaparado() {
        Pedido p = pedido(StatusPedido.PRONTO, null);

        service.definirEntregador(500L, "  Maria  ");

        assertEquals("Maria", p.entregador);
    }

    // =============================================================== e fica o rastro

    @Test
    void aTROCAfICAnoLOGdeAUDITORIA() {
        // Era o unico campo de dinheiro que mudava sem deixar rastro. O teste le o fonte porque o que
        // importa aqui e a linha existir com os dois nomes -- quem auditar precisa do de antes.
        String fonte = assertDoesNotThrow(() -> java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/br/com/bora/service/PedidoService.java"),
                java.nio.charset.StandardCharsets.UTF_8));
        int i = fonte.indexOf("public Pedido definirEntregador(");
        assertTrue(i > 0);
        String metodo = fonte.substring(i, fonte.indexOf("\n    }", i));

        assertTrue(metodo.contains("AUDITORIA"), "a troca tem que ir para o log de auditoria");
        assertTrue(metodo.contains("String anterior = p.entregador"),
                "o log tem que dizer de QUEM para quem, senao nao da para auditar");
        assertTrue(metodo.contains("userId()"), "e quem fez");
    }
}
