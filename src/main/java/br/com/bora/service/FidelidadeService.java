package br.com.bora.service;

import br.com.bora.entity.Cliente;
import br.com.bora.repository.ClienteRepository;
import br.com.bora.repository.ConfiguracaoLojaRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * Cashback e histórico do cliente.
 *
 * <p>Vale nos dois caminhos: o pedido lançado no painel (com cliente escolhido) e o pedido feito
 * pelo próprio cliente no cardápio online, onde a identificação é o telefone.</p>
 *
 * <p>O percentual é por loja ({@code configuracao_loja.cashback_percentual}, padrão 5%). Isso
 * importa porque no cardápio online <b>todo</b> pedido tem telefone — o cashback incide em 100%
 * das vendas desse canal, e o lojista precisa poder ajustar ou desligar.</p>
 */
@Service
public class FidelidadeService {

    /** Usado quando a loja ainda não tem configuração — o mesmo 5% que valia antes. */
    private static final BigDecimal PADRAO = new BigDecimal("5");

    /** O resgate nunca zera o pedido: sobra este mínimo a pagar. */
    private static final BigDecimal MINIMO_A_PAGAR = new BigDecimal("0.01");

    private final ClienteRepository clientes;
    private final ConfiguracaoLojaRepository configs;

    public FidelidadeService(ClienteRepository clientes, ConfiguracaoLojaRepository configs) {
        this.clientes = clientes;
        this.configs = configs;
    }

    /** Percentual de cashback da loja, em %. Zero desliga o recurso. */
    public BigDecimal percentual(Long lojaId) {
        return configs.findByLojaId(lojaId)
                .map(c -> c.cashbackPercentual)
                .filter(v -> v != null && v.signum() >= 0)
                .orElse(PADRAO);
    }

    public boolean ativo(Long lojaId) {
        return percentual(lojaId).signum() > 0;
    }

    /** Só dígitos — o cliente digita o telefone de um jeito diferente a cada pedido. */
    public static String normalizar(String telefone) {
        if (telefone == null) return null;
        String so = telefone.replaceAll("\\D", "");
        return so.isBlank() ? null : so;
    }

    /**
     * Encontra o cliente pelo telefone ou cria um novo. É o que permite o cashback funcionar no
     * cardápio online, onde não existe cadastro nem login.
     */
    public Long identificarPeloTelefone(Long lojaId, String nome, String telefone, String endereco) {
        String tel = normalizar(telefone);
        if (tel == null) return null;
        return clientes.findFirstByLojaIdAndTelefone(lojaId, tel)
                .map(c -> {
                    // Mantém o cadastro fresco com o que o cliente acabou de informar.
                    if (endereco != null && !endereco.isBlank()) c.endereco = endereco;
                    if ((c.nome == null || c.nome.isBlank()) && nome != null) c.nome = nome;
                    return clientes.save(c).id;
                })
                .orElseGet(() -> {
                    Cliente novo = new Cliente();
                    novo.lojaId = lojaId;
                    novo.nome = nome == null || nome.isBlank() ? "Cliente do cardápio" : nome.trim();
                    novo.telefone = tel;
                    novo.endereco = endereco;
                    return clientes.save(novo).id;
                });
    }

    public BigDecimal saldo(Long lojaId, Long clienteId) {
        if (clienteId == null) return BigDecimal.ZERO;
        return clientes.findByIdAndLojaId(clienteId, lojaId)
                .map(c -> c.cashback == null ? BigDecimal.ZERO : c.cashback)
                .orElse(BigDecimal.ZERO);
    }

    public BigDecimal saldoPeloTelefone(Long lojaId, String telefone) {
        String tel = normalizar(telefone);
        if (tel == null) return BigDecimal.ZERO;
        return clientes.findFirstByLojaIdAndTelefone(lojaId, tel)
                .map(c -> c.cashback == null ? BigDecimal.ZERO : c.cashback)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Quanto do saldo pode ser abatido deste pedido. Limita ao total menos um centavo para o
     * pedido nunca ficar zerado — um pagamento de R$ 0,00 não existe no PIX nem no caixa.
     */
    public BigDecimal resgatePossivel(Long lojaId, Long clienteId, BigDecimal total) {
        if (clienteId == null || total == null || total.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal saldo = saldo(lojaId, clienteId);
        if (saldo.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal teto = total.subtract(MINIMO_A_PAGAR);
        if (teto.signum() <= 0) return BigDecimal.ZERO;
        return saldo.min(teto).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Fecha o ciclo do pedido: soma ao histórico, debita o que foi resgatado e credita o cashback
     * novo sobre o valor efetivamente pago.
     */
    public void registrar(Long lojaId, Long clienteId, BigDecimal totalPago, BigDecimal resgate) {
        if (clienteId == null) return;
        BigDecimal usado = resgate == null ? BigDecimal.ZERO : resgate;
        BigDecimal pago = totalPago == null ? BigDecimal.ZERO : totalPago;
        BigDecimal taxa = percentual(lojaId).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
        clientes.findByIdAndLojaId(clienteId, lojaId).ifPresent(c -> {
            BigDecimal gasto = c.totalGasto == null ? BigDecimal.ZERO : c.totalGasto;
            BigDecimal saldo = c.cashback == null ? BigDecimal.ZERO : c.cashback;
            c.totalGasto = gasto.add(pago);
            c.qtdPedidos = (c.qtdPedidos == null ? 0 : c.qtdPedidos) + 1;
            c.cashback = saldo.subtract(usado)
                    .add(pago.multiply(taxa))
                    .max(BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);
            c.ultimoPedido = OffsetDateTime.now();
            clientes.save(c);
        });
    }
}
