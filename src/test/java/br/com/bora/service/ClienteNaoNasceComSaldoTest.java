package br.com.bora.service;

import br.com.bora.entity.Cliente;
import br.com.bora.repository.ClienteRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Cadastro de cliente nao e porta de entrada para saldo de cashback.
 *
 * <p><b>O furo que isto fecha (09/10/2026):</b> o {@code POST /api/clientes} recebia a entidade
 * {@code Cliente} inteira e o {@code save} gravava tudo. O codigo zerava {@code id} e {@code lojaId} e
 * mais nada, entao {@code cashback}, {@code totalGasto}, {@code qtdPedidos} e os campos de marketplace
 * vinham do corpo da requisicao. Um POST com {@code "cashback": 9999} criava cliente com saldo — e
 * saldo e desconto que sai do caixa do lojista no pedido seguinte. O endpoint nao exige papel, entao
 * qualquer balconista conseguia.</p>
 *
 * <p>O outro furo: {@code excluir} nao tinha checagem de papel nenhuma. Um OPERADOR podia apagar a
 * base de clientes da loja, um por um.</p>
 */
class ClienteNaoNasceComSaldoTest {

    private ClienteRepository repo;
    private AuthContext ctx;
    private ClienteService service;

    @BeforeEach
    void montar() {
        repo = mock(ClienteRepository.class);
        ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(18L);
        when(ctx.atual()).thenReturn(new BoraPrincipal(7L, 18L, "OPERADOR", "balcao@zira.com"));
        when(repo.save(any(Cliente.class))).thenAnswer(i -> i.getArgument(0));
        service = new ClienteService(repo, ctx);
    }

    /** O corpo que um operador mal-intencionado mandaria. */
    private Cliente corpoMalicioso() {
        Cliente c = new Cliente();
        c.id = 999L;
        c.lojaId = 1L;                            // outra loja
        c.nome = "Fulano";
        c.telefone = "(15) 99999-0001";
        c.cashback = new BigDecimal("9999.00");   // saldo fabricado
        c.totalGasto = new BigDecimal("50000.00");
        c.qtdPedidos = 300;
        c.ultimoPedido = OffsetDateTime.now();
        c.canalExterno = "IFOOD";
        c.idExterno = "ped-ext-1";
        return c;
    }

    @Test
    void cashbackVindoDoCorpoEhIgnorado() {
        Cliente salvo = service.criar(corpoMalicioso());

        assertEquals(0, salvo.cashback.compareTo(BigDecimal.ZERO),
                "cliente nao nasce com saldo: saldo e dinheiro do lojista");
    }

    @Test
    void historicoDeCompraVindoDoCorpoEhIgnorado() {
        Cliente salvo = service.criar(corpoMalicioso());

        assertEquals(0, salvo.totalGasto.compareTo(BigDecimal.ZERO));
        assertEquals(0, salvo.qtdPedidos);
        assertNull(salvo.ultimoPedido);
    }

    @Test
    void camposDeMarketplaceVindosDoCorpoSaoIgnorados() {
        Cliente salvo = service.criar(corpoMalicioso());

        // Com estes campos preenchidos, o cliente falso seria casado com pedidos de iFood de verdade.
        assertNull(salvo.canalExterno);
        assertNull(salvo.idExterno);
    }

    @Test
    void aLojaEhSempreADoTokenEOIdEhNovo() {
        Cliente salvo = service.criar(corpoMalicioso());

        assertEquals(18L, salvo.lojaId);
        assertNull(salvo.id);
    }

    @Test
    void oQueEhCadastroDeVerdadeEhCopiado() {
        Cliente dados = new Cliente();
        dados.nome = "Maria";
        dados.telefone = "(15) 99999-0001";
        dados.endereco = "Rua A, 10";
        dados.bairro = "Centro";
        dados.referencia = "portao azul";

        Cliente salvo = service.criar(dados);

        assertEquals("Maria", salvo.nome);
        assertEquals("15999990001", salvo.telefone, "telefone so com digitos, igual ao do cardapio");
        assertEquals("Rua A, 10", salvo.endereco);
        assertEquals("Centro", salvo.bairro);
        assertEquals("portao azul", salvo.referencia);
    }

    @Test
    void semNomeNaoCria() {
        Cliente c = new Cliente();
        c.telefone = "15999990001";

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.criar(c));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(repo, never()).save(any());
    }

    @Test
    void operadorNaoApagaCliente() {
        Cliente c = new Cliente();
        c.id = 3L; c.lojaId = 18L; c.nome = "Maria";
        when(repo.findByIdAndLojaId(3L, 18L)).thenReturn(Optional.of(c));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(ctx).requirePapel("GERENTE", "ADMINISTRADOR_LOJA");

        assertThrows(ResponseStatusException.class, () -> service.excluir(3L));
        verify(repo, never()).delete(any());
    }

    @Test
    void gerenteApagaCliente() {
        Cliente c = new Cliente();
        c.id = 3L; c.lojaId = 18L; c.nome = "Maria";
        when(repo.findByIdAndLojaId(3L, 18L)).thenReturn(Optional.of(c));

        service.excluir(3L);

        verify(repo).delete(c);
    }
}
