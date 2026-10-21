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
        servico = new ClienteService(repo, mock(br.com.bora.repository.PedidoRepository.class), ctx);
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

    // ---------------------------------------------------------- telefone repetido (item 6.10)

    /** Já existe um cliente com este telefone na loja. */
    private Cliente jaExiste(Long id, String nome, String telefone) {
        Cliente outro = new Cliente();
        outro.id = id;
        outro.lojaId = 1L;
        outro.nome = nome;
        outro.telefone = telefone;
        when(repo.findFirstByLojaIdAndTelefone(1L, telefone)).thenReturn(Optional.of(outro));
        return outro;
    }

    /**
     * Telefone que já é de outro cliente é RECUSADO.
     *
     * <h2>Item 6.10 da varredura</h2>
     * <p>O cadastro não procurava pelo telefone. O balconista cadastrava "Maria" de novo no dia
     * seguinte e a loja ficava com dois cadastros da mesma pessoa — <b>e o cashback racha</b>: parte
     * presa num, parte no outro, e o cliente reclamando de um saldo que a tela não mostra. Pior: o
     * telefone é a chave que o cardápio e os marketplaces usam para reencontrar a pessoa, então o
     * pedido cai sempre no primeiro cadastro que o banco devolver.</p>
     *
     * <p><b>Recusar e não juntar:</b> juntar sobrescreveria nome e endereço de alguém com o que o
     * operador acabou de digitar — e se o telefone foi digitado errado, o estrago cai no cadastro de
     * outra pessoa, com o saldo dela.</p>
     */
    @Test
    void telefoneQUEjaEHdeOUTROclienteEHrecusado() {
        jaExiste(55L, "Maria Silva", "62999990000");

        var erro = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> servico.criar(comTelefone("(62) 99999-0000")));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, erro.getStatusCode());
        verify(repo, never()).save(any(Cliente.class));
    }

    @Test
    void aRECUSAdizDEQUEMehOtelefone() {
        // E a informacao que falta para decidir: sem o nome, o operador nao sabe se e a mesma pessoa
        // ou se ele digitou o numero errado.
        jaExiste(55L, "Maria Silva", "62999990000");

        var erro = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> servico.criar(comTelefone("62999990000")));

        assertTrue(erro.getReason().contains("Maria Silva"), "foi: " + erro.getReason());
        assertTrue(erro.getReason().contains("cashback"), "e tem que dizer o que se perde");
    }

    @Test
    void aCOMPARACAOehPELOtelefoneNORMALIZADO() {
        // O cadastro antigo pode ter mascara e o novo nao (ou o contrario). Comparar o texto cru
        // deixaria passar o duplicado -- que e justamente o defeito que isto fecha.
        jaExiste(55L, "Maria", "62999990000");

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> servico.criar(comTelefone("(62) 9 9999-0000")));
    }

    @Test
    void telefoneEMBRANCOnaoEHdisputado() {
        // Cliente de balcao que nao quis dar o numero continua entrando, e e assim que a maioria
        // entra. Recusar aqui travaria a venda.
        when(repo.findFirstByLojaIdAndTelefone(eq(1L), any())).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> servico.criar(comTelefone(null)));
        assertDoesNotThrow(() -> servico.criar(comTelefone("")));
        verify(repo, times(2)).save(any(Cliente.class));
        verify(repo, never()).findFirstByLojaIdAndTelefone(anyLong(), any());
    }

    @Test
    void telefoneLIVREcontinuaSENDOcadastrado() {
        when(repo.findFirstByLojaIdAndTelefone(eq(1L), any())).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> servico.criar(comTelefone("62988887777")));
        verify(repo).save(any(Cliente.class));
    }

    @Test
    void oCLIENTEnaoBRIGAcomOproprioTELEFONEaoSEReditado() {
        // Sem isto, editar o endereco de um cliente sem mexer no telefone seria recusado -- e o
        // caminho de corrigir cadastro, que e o item 7.12, morreria no primeiro clique.
        Cliente maria = jaExiste(55L, "Maria", "62999990000");
        when(repo.findByIdAndLojaId(55L, 1L)).thenReturn(Optional.of(maria));

        Cliente novo = comTelefone("(62) 99999-0000");
        novo.endereco = "Rua Nova, 500";

        assertDoesNotThrow(() -> servico.atualizar(55L, novo));
        verify(repo).save(maria);
    }

    @Test
    void editarPARAoTELEFONEdeOUTROehRECUSADO() {
        Cliente joao = new Cliente();
        joao.id = 56L;
        joao.lojaId = 1L;
        joao.nome = "Joao";
        when(repo.findByIdAndLojaId(56L, 1L)).thenReturn(Optional.of(joao));
        jaExiste(55L, "Maria", "62999990000");

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> servico.atualizar(56L, comTelefone("62999990000")));
    }
}
