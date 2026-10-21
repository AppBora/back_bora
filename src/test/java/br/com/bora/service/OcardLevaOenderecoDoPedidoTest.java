package br.com.bora.service;

import br.com.bora.dto.PedidoCard;
import br.com.bora.entity.Cliente;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.*;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O card do quadro leva o endereço DESTE pedido, não o do cadastro do cliente.
 *
 * <h2>O item 6.2 da varredura, e o que medir mostrou</h2>
 * <p>O {@code PedidoCard} mandava só {@code clienteEndereco} e {@code clienteBairro} — os do
 * <b>cadastro</b>. Em cliente recorrente que se mudou, o motoboy lia o endereço antigo. E isso é
 * exatamente o que a V48 foi criada para resolver: ela gravou {@code endereco_entrega},
 * {@code bairro_entrega} e {@code tipo_entrega} no pedido, e as colunas <b>nunca chegaram na
 * tela</b>.</p>
 *
 * <p><b>As telas já esperavam o dado.</b> {@code pedidos.js:110} e {@code entregas.js:30,70} escrevem
 * {@code p.enderecoEntrega || p.clienteEndereco}: como o campo nunca vinha, o segundo sempre ganhava.
 * Faltava o dado, não a tela — então o conserto é só no backend, e a tela passa a mostrar o endereço
 * certo sem mudar uma linha.</p>
 *
 * <p>O {@code tipoEntrega} vai junto pelo mesmo motivo: sem ele a tela de Entregas oferece
 * "Despachar" para quem vem buscar no balcão.</p>
 */
class OcardLevaOenderecoDoPedidoTest {

    private PedidoRepository repo;
    private ClienteRepository clientes;
    private PedidoService service;

    private static final Long LOJA = 1L;

    @BeforeEach
    void montar() {
        repo = mock(PedidoRepository.class);
        clientes = mock(ClienteRepository.class);
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        service = new PedidoService(repo, mock(PedidoItemRepository.class), mock(ProdutoRepository.class),
                clientes, mock(LogStatusRepository.class), mock(PlanoService.class),
                mock(IntegracaoService.class), mock(TaxaEntregaRepository.class),
                mock(InsumoService.class), mock(FidelidadeService.class), mock(ComplementoService.class),
                ctx, mock(EstornoDaVendaService.class), GerenciadorDeTransacaoFalso.novo());
        when(repo.findByLojaIdAndStatusInOrderByCriadoEmDesc(eq(LOJA), any())).thenReturn(List.of());
    }

    /** O cliente do cadastro, com o endereço ANTIGO. */
    private void clienteMudouDeCasa() {
        Cliente c = new Cliente();
        c.id = 77L;
        c.lojaId = LOJA;
        c.nome = "Maria";
        c.endereco = "Rua Velha, 100";
        c.bairro = "Centro";
        c.telefone = "62999990000";
        when(clientes.findByLojaIdAndIdIn(eq(LOJA), any())).thenReturn(List.of(c));
    }

    private PedidoCard cardDe(Pedido p) {
        when(repo.findByLojaIdAndCriadoEmGreaterThanEqualAndCriadoEmLessThanOrderByCriadoEmDesc(
                eq(LOJA), any(), any())).thenReturn(List.of(p));
        List<PedidoCard> cards = service.board(LocalDate.now(), null);
        assertEquals(1, cards.size(), "o cenario tem que render um card");
        return cards.get(0);
    }

    private Pedido pedido() {
        Pedido p = new Pedido();
        p.id = 500L;
        p.lojaId = LOJA;
        p.clienteId = 77L;
        p.status = StatusPedido.RECEBIDO;
        p.criadoEm = OffsetDateTime.now();
        p.atualizadoEm = OffsetDateTime.now();
        return p;
    }

    // =============================================================== o endereco do pedido

