package br.com.bora.service;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PIX não pago NÃO cancela pedido que a cozinha já preparou ou que já foi entregue.
 *
 * <h2>O item 6.1 da varredura</h2>
 * <p>O laço que expira PIX abandonado olhava duas coisas: {@code pagoEm} e {@code CANCELADO}. Mais
 * nada. Um pedido de PIX que o lojista preparou e entregou sem esperar a confirmação virava
 * <b>CANCELADO 30 minutos depois de nascer</b> — e o cancelamento manda recado: <b>o cliente recebia
 * "pedido cancelado" depois de ter comido</b>, no WhatsApp e na tela de acompanhar.</p>
 *
 * <p>Cancelar ali está errado por dois motivos independentes: a comida saiu, então não há o que
 * desfazer; e o cancelamento fala com o cliente. O que faltava não era cancelar — era <b>avisar o
 * lojista</b> de que saiu comida sem pagamento, que é dinheiro e desaparece se ninguém olhar.</p>
 *
 * <h2>Por que a marca de "aguardando pagamento" NÃO é limpa</h2>
 * <p>É ela que mantém o pedido fora do faturamento — "PIX que ninguém pagou não é venda", a regra do
 * item 1.3. Limpar faria o dinheiro aparecer no caixa sem ter entrado. Então o pedido continua
 * marcado, e o que evita avisar a cada varredura é a própria observação: o recado é a chave.</p>
 */
class PixNaoCancelaPedidoQueSaiuTest {

    private PedidoRepository pedidos;
    private EstornoDaVendaService estorno;
    private CobradorDePixService cobrador;

    private static final Long LOJA = 1L;

