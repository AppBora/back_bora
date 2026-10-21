package br.com.bora.service;

import br.com.bora.entity.Produto;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O código do marketplace no cadastro de produtos: o que o lojista digita, e o que ele LÊ quando
 * digita errado.
 *
 * <h2>Os dois buracos que isto fecha</h2>
 * <ol>
 *   <li><b>Código vazio ia como string vazia.</b> O índice único do banco
 *       ({@code ux_produto_codigo_externo}) vale para todo valor preenchido, então o SEGUNDO produto
 *       salvo sem código seria recusado pelo banco — o lojista não conseguiria cadastrar nada. Vazio
 *       tem que virar NULO, que fica fora do índice.</li>
 *   <li><b>Código repetido estourava um 500 sem texto.</b> Não existe handler de
 *       {@code DataIntegrityViolationException} no projeto; o lojista via "erro no servidor" e não
 *       tinha como descobrir que o código já estava em outro produto.</li>
 * </ol>
 *
 * <p>E o motivo de os códigos terem que ser únicos não é burocracia: se dois produtos tivessem o
 * mesmo código, o pedido do iFood acharia os dois, o sistema desistiria de escolher, e voltaria a
 * não baixar estoque — o defeito que o código existe para consertar. Ver
 * {@link EstoqueDoMarketplaceTest}.</p>
 */
class CodigoDoMarketplaceNaoRepeteTest {

    private ProdutoRepository repo;
    private ProdutoService service;

    @BeforeEach
    void montar() {
        repo = mock(ProdutoRepository.class);
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(1L);
        service = new ProdutoService(repo, mock(br.com.bora.repository.ProdutoInsumoRepository.class), mock(br.com.bora.repository.ComplementoGrupoRepository.class), mock(br.com.bora.repository.ComplementoItemRepository.class), ctx);
        when(repo.save(any(Produto.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findFirstByLojaIdAndCodigoExterno(anyLong(), anyString())).thenReturn(Optional.empty());
    }

    private Produto novo(String nome, String codigo) {
        Produto p = new Produto();
        p.nome = nome;
        p.preco = new BigDecimal("10");
        p.codigoExterno = codigo;
        return p;
    }

    @Test
    void codigoVazioViraNULO_senaoOsegundoProdutoSemCodigoNaoSalva() {
        Produto salvo = service.criar(novo("Água", ""));
        assertNull(salvo.codigoExterno, "string vazia no banco colidiria no índice único");
    }

    @Test
    void soEspacoTambemViraNULO() {
        assertNull(service.criar(novo("Água", "   ")).codigoExterno);
    }

    @Test
    void oCodigoEhGravadoSemEspacoNasPontas() {
        // O lojista cola o código do iFood e vem um espaço junto. " 7470" e "7470" são o mesmo código,
        // e se gravasse com o espaço o pedido não casaria.
        assertEquals("7470", service.criar(novo("Açaí", "  7470  ")).codigoExterno);
    }

    @Test
    void codigoJaUsadoEmOutroProdutoDaMesmaLojaEhRecusadoComOnomeDoDono() {
        Produto dono = novo("Açaí 500ml", "7470");
        dono.id = 11L;
        when(repo.findFirstByLojaIdAndCodigoExterno(1L, "7470")).thenReturn(Optional.of(dono));

        var erro = assertThrows(ResponseStatusException.class, () -> service.criar(novo("Combo", "7470")));

        assertEquals(HttpStatus.CONFLICT, erro.getStatusCode(), "é conflito, não erro de servidor");
        assertTrue(erro.getReason().contains("Açaí 500ml"),
                "o lojista precisa LER qual produto já usa o código; a mensagem foi: " + erro.getReason());
        verify(repo, never()).save(any(Produto.class));
    }

    @Test
    void editarOproprioProdutoMantendoOcodigoNaoBrigaConsigoMesmo() {
        Produto existente = novo("Açaí 500ml", "7470");
        existente.id = 11L;
        existente.lojaId = 1L;
        when(repo.findByIdAndLojaId(11L, 1L)).thenReturn(Optional.of(existente));
        when(repo.findFirstByLojaIdAndCodigoExterno(1L, "7470")).thenReturn(Optional.of(existente));

        Produto dados = novo("Açaí 500ml (novo nome)", "7470");
        assertDoesNotThrow(() -> service.atualizar(11L, dados));
    }

    @Test
    void editarApagandoOcodigoFunciona() {
        Produto existente = novo("Açaí 500ml", "7470");
        existente.id = 11L;
        existente.lojaId = 1L;
        when(repo.findByIdAndLojaId(11L, 1L)).thenReturn(Optional.of(existente));

        Produto salvo = service.atualizar(11L, novo("Açaí 500ml", ""));

        assertNull(salvo.codigoExterno, "a tela manda o campo vazio para APAGAR o código");
    }

    @Test
    void produtoSemCodigoNaoConsultaOdono() {
        service.criar(novo("Água", null));
        verify(repo, never()).findFirstByLojaIdAndCodigoExterno(anyLong(), any());
    }
}
