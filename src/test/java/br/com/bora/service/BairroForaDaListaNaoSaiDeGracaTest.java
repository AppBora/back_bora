package br.com.bora.service;

import br.com.bora.dto.ItemPedidoRequest;
import br.com.bora.dto.NovoPedidoRequest;
import br.com.bora.entity.Cliente;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.Produto;
import br.com.bora.entity.TaxaEntrega;
import br.com.bora.repository.ClienteRepository;
import br.com.bora.repository.LogStatusRepository;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.repository.TaxaEntregaRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bairro fora da lista de entrega nao pode sair R$ 0,00 em silencio.
 *
 * <h2>O defeito (medido em 11/11/2026)</h2>
 * <p>O {@code taxaPara} devolvia {@code BigDecimal.ZERO} quando o bairro nao tinha correspondencia
 * na tabela, e o pedido era aceito com frete zerado <b>sem aviso nenhum</b>. Cliente sem bairro no
 * cadastro tambem pagava zero. O cardapio publico recusa esse caso com 400 ("Ainda nao entregamos
 * nesse bairro"); aqui passava calado.</p>
 *
 * <p><b>Por que isto estava custando dinheiro de verdade:</b> a loja 18 da producao chama-se
 * "Zira - Montreal" e <b>nao tem Montreal</b> entre os 6 bairros cadastrados dela. Quem mora no
 * bairro do nome da propria loja recebia <b>de graca</b>, todo dia, e nada denunciava.</p>
 *
 * <h2>O segundo defeito, no mesmo metodo</h2>
 * <p>A taxa saia de {@code cliente.bairro} -- o bairro do CADASTRO -- e nao do bairro DESTE pedido.
 * O atendente digita "entregar no Centro" e o frete vinha do bairro antigo: outro bairro, outro
 * preco. E quem nao tem cadastro nenhum nao tinha frete, porque {@code clienteId == null} devolvia
 * zero antes de olhar qualquer coisa.</p>
 */
class BairroForaDaListaNaoSaiDeGracaTest {

    private PedidoRepository repo;
    private TaxaEntregaRepository taxas;
    private ClienteRepository clientes;
    private ProdutoRepository produtos;
    private PedidoService service;

    @BeforeEach
    void montar() {
        repo = mock(PedidoRepository.class);
        taxas = mock(TaxaEntregaRepository.class);
        clientes = mock(ClienteRepository.class);
        produtos = mock(ProdutoRepository.class);
        ComplementoService complementos = mock(ComplementoService.class);
        InsumoService insumos = mock(InsumoService.class);
        FidelidadeService fidelidade = mock(FidelidadeService.class);
        AuthContext ctx = mock(AuthContext.class);

        service = new PedidoService(repo, mock(PedidoItemRepository.class), produtos, clientes,
                mock(LogStatusRepository.class), mock(PlanoService.class), mock(IntegracaoService.class),
                taxas, insumos, fidelidade, complementos, ctx, mock(EstornoDaVendaService.class),
                GerenciadorDeTransacaoFalso.novo());
        ReflectionTestUtils.setField(service, "notifCliente", mock(NotificacaoClienteService.class));

        when(ctx.lojaId()).thenReturn(1L);
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, 1L, "ADMINISTRADOR_LOJA", "admin@bora.app"));
        when(repo.save(any(Pedido.class))).thenAnswer(inv -> {
            Pedido p = inv.getArgument(0);
            p.id = 500L;
            return p;
        });
        when(complementos.aplicar(anyLong(), any(Produto.class), any()))
                .thenReturn(new ComplementoService.Escolha(BigDecimal.ZERO, List.of()));
        when(insumos.consumirFicha(anyLong(), anyLong(), anyInt())).thenReturn(null);
        when(fidelidade.resgatePossivel(anyLong(), any(), any(BigDecimal.class))).thenReturn(BigDecimal.ZERO);

        Produto p = new Produto();
        p.id = 10L;
        p.lojaId = 1L;
        p.nome = "Acai 500ml";
        p.preco = new BigDecimal("30.00");
        when(produtos.findByIdAndLojaId(10L, 1L)).thenReturn(Optional.of(p));
    }

    private void bairroComFrete(String bairro, String valor) {
        TaxaEntrega t = new TaxaEntrega();
        t.taxa = new BigDecimal(valor);
        when(taxas.findFirstByLojaIdAndBairroIgnoreCaseAndAtivoTrueOrderByIdDesc(1L, bairro))
                .thenReturn(Optional.of(t));
    }

    private void clienteNoBairro(long id, String bairro) {
        Cliente c = new Cliente();
        c.id = id;
        c.lojaId = 1L;
        c.nome = "Cliente";
        c.bairro = bairro;
        when(clientes.findByIdAndLojaId(id, 1L)).thenReturn(Optional.of(c));
    }

    /** Sem este default o Mockito devolve null e o teste morre por outro motivo. */
    private void nenhumBairroCadastrado() {
        when(taxas.findFirstByLojaIdAndBairroIgnoreCaseAndAtivoTrueOrderByIdDesc(anyLong(), anyString()))
                .thenReturn(Optional.empty());
    }

    private NovoPedidoRequest entregaPara(Long clienteId, String bairroDoPedido, BigDecimal taxaInformada) {
        return new NovoPedidoRequest(clienteId, "#1", "Dinheiro", "Delivery", null, false,
                List.of(new ItemPedidoRequest(10L, 1, null)), taxaInformada, "ENTREGA", null, bairroDoPedido);
    }

    private Pedido salvo() {
        ArgumentCaptor<Pedido> c = ArgumentCaptor.forClass(Pedido.class);
        verify(repo).save(c.capture());
        return c.getValue();
    }

    // ------------------------------------------------------------------ o defeito

    @Test
    void bairroFORAdaLISTAnaoVIRAfreteZEROcalado() {
        nenhumBairroCadastrado();
        clienteNoBairro(3L, "Jardim Montreal");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.criar(entregaPara(3L, null, null)),
                "frete zero em silencio e entrega de graca que ninguem ve");

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason() != null && e.getReason().contains("Jardim Montreal"),
                "a resposta tem de DIZER qual bairro ficou de fora: " + e.getReason());
        verify(repo, never()).save(any(Pedido.class));
    }

    @Test
    void entregaSEMbairroNENHUMtambemNAOpassaCALADA() {
        nenhumBairroCadastrado();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.criar(entregaPara(null, null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(repo, never()).save(any(Pedido.class));
    }

    @Test
    void oBAIRROdoPEDIDOvenceOdoCADASTRO() {
        // O atendente digitou "entregar no Centro". O cadastro diz Setor Bueno, que custa R$ 7,00.
        // Antes, o frete saia do CADASTRO -- outro bairro, outro preco.
        clienteNoBairro(3L, "Setor Bueno");
        bairroComFrete("Setor Bueno", "7.00");
        bairroComFrete("Centro", "4.50");

        service.criar(entregaPara(3L, "Centro", null));

        assertEquals(0, new BigDecimal("4.50").compareTo(salvo().taxaEntrega),
                "vale o frete do bairro para onde o pedido vai");
        assertEquals(0, new BigDecimal("34.50").compareTo(salvo().valorTotal));
    }

    @Test
    void quemNAOtemCADASTROtambemPAGAfrete() {
        // `clienteId == null` devolvia ZERO antes de olhar qualquer coisa: quem pediu pela primeira
        // vez, sem cadastro, levava entrega de graca.
        bairroComFrete("Centro", "4.50");

        service.criar(entregaPara(null, "Centro", null));

        assertEquals(0, new BigDecimal("4.50").compareTo(salvo().taxaEntrega));
    }

    // ------------------------------------------------------------------ o que NAO pode mudar

    @Test
    void oBALCONISTAcontinuaPODENDOcombinarFRETEforaDAtabela() {
        // A trava nao pode travar a venda: o campo "Taxa" do painel sempre vai no corpo, e o
        // balconista decide (frete combinado, cortesia, R$ 0,00 de proposito).
        nenhumBairroCadastrado();
        clienteNoBairro(3L, "Bairro Que Nao Existe");

        service.criar(entregaPara(3L, null, BigDecimal.ZERO));

        assertEquals(0, BigDecimal.ZERO.compareTo(salvo().taxaEntrega), "cortesia informada na mao");
        assertEquals(0, new BigDecimal("30.00").compareTo(salvo().valorTotal));
    }

    @Test
    void retiradaNAOtemFRETEenemRECLAMAdeBAIRRO() {
        nenhumBairroCadastrado();

        service.criar(new NovoPedidoRequest(null, "#2", "Dinheiro", "Delivery", null, false,
                List.of(new ItemPedidoRequest(10L, 1, null)), null, "RETIRADA", null, null));

        assertEquals(0, BigDecimal.ZERO.compareTo(salvo().taxaEntrega));
    }

    @Test
    void balcaoNAOtemFRETEenemRECLAMAdeBAIRRO() {
        nenhumBairroCadastrado();
        clienteNoBairro(3L, "Bairro Que Nao Existe");

        service.criar(new NovoPedidoRequest(3L, "#3", "Dinheiro", "Balcao", null, false,
                List.of(new ItemPedidoRequest(10L, 1, null)), null, null, null, null));

        assertEquals(0, BigDecimal.ZERO.compareTo(salvo().taxaEntrega));
    }

    @Test
    void bairroCADASTRADOcomFRETEzeroCONTINUAvalendoZERO() {
        // Entrega gratis no Centro e uma decisao do lojista, e tem de ser possivel. O que muda e que
        // agora o zero vem de um cadastro, e nao da ausencia dele.
        bairroComFrete("Centro", "0.00");

        service.criar(entregaPara(null, "Centro", null));

        assertEquals(0, BigDecimal.ZERO.compareTo(salvo().taxaEntrega));
        assertEquals(0, new BigDecimal("30.00").compareTo(salvo().valorTotal));
    }

    // ------------------------------------------------------------------ teste do teste

    @Test
    void aTABELAdeFRETEdeMENTIRAestaSENDOconsultada() {
        // Sem isto, um bairro que nunca chega na consulta faria os testes de cima passarem medindo
        // outra coisa.
        bairroComFrete("Centro", "4.50");
        service.criar(entregaPara(null, "Centro", null));
        verify(taxas).findFirstByLojaIdAndBairroIgnoreCaseAndAtivoTrueOrderByIdDesc(1L, "Centro");
    }
}