    @BeforeEach
    void montar() {
        pedidos = mock(PedidoRepository.class);
        estorno = mock(EstornoDaVendaService.class);
        cobrador = new CobradorDePixService(pedidos, mock(FidelidadeService.class),
                mock(LojaRepository.class), mock(IntegracaoCanalRepository.class),
                mock(PixService.class), estorno, GerenciadorDeTransacaoFalso.novo(), 30);
        when(pedidos.save(any(Pedido.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Pedido pedido(StatusPedido status) {
        Pedido p = new Pedido();
        p.id = 500L;
        p.lojaId = LOJA;
        p.status = status;
        p.aguardandoPagamento = true;
        p.pagoEm = null;
        p.valorTotal = new BigDecimal("40.00");
        p.observacao = "";
        when(pedidos.findByAguardandoPagamentoTrueAndCriadoEmBefore(any())).thenReturn(List.of(p));
        return p;
    }

    private List<Pedido> varrer() {
        return cobrador.cancelarVencidos(OffsetDateTime.now(), Set.of());
    }

    // =============================================================== nao cancela o que saiu

    @Test
    void pedidoENTREGUEnaoEHcancelado() {
        // O caso que doi: o cliente comeu e receberia "pedido cancelado".
        Pedido p = pedido(StatusPedido.ENTREGUE);

        assertTrue(varrer().isEmpty(), "pedido entregue nao pode entrar na lista de cancelados");
        assertEquals(StatusPedido.ENTREGUE, p.status);
        assertNull(p.canceladoEm);
    }

    @Test
    void pedidoEMPREPAROnaoEHcancelado() {
        // A cozinha ja mexeu na comida.
        Pedido p = pedido(StatusPedido.EM_PREPARO);

        assertTrue(varrer().isEmpty());
        assertEquals(StatusPedido.EM_PREPARO, p.status);
    }

    @Test
    void pedidoPRONTOeSAIUparaENTREGAtambemNAO() {
        for (StatusPedido st : List.of(StatusPedido.PRONTO, StatusPedido.SAIU_PARA_ENTREGA)) {
            Pedido p = pedido(st);
            assertTrue(varrer().isEmpty(), "nao pode cancelar em " + st);
            assertEquals(st, p.status);
        }
    }

    @Test
    void oESTOQUEnaoEHdevolvido_porqueACOMIDAsaiu() {
        // Devolver insumo de comida que foi entregue infla o estoque com o que nao existe.
        pedido(StatusPedido.ENTREGUE);

        varrer();

        verify(estorno, never()).devolver(any(), any(), anyBoolean());
    }

    // =============================================================== mas avisa

    @Test
    void oLOJISTAeAVISADOnaOBSERVACAOdoPEDIDO() {
        // E o unico lugar que a equipe le: o card. Sem isto, saiu comida sem pagamento e ninguem fica
        // sabendo -- que era o estado anterior, sob a forma de um cancelamento mentiroso.
        Pedido p = pedido(StatusPedido.ENTREGUE);

        varrer();

        assertTrue(p.observacao.contains("NUNCA foi pago"), "a observacao ficou: " + p.observacao);
        verify(pedidos).save(p);
    }

    @Test
    void oRECADOdoCLIENTEnaoEHperdido() {
        Pedido p = pedido(StatusPedido.ENTREGUE);
        p.observacao = "sem cebola";

        varrer();

        assertTrue(p.observacao.startsWith("sem cebola"));
        assertTrue(p.observacao.contains("NUNCA foi pago"));
    }

    @Test
    void oAVISOnaoEHrepetidoAcadaVARREDURA() {
        // A varredura roda a cada poucos minutos e o pedido continua marcado de proposito. Sem esta
        // guarda, a observacao cresceria para sempre e o log viraria ruido.
        Pedido p = pedido(StatusPedido.ENTREGUE);

        varrer();
        String depoisDaPrimeira = p.observacao;
        varrer();
        varrer();

        assertEquals(depoisDaPrimeira, p.observacao, "a observacao nao pode crescer a cada varredura");
        verify(pedidos, times(1)).save(p);
    }

    @Test
    void aMARCAdeAGUARDANDOpagamentoCONTINUAligada() {
        // E ela que mantem este pedido FORA do faturamento. Limpar faria o dinheiro aparecer no caixa
        // sem ter entrado -- pior que o defeito original.
        Pedido p = pedido(StatusPedido.ENTREGUE);

        varrer();

        assertEquals(Boolean.TRUE, p.aguardandoPagamento);
        assertTrue(p.pagamentoPendente(), "o pedido tem que seguir contando como pagamento pendente");
    }

    // =============================================================== o que CONTINUA sendo cancelado

    @Test
    void pedidoRECEBIDOcontinuaSENDOcancelado() {
        // O caso para o qual a rotina existe: checkout abandonado, ninguem mexeu na comida.
        Pedido p = pedido(StatusPedido.RECEBIDO);

        assertEquals(1, varrer().size());
        assertEquals(StatusPedido.CANCELADO, p.status);
        assertNotNull(p.canceladoEm);
        verify(estorno).devolver(p, StatusPedido.RECEBIDO, false);
    }

    @Test
    void pedidoCONFIRMADOtambemContinuaSENDOcancelado() {
        // CONFIRMADO ainda e fila: o lojista aceitou, a cozinha nao comecou.
        Pedido p = pedido(StatusPedido.CONFIRMADO);

        assertEquals(1, varrer().size());
        assertEquals(StatusPedido.CANCELADO, p.status);
        verify(estorno).devolver(p, StatusPedido.CONFIRMADO, false);
    }

    @Test
    void pedidoJApagoNAOeTOCADOemESTADOnenhum() {
        Pedido p = pedido(StatusPedido.RECEBIDO);
        p.pagoEm = OffsetDateTime.now();

        assertTrue(varrer().isEmpty());
        assertEquals(StatusPedido.RECEBIDO, p.status);
        assertEquals("", p.observacao, "pedido pago nao recebe aviso nenhum");
    }

    @Test
    void osDOISestadosDEespera_eSOeles_saoCANCELAVEIS() {
        // O enum tem SETE valores. Se alguem acrescentar um estado de fila novo e esquecer este
        // metodo, o pedido novo deixa de ser cancelado e o PIX abandonado passa a acumular -- ou pior,
        // um estado de producao novo entra na lista e volta o defeito 6.1.
        Set<StatusPedido> cancelaveis = java.util.EnumSet.noneOf(StatusPedido.class);
        for (StatusPedido st : StatusPedido.values()) {
            Pedido p = pedido(st);
            if (!varrer().isEmpty()) cancelaveis.add(st);
        }

        assertEquals(java.util.EnumSet.of(StatusPedido.RECEBIDO, StatusPedido.CONFIRMADO), cancelaveis,
                "so os dois estados de fila podem ser cancelados por PIX nao pago");
    }
}
