package br.com.bora.service;

import br.com.bora.entity.Cliente;
import br.com.bora.repository.ClienteRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Um dublê FIEL das quatro operações de saldo do {@code ClienteRepository}.
 *
 * <h2>Por que isto existe</h2>
 * <p>Em 21/10/2026 toda mexida no saldo de cashback saiu do Java e virou SQL (varredura 1.5): era
 * ler-somar-gravar, e dois checkouts do mesmo cliente ao mesmo tempo gastavam o mesmo saldo duas
 * vezes.</p>
 *
 * <p>Com isso, um mock comum de repositório deixou de somar nada — e os testes que provavam
 * <b>a conta</b> (cancelar devolve o resgate, cancelar tira o cashback que a venda gerou, dez pedidos
 * falsos não fabricam saldo) passariam a provar apenas que alguém chamou alguém. Esses cenários valem
 * mais que o mecanismo, então o dublê faz a mesma aritmética do SQL.</p>
 *
 * <h2>O limite deste dublê, e ele importa</h2>
 * <p>Isto é uma <b>cópia</b> da conta que está no SQL. Se as duas divergirem, este dublê mente. O que
 * prova o SQL de verdade é o {@code SaldoDeCashbackEhAtomicoIT}, contra Postgres — inclusive a
 * cláusula que recusa gastar o que não tem e o {@code greatest} que impede saldo negativo. Mexeu numa
 * das consultas de saldo? Mexa aqui também, e o IT é quem diz se ficou igual.</p>
 */
final class SaldoEmMemoria {

    private SaldoEmMemoria() {
    }

    /** Faz o mock do repositório mexer no {@code cliente} como o banco mexeria. */
    static void instalar(ClienteRepository mock, Cliente cliente) {
        Long id = cliente.id;
        Long loja = cliente.lojaId;
        when(mock.findByIdAndLojaId(id, loja)).thenReturn(Optional.of(cliente));
        when(mock.save(any(Cliente.class))).thenAnswer(i -> i.getArgument(0));
        // O cliente deste duble EXISTE. A pergunta separa "saldo nao cobre" de "cliente foi apagado",
        // e aqui o caso e sempre o primeiro.
        when(mock.existsByIdAndLojaId(id, loja)).thenReturn(true);

        // update cliente set cashback = cashback - :usado + :credito, total_gasto = ... + :pago,
        //                    qtd_pedidos = ... + 1, ultimo_pedido = :agora
        //  where id/loja casam and coalesce(cashback,0) >= :usado
        when(mock.registrarVenda(eq(id), eq(loja), any(), any(), any(), any())).thenAnswer(i -> {
            BigDecimal pago = i.getArgument(2);
            BigDecimal usado = i.getArgument(3);
            BigDecimal credito = i.getArgument(4);
            if (saldo(cliente).compareTo(usado) < 0) return 0;   // a clausula que recusa
            cliente.cashback = saldo(cliente).subtract(usado).add(credito).setScale(2, RoundingMode.HALF_UP);
            cliente.totalGasto = gasto(cliente).add(pago);
            cliente.qtdPedidos = (cliente.qtdPedidos == null ? 0 : cliente.qtdPedidos) + 1;
            cliente.ultimoPedido = i.getArgument(5);
            return 1;
        });

        // update cliente set cashback = cashback - :valor where ... and coalesce(cashback,0) >= :valor
        when(mock.debitarSeTiver(eq(id), eq(loja), any())).thenAnswer(i -> {
            BigDecimal valor = i.getArgument(2);
            if (saldo(cliente).compareTo(valor) < 0) return 0;
            cliente.cashback = saldo(cliente).subtract(valor).setScale(2, RoundingMode.HALF_UP);
            return 1;
        });

        // update cliente set cashback = coalesce(cashback,0) + :valor where ...
        when(mock.creditar(eq(id), eq(loja), any())).thenAnswer(i -> {
            cliente.cashback = saldo(cliente).add((BigDecimal) i.getArgument(2))
                    .setScale(2, RoundingMode.HALF_UP);
            return 1;
        });

        // update cliente set cashback = greatest(0, cashback + :usado - :credito),
        //                    total_gasto = greatest(0, total_gasto - :pago),
        //                    qtd_pedidos = greatest(0, qtd_pedidos - :pedidos)
        when(mock.desfazerVenda(eq(id), eq(loja), any(), any(), any(), anyInt())).thenAnswer(i -> {
            BigDecimal pago = i.getArgument(2);
            BigDecimal usado = i.getArgument(3);
            BigDecimal credito = i.getArgument(4);
            int pedidos = i.getArgument(5);
            cliente.cashback = saldo(cliente).add(usado).subtract(credito)
                    .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            cliente.totalGasto = gasto(cliente).subtract(pago).max(BigDecimal.ZERO);
            cliente.qtdPedidos = Math.max(0, (cliente.qtdPedidos == null ? 0 : cliente.qtdPedidos) - pedidos);
            return 1;
        });
    }

    private static BigDecimal saldo(Cliente c) {
        return c.cashback == null ? BigDecimal.ZERO : c.cashback;
    }

    private static BigDecimal gasto(Cliente c) {
        return c.totalGasto == null ? BigDecimal.ZERO : c.totalGasto;
    }
}
