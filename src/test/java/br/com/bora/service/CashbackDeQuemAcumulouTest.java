package br.com.bora.service;

import br.com.bora.entity.Cliente;
import br.com.bora.repository.ClienteRepository;
import br.com.bora.repository.ConfiguracaoLojaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * O saldo é de quem o acumulou.
 *
 * <p>Telefone de cliente não é segredo: está no grupo do bairro, no comprovante, na agenda de muita
 * gente. Com ele e só ele, qualquer pessoa consultava o saldo alheio no cardápio e gastava o
 * cashback do vizinho. Pedir também o primeiro nome do cadastro não é autenticação de verdade — isso
 * exige um código por WhatsApp — mas tira o caso de quem só tem o número na mão.</p>
 */
class CashbackDeQuemAcumulouTest {

    private ClienteRepository clientes;
    private FidelidadeService fidelidade;

    @BeforeEach
    void montar() {
        clientes = mock(ClienteRepository.class);
        fidelidade = new FidelidadeService(clientes, mock(ConfiguracaoLojaRepository.class));

        Cliente maria = new Cliente();
        maria.id = 42L;
        maria.lojaId = 1L;
        maria.nome = "Maria Eduarda Alves";
        maria.telefone = "15991230011";
        maria.cashback = new BigDecimal("30.00");
        when(clientes.findFirstByLojaIdAndTelefone(1L, "15991230011")).thenReturn(Optional.of(maria));
        when(clientes.findByIdAndLojaId(42L, 1L)).thenReturn(Optional.of(maria));
    }

    @Test
    void comOTelefoneSozinho_oSaldoNaoAparece() {
        assertEquals(0, BigDecimal.ZERO.compareTo(fidelidade.saldoPeloTelefone(1L, "15991230011", null)),
                "era assim que dava para varrer saldo dos outros");
        assertEquals(0, BigDecimal.ZERO.compareTo(fidelidade.saldoPeloTelefone(1L, "15991230011", "Joao")));
    }

    @Test
    void comONomeCerto_oSaldoAparece() {
        assertEquals(0, new BigDecimal("30.00").compareTo(
                fidelidade.saldoPeloTelefone(1L, "15991230011", "Maria Eduarda Alves")));
    }

    @Test
    void soOPrimeiroNomeBasta_eAcentoOuCaixaNaoAtrapalham() {
        assertEquals(0, new BigDecimal("30.00").compareTo(
                fidelidade.saldoPeloTelefone(1L, "15991230011", "maria")), "quem digita so o primeiro nome");
        assertTrue(FidelidadeService.nomeConfere("Antônio Silva", "antonio"), "acento nao pode barrar o dono");
        assertTrue(FidelidadeService.nomeConfere("JOSÉ", "josé"));
        assertTrue(FidelidadeService.nomeConfere("  Ana  ", "ana carolina"));
    }

    @Test
    void nomeDiferenteNaoGastaOCashbackAlheio() {
        assertFalse(fidelidade.ehOMesmoCliente(1L, 42L, "Carlos"));
        assertFalse(fidelidade.ehOMesmoCliente(1L, 42L, ""));
        assertFalse(fidelidade.ehOMesmoCliente(1L, 42L, null));
        assertTrue(fidelidade.ehOMesmoCliente(1L, 42L, "Maria"));
    }

    @Test
    void clienteNovo_naoTemSaldoParaNinguemGastar() {
        assertFalse(fidelidade.ehOMesmoCliente(1L, null, "Maria"));
        assertEquals(0, BigDecimal.ZERO.compareTo(fidelidade.saldoPeloTelefone(1L, "15999999999", "Maria")));
    }

    @Test
    void enderecoDoCadastroNaoEhSobrescritoPorQuemSabeOTelefone() {
        Cliente maria = clientes.findByIdAndLojaId(42L, 1L).orElseThrow();
        maria.endereco = "Rua XV de Novembro, 320";
        when(clientes.save(any(Cliente.class))).thenAnswer(i -> i.getArgument(0));

        fidelidade.identificarPeloTelefone(1L, "Golpista", "15991230011", "Rua do Golpista, 1");

        assertEquals("Rua XV de Novembro, 320", maria.endereco,
                "a proxima entrega dela iria para o endereco do golpista");
    }
}