    @Test
    void oENDERECOdoPEDIDOchegaNOcard() {
        clienteMudouDeCasa();
        Pedido p = pedido();
        p.enderecoEntrega = "Rua Nova, 500";
        p.bairroEntrega = "Setor Bueno";

        PedidoCard card = cardDe(p);

        assertEquals("Rua Nova, 500", card.enderecoEntrega(),
                "sem isto o motoboy vai no endereco antigo do cadastro");
        assertEquals("Setor Bueno", card.bairroEntrega());
    }

    @Test
    void oDOcadastroCONTINUAindoJUNTO_porqueEHaREDEdeSEGURANCA() {
        // A tela escreve `p.enderecoEntrega || p.clienteEndereco`. Pedido anterior a V48 nao tem a
        // coluna, e ai o cadastro e a melhor informacao que existe para o passado.
        clienteMudouDeCasa();
        Pedido p = pedido();
        p.enderecoEntrega = "Rua Nova, 500";

        PedidoCard card = cardDe(p);

        assertEquals("Rua Velha, 100", card.clienteEndereco());
        assertEquals("Centro", card.clienteBairro());
    }

    @Test
    void pedidoANTESdaV48vemComNULO_eNAOcomVAZIO() {
        // Nulo e o que faz a tela cair no cadastro. String vazia ganharia do `||` e deixaria o card
        // sem endereco nenhum -- pior que o defeito.
        clienteMudouDeCasa();

        PedidoCard card = cardDe(pedido());

        assertNull(card.enderecoEntrega());
        assertNull(card.bairroEntrega());
        assertEquals("Rua Velha, 100", card.clienteEndereco());
    }

    // =============================================================== o tipo de entrega

    @Test
    void oTIPOdeENTREGAchegaNOcard() {
        // Sem ele a tela de Entregas oferece "Despachar" para quem vem buscar no balcao.
        clienteMudouDeCasa();
        Pedido p = pedido();
        p.tipoEntrega = "RETIRADA";

        assertEquals("RETIRADA", cardDe(p).tipoEntrega());
    }

    @Test
    void entregaNORMALchegaComoENTREGA() {
        clienteMudouDeCasa();
        Pedido p = pedido();
        p.tipoEntrega = "ENTREGA";

        assertEquals("ENTREGA", cardDe(p).tipoEntrega());
    }

    // =============================================================== o resto do card nao mudou

    @Test
    void oQUEoCARDjaMANDAVAcontinuaIGUAL() {
        // Acrescentar campo em record e trocar a ordem dos argumentos sem o compilador reclamar --
        // tres Strings seguidas aceitam qualquer permutacao. Este teste prende cada uma no lugar.
        clienteMudouDeCasa();
        Pedido p = pedido();
        p.codigo = "A-12";
        p.valorTotal = new java.math.BigDecimal("40.00");
        p.formaPagamento = "Dinheiro na entrega";
        p.origem = "CARDAPIO";
        p.observacao = "sem cebola";
        p.entregador = "Joao";
        p.enderecoEntrega = "Rua Nova, 500";
        p.bairroEntrega = "Setor Bueno";
        p.tipoEntrega = "ENTREGA";

        PedidoCard card = cardDe(p);

        assertEquals("A-12", card.codigo());
        assertEquals("RECEBIDO", card.status());
        assertEquals("Dinheiro na entrega", card.formaPagamento());
        assertEquals("CARDAPIO", card.origem());
        assertEquals("sem cebola", card.observacao(), "a cozinha le isto no card");
        assertEquals("Maria", card.clienteNome());
        assertEquals("62999990000", card.clienteTelefone());
        assertEquals("Joao", card.entregador());
    }

    @Test
    void pedidoSEMcliente_naoEstoura() {
        Pedido p = pedido();
        p.clienteId = null;
        p.enderecoEntrega = "Rua Nova, 500";
        when(clientes.findByLojaIdAndIdIn(eq(LOJA), any())).thenReturn(List.of());

        PedidoCard card = cardDe(p);

        assertNull(card.clienteNome());
        assertEquals("Rua Nova, 500", card.enderecoEntrega(), "o endereco do pedido vale sem cadastro");
    }
}
