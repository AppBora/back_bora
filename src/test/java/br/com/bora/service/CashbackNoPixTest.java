package br.com.bora.service;

import br.com.bora.entity.Cliente;
import br.com.bora.repository.ClienteRepository;
import br.com.bora.repository.ConfiguracaoLojaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * O cashback gasto num pedido PIX precisa sair do saldo.
 *
 * <p>No cardápio o desconto saía do total na hora, mas quem registrava o consumo era o webhook do
 * pagamento — e ele passava resgate ZERO, porque o valor usado não era guardado em lugar nenhum
 * (só num texto dentro da observação). Resultado: o cliente gastava os mesmos R$ 20 em quantos
 * pedidos PIX quisesse, e ainda ganhava cashback novo sobre o valor já descontado. Quem pagava a
 * diferença era o lojista.</p>
 */
class CashbackNoPixTest {

    private ClienteRepository clientes;
    private FidelidadeService fidelidade;
    private Cliente maria;

    @BeforeEach
    void montar() {
        clientes = mock(ClienteRepository.class);
        fidelidade = new FidelidadeService(clientes, mock(ConfiguracaoLojaRepository.class));
        maria = new Cliente();
        maria.id = 42L;
        maria.lojaId = 1L;
        maria.nome = "Maria";
        maria.cashback = new BigDecimal("20.00");
        when(clientes.findByIdAndLojaId(42L, 1L)).thenReturn(Optional.of(maria));
        when(clientes.save(any(Cliente.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void usarCashbackNoPix_tiraDoSaldoNaHora() {
        fidelidade.consumir(1L, 42L, new BigDecimal("20.00"));

        assertEquals(0, BigDecimal.ZERO.compareTo(maria.cashback),
                "era assim que o mesmo saldo valia em pedido atras de pedido");
    }

    @Test
    void oMesmoSaldoNaoPagaDoisPedidos() {
        fidelidade.consumir(1L, 42L, new BigDecimal("20.00")); // 1º pedido PIX
        fidelidade.consumir(1L, 42L, new BigDecimal("20.00")); // 2º, antes de pagar o primeiro

        assertEquals(0, BigDecimal.ZERO.compareTo(maria.cashback), "saldo nunca fica negativo");
    }

    @Test
    void pixAbandonado_devolveOSaldo() {
        fidelidade.consumir(1L, 42L, new BigDecimal("20.00"));
        fidelidade.devolver(1L, 42L, new BigDecimal("20.00")); // o cobrador cancelou o pedido

        assertEquals(0, new BigDecimal("20.00").compareTo(maria.cashback),
                "venda que nao aconteceu nao pode comer o saldo do cliente");
    }

    @Test
    void pagamentoConfirmado_creditaSobreOPagoSemDebitarDeNovo() {
        fidelidade.consumir(1L, 42L, new BigDecimal("20.00")); // no pedido
        when(clientes.findByIdAndLojaId(42L, 1L)).thenReturn(Optional.of(maria));

        // o webhook credita o cashback novo, com resgate ZERO: o debito ja aconteceu
        fidelidade.registrar(1L, 42L, new BigDecimal("30.00"), BigDecimal.ZERO);

        assertTrue(maria.cashback.compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(maria.cashback.compareTo(new BigDecimal("20.00")) < 0,
                "o saldo nao pode voltar ao que era: os R$ 20 foram gastos");
    }

    @Test
    void valorZeradoOuNulo_naoMexeNoSaldo() {
        fidelidade.consumir(1L, 42L, BigDecimal.ZERO);
        fidelidade.consumir(1L, 42L, null);
        fidelidade.devolver(1L, 42L, BigDecimal.ZERO);
        fidelidade.consumir(1L, null, new BigDecimal("5.00"));

        assertEquals(0, new BigDecimal("20.00").compareTo(maria.cashback));
        verify(clientes, never()).save(any(Cliente.class));
    }
}
