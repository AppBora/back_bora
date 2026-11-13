package br.com.bora.controller;

import br.com.bora.dto.ItemPedidoRequest;
import br.com.bora.dto.NovoPedidoRequest;
import br.com.bora.entity.Pedido;
import br.com.bora.service.PedidoIdempotenteService;
import br.com.bora.service.PedidoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A chave da venda tem que CHEGAR ao serviço.
 *
 * <h2>Por que esta classe existe</h2>
 * <p>Porque <b>teste de serviço não cobre fiação de controller</b>, e isso já custou neste projeto:
 * em 21/10/2026 uma mutação no {@code PlataformaController} passou com 28 testes de serviço verdes
 * (ver {@link ConfirmacaoDoSegundoPagamentoChegaAoServicoTest}). Aqui o risco é o mesmo em forma
 * nova: o {@link PedidoIdempotenteService} tem 8 testes provando que ele barra a venda repetida, e
 * <b>todos os 8 continuariam verdes</b> se o cabeçalho nunca chegasse até ele. A trava existiria,
 * ninguém a alimentaria, e a venda voltaria a ser gravada duas vezes — calada, como antes.</p>
 *
 * <h2>As duas metades, e por que são duas</h2>
 * <p>Chamar o método do controller prova o repasse, e <b>não</b> prova que o Spring enche o
 * parâmetro a partir do cabeçalho HTTP: numa chamada direta quem passa o valor é o teste. O nome do
 * cabeçalho e o "pode faltar" ficam na anotação, então é a anotação que precisa ser lida — por
 * reflexão, que é o que o Spring também lê. Um {@code @RequestHeader} com o nome trocado
 * ({@code Idempotency_Key}, {@code X-Idempotency-Key}) deixaria a tela mandando a chave e o servidor
 * recebendo nulo, e nenhum teste de serviço notaria.</p>
 */
class AchaveDaVendaCHEGAaoServicoTest {

    /** O mesmo texto que o {@code api.js} e o {@code novo-pedido.js} mandam. */
    private static final String CABECALHO = "Idempotency-Key";

    private PedidoIdempotenteService umaVezSo;
    private PedidoController controller;

    private static NovoPedidoRequest venda() {
        return new NovoPedidoRequest(null, "#001", "Dinheiro", "Balcao", null, false,
                List.of(new ItemPedidoRequest(10L, 1, null)), null, null, null, null);
    }

    @BeforeEach
    void montar() {
        umaVezSo = mock(PedidoIdempotenteService.class);
        controller = new PedidoController(mock(PedidoService.class), umaVezSo);
        Pedido p = new Pedido();
        p.id = 500L;
        // `any()` nos dois: misturar matcher com um null cru e InvalidUseOfMatchers, e aqui o caso
        // SEM cabecalho e justamente o do nulo.
        when(umaVezSo.criar(any(), any())).thenReturn(p);
    }

    // ------------------------------------------------------------- metade 1: o repasse

    @Test
    void aCHAVEdoCABECALHOchegaINTEIRAaoSERVICO() {
        String chave = "11111111-2222-3333-4444-555555555555";

        controller.criar(venda(), chave);

        ArgumentCaptor<String> recebida = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(umaVezSo).criar(any(NovoPedidoRequest.class), recebida.capture());
        assertEquals(chave, recebida.getValue(),
                "sem isto a trava existe e ninguem a alimenta: a venda volta a ser gravada duas vezes");
    }

    @Test
    void semCABECALHOoSERVICOrecebeNULO() {
        // Cliente antigo -- cardapio publico, webhook, integracao de terceiro -- nao manda chave, e
        // tem de continuar funcionando. Nulo e o sinal de "segue pelo caminho de sempre".
        controller.criar(venda(), null);

        ArgumentCaptor<String> recebida = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(umaVezSo).criar(any(NovoPedidoRequest.class), recebida.capture());
        assertNull(recebida.getValue());
    }

    @Test
    void oPEDIDOqueOservicoDEVOLVEeOqueSAInaRESPOSTA() {
        // Repasse tambem na volta: devolver outra coisa faria o reenvio parecer uma venda nova.
        assertEquals(500L, controller.criar(venda(), "chave").id);
    }

    // ------------------------------------------------- metade 2: a anotacao, lida por reflexao

    @Test
    void oNOMEdoCABECALHOnaANOTACAOeOqueOnavegadorMANDA() throws Exception {
        Method criar = PedidoController.class.getMethod("criar", NovoPedidoRequest.class, String.class);
        Parameter[] params = criar.getParameters();
        RequestHeader anotacao = params[params.length - 1].getAnnotation(RequestHeader.class);

        assertNotNull(anotacao, "sem @RequestHeader o Spring nao enche o parametro: a chave nunca "
                + "chega, e a chamada direta do teste de cima nao enxerga isso");
        String nome = anotacao.name().isEmpty() ? anotacao.value() : anotacao.name();
        assertEquals(CABECALHO, nome, "o nome tem de ser EXATAMENTE o que o api.js manda");
        assertFalse(anotacao.required(), "cabecalho obrigatorio faria toda chamada sem chave virar "
                + "400 -- cardapio publico e webhook param de lancar pedido");
    }
}
