package br.com.bora.service;

import br.com.bora.entity.Cliente;
import br.com.bora.repository.ClienteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * O cardápio público não escreve no cadastro de um cliente que já existe.
 *
 * <h2>O defeito que isto fecha (item 8.6)</h2>
 * <p>Isto já tinha sido fechado <b>em parte</b>: antes, qualquer pessoa que digitasse o telefone de um
 * cliente <b>reescrevia</b> o endereço cadastrado dele, e a entrega seguinte da loja ia para o lugar
 * errado. O conserto de então passou a só <b>completar o que estava em branco</b>.</p>
 *
 * <p>Só que "em branco" também é porta. Telefone de cliente não é segredo — está no grupo do bairro,
 * no comprovante, na agenda de muita gente. Um estranho identificado só pelo telefone gravava o
 * endereço <b>dele</b> no cadastro de um cliente que estava sem endereço, e a próxima entrega da loja
 * para aquele cliente ia para lá.</p>
 *
 * <h2>E não se perde nada fechando</h2>
 * <p>Desde a V48 o endereço da entrega fica no <b>próprio pedido</b>
 * ({@code pedido.endereco_entrega}), que é o que o motoboy lê — o cadastro não participa da entrega.
 * Cadastro se corrige na tela de Clientes, por alguém logado. Fechar tira risco e não tira função.</p>
 */
class OcardapioNaoEscreveNoCadastroTest {

    private static final Long LOJA = 18L;
    private static final String TEL = "5515998887777";

    private ClienteRepository clientes;
    private FidelidadeService fidelidade;

    @BeforeEach
    void montar() {
        clientes = mock(ClienteRepository.class);
        when(clientes.save(any())).thenAnswer(inv -> {
            Cliente c = inv.getArgument(0);
            if (c.id == null) c.id = 900L;
            return c;
        });
        fidelidade = new FidelidadeService(clientes,
                mock(br.com.bora.repository.ConfiguracaoLojaRepository.class));
    }

    private Cliente jaCadastrado(String nome, String endereco) {
        Cliente c = new Cliente();
        c.id = 42L;
        c.lojaId = LOJA;
        c.nome = nome;
        c.telefone = TEL;
        c.endereco = endereco;
        when(clientes.findFirstByLojaIdAndTelefone(LOJA, TEL)).thenReturn(Optional.of(c));
        return c;
    }

    // =============================================================== o cliente que ja existe

    @Test
    void oESTRANHOnaoGRAVAoENDERECOdeleNOcadastroALHEIO() {
        // O cadastro esta sem endereco (importado de marketplace, por exemplo). Um estranho pede pelo
        // cardapio com o telefone da vitima e o endereco DELE.
        Cliente vitima = jaCadastrado("Maria", null);

        fidelidade.identificarPeloTelefone(LOJA, "Maria", TEL, "Rua do Estranho, 999");

        assertNull(vitima.endereco,
                "o cardapio nao pode gravar endereco em cadastro que ja existe: a entrega seguinte "
                        + "da loja iria para la");
        verify(clientes, never()).save(any());
    }

    @Test
    void eMUITOmenosSOBREoqueJAestavaLA() {
        Cliente vitima = jaCadastrado("Maria", "Rua da Maria, 10");

        fidelidade.identificarPeloTelefone(LOJA, "Maria", TEL, "Rua do Estranho, 999");

        assertEquals("Rua da Maria, 10", vitima.endereco);
    }

    @Test
    void oPEDIDOeRECONHECIDOdoMESMOcliente() {
        // O que a funcao existe para fazer continua funcionando: achar o cadastro pelo telefone, para
        // o cashback e o historico cairem na pessoa certa.
        jaCadastrado("Maria", null);

        assertEquals(42L, fidelidade.identificarPeloTelefone(LOJA, "Maria", TEL, "Rua X, 1"));
    }

    @Test
    void oNOMEemBRANCOaindaEcompletado() {
        // Nome em branco e outra historia: cadastro de marketplace nasce "Cliente do cardapio", e um
        // nome e informacao de atendimento, nao de entrega -- nao manda comida para lugar nenhum.
        Cliente c = jaCadastrado(null, null);

        fidelidade.identificarPeloTelefone(LOJA, "Maria Silva", TEL, "Rua X, 1");

        assertEquals("Maria Silva", c.nome);
        verify(clientes).save(c);
    }

    @Test
    void comNOMEeENDERECOjaPREENCHIDOS_naoGRAVAnada() {
        // Uma escrita a menos em cadastro de cliente a cada pedido do cardapio.
        jaCadastrado("Maria", "Rua da Maria, 10");

        fidelidade.identificarPeloTelefone(LOJA, "Maria", TEL, "Rua Y, 2");

        verify(clientes, never()).save(any());
    }

    // =============================================================== o cliente novo

    @Test
    void clienteNOVOnasceComOqueELEmesmoINFORMOU() {
        // Aqui nao ha vitima: o cadastro nao existia, e quem esta pedindo e quem esta digitando. A
        // loja quer o cadastro para o CRM e para o cashback.
        when(clientes.findFirstByLojaIdAndTelefone(LOJA, TEL)).thenReturn(Optional.empty());

        fidelidade.identificarPeloTelefone(LOJA, "Joao", TEL, "Rua do Joao, 5");

        ArgumentCaptor<Cliente> novo = ArgumentCaptor.forClass(Cliente.class);
        verify(clientes).save(novo.capture());
        assertEquals("Joao", novo.getValue().nome);
        assertEquals("Rua do Joao, 5", novo.getValue().endereco);
        assertEquals(LOJA, novo.getValue().lojaId);
    }

    @Test
    void semTELEFONEnaoHAcadastro() {
        assertNull(fidelidade.identificarPeloTelefone(LOJA, "Joao", null, "Rua X"));
        verify(clientes, never()).save(any());
        verify(clientes, never()).findFirstByLojaIdAndTelefone(anyLong(), any());
    }
}
