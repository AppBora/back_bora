package br.com.bora.service;

import br.com.bora.entity.Cliente;
import br.com.bora.repository.ClienteRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * O telefone do cliente é a chave de busca do cardápio — e era gravado de dois jeitos.
 *
 * <p>O cardápio e os marketplaces gravam só dígitos; a tela de clientes gravava exatamente o que o
 * lojista digitasse, com máscara. Resultado: o cliente cadastrado pelo lojista não era encontrado
 * quando pedia pelo cardápio. Nascia um segundo cadastro, e o cashback acumulado ficava preso no
 * primeiro — inacessível para ele e invisível para o lojista.</p>
 */
class TelefoneDoClienteTest {

    private ClienteRepository repo;
    private ClienteService servico;

    @BeforeEach
    void montar() {
        repo = mock(ClienteRepository.class);
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(1L);
        servico = new ClienteService(repo, ctx);
        when(repo.save(any(Cliente.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Cliente comTelefone(String t) {
        Cliente c = new Cliente();
        c.nome = "Maria";
        c.telefone = t;
        return c;
    }

    private String gravado() {
        ArgumentCaptor<Cliente> c = ArgumentCaptor.forClass(Cliente.class);
        verify(repo).save(c.capture());
        return c.getValue().telefone;
    }

    @Test
    void cadastroComMascara_guardaSoOsDigitos() {
        servico.criar(comTelefone("(15) 99860-2332"));

        assertEquals("15998602332", gravado(),
                "com mascara, o cardapio nao acha este cliente e cria outro");
    }

    @Test
    void edicaoComMascara_tambemEndireita() {
        Cliente existente = comTelefone("15998602332");
        existente.id = 7L;
        existente.lojaId = 1L;
        when(repo.findByIdAndLojaId(7L, 1L)).thenReturn(Optional.of(existente));

        servico.atualizar(7L, comTelefone("15 9 9860-2332"));

        assertEquals("15998602332", gravado());
    }

    @Test
    void oQueJaVemLimpo_naoMuda() {
        servico.criar(comTelefone("11955550002"));
        assertEquals("11955550002", gravado());
    }

    @Test
    void semTelefone_continuaSemTelefone() {
        servico.criar(comTelefone("   "));
        assertNull(gravado(), "string vazia vira nulo, nao string em branco");
    }

    @Test
    void oMesmoNumeroDigitadoDeJeitosDiferentes_cai_no_mesmo_valor() {
        assertEquals(FidelidadeService.normalizar("(15) 99860-2332"),
                FidelidadeService.normalizar("15998602332"));
        assertEquals(FidelidadeService.normalizar("+55 15 99860 2332"),
                FidelidadeService.normalizar("5515998602332"));
    }
}
