package br.com.bora.service;

import br.com.bora.entity.StatusPedido;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Todo status do pedido está classificado: ou aberto, ou encerrado.
 *
 * <h2>Por que este teste existe</h2>
 * <p>A consulta do quadro da cozinha era {@code status NOT IN (ENCERRADOS)} — uma negação, que
 * nenhum índice serve. O KDS recarrega a cada 6 segundos e cada volta varria todos os pedidos da
 * loja. Trocar por lista <b>positiva</b> ({@code status IN (ABERTOS)}) faz o índice
 * {@code (loja_id, status)} servir sempre — medido em 9,3 ms → 0,35 ms com 26 mil pedidos (V54).</p>
 *
 * <p><b>Mas a troca cria um risco novo.</b> A negação pegava status novo de graça: qualquer coisa que
 * não fosse ENTREGUE ou CANCELADO aparecia no quadro. A lista positiva não — um status acrescentado ao
 * enum e esquecido em {@code ABERTOS} faz o pedido <b>desaparecer da vista da cozinha</b>, calado. A
 * comida não sai e ninguém entende por quê.</p>
 *
 * <p>E não é hipotético: o enum tem <b>sete</b> valores, não seis. Ao montar a lista eu quase deixei o
 * {@code CONFIRMADO} de fora — é o que o marketplace usa entre o aceite e o preparo.</p>
 *
 * <p>Este teste é a trava: acrescentar status sem classificar reprova, com o nome do status na
 * mensagem.</p>
 */
class AbertosMaisEncerradosCobremTudoTest {

    @Test
    void todoSTATUSestaEmABERTOSouEmENCERRADOS() {
        Set<StatusPedido> classificados = new HashSet<>(PedidoService.ABERTOS);
        classificados.addAll(PedidoService.ENCERRADOS);

        Set<StatusPedido> faltando = Arrays.stream(StatusPedido.values())
                .filter(st -> !classificados.contains(st))
                .collect(Collectors.toCollection(TreeSet::new));

        assertTrue(faltando.isEmpty(),
                "estes status não estão nem em ABERTOS nem em ENCERRADOS: " + faltando
                        + ". Pedido nesse status DESAPARECE do quadro da cozinha, porque a consulta "
                        + "do quadro usa a lista positiva ABERTOS. Classifique em PedidoService.");
    }

    @Test
    void nenhumSTATUSestaNosDOIS() {
        Set<StatusPedido> nosDois = PedidoService.ABERTOS.stream()
                .filter(PedidoService.ENCERRADOS::contains)
                .collect(Collectors.toCollection(TreeSet::new));

        assertTrue(nosDois.isEmpty(),
                "estes status estão em ABERTOS e em ENCERRADOS ao mesmo tempo: " + nosDois
                        + ". O quadro mostraria pedido encerrado, ou o contrário.");
    }

    @Test
    void oCONFIRMADOestaEmABERTOS() {
        // O que eu quase esqueci. E o status que o marketplace usa entre o aceite e o preparo: sem ele
        // na lista, pedido aceito no iFood nao aparece para a cozinha.
        assertTrue(PedidoService.ABERTOS.contains(StatusPedido.CONFIRMADO),
                "pedido CONFIRMADO esta vivo: a cozinha precisa ve-lo");
    }

    @Test
    void oENTREGUEeoCANCELADOnaoEstaoEmABERTOS() {
        // O outro lado: encerrado no quadro entope a vista da cozinha com o que ja acabou.
        assertFalse(PedidoService.ABERTOS.contains(StatusPedido.ENTREGUE));
        assertFalse(PedidoService.ABERTOS.contains(StatusPedido.CANCELADO));
    }

    @Test
    void aLISTAdeABERTOScobreOqueANEGACAOcobria() {
        // A prova de que a troca preservou o comportamento: o conjunto que a consulta antiga trazia
        // (tudo menos os encerrados) tem que ser EXATAMENTE o conjunto novo.
        Set<StatusPedido> oQueANegacaoTrazia = Arrays.stream(StatusPedido.values())
                .filter(st -> !PedidoService.ENCERRADOS.contains(st))
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(oQueANegacaoTrazia, new TreeSet<>(PedidoService.ABERTOS),
                "a lista positiva tem que trazer exatamente o que a negação trazia");
    }

    @Test
    void oTESTEestaREALMENTEolhandoOenum() {
        // Sem isto, um enum vazio ou um erro de reflexão faria tudo acima passar medindo nada.
        assertEquals(7, StatusPedido.values().length,
                "o enum mudou de tamanho: confira se o status novo foi classificado");
        assertEquals(5, PedidoService.ABERTOS.size());
        assertEquals(2, PedidoService.ENCERRADOS.size());
    }
}
