package br.com.bora.repository;

import br.com.bora.entity.Cliente;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O saldo de cashback é mexido no BANCO, e a conta é a certa.
 *
 * <h2>O defeito que isto fecha (varredura 1.5, medido em 21/10/2026)</h2>
 * <p>Toda mexida no saldo era ler-somar-gravar em Java: {@code findByIdAndLojaId}, mexe nos campos,
 * {@code save}. Dois checkouts do mesmo cliente ao mesmo tempo liam o <b>mesmo</b> saldo antes de
 * qualquer um gravar, e o mesmo cashback valia nos dois pedidos. É o defeito que o estoque já teve
 * (ver {@code ProdutoRepository.baixarEstoque}), agora no dinheiro do cliente. O caso realista não é
 * dois aparelhos: é o cliente clicando duas vezes em "confirmar".</p>
 *
 * <h2>Por que contra banco de verdade</h2>
 * <p>A conta <b>mudou de lugar</b>: saiu do Java e virou SQL. Mock de repositório não soma nada —
 * provaria apenas que alguém chamou alguém. O que precisa de prova aqui é a aritmética e a cláusula
 * que impede gastar duas vezes, e as duas vivem no Postgres.</p>
 *
 * <p><b>Como rodar:</b> precisa de um Postgres. Com Docker:</p>
 * <pre>
 * docker run -d --name bora-teste-pg -e POSTGRES_PASSWORD=teste -e POSTGRES_DB=bora -p 55432:5432 postgres:16
 * mvn -o test -Dtest='*IT' -DfailIfNoTests=false
 * </pre>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:55432/bora",
        "spring.datasource.username=postgres",
        "spring.datasource.password=teste",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class SaldoDeCashbackEhAtomicoIT {

    private static final Long LOJA = 9200L;

    @Autowired private ClienteRepository clientes;
    @Autowired private jakarta.persistence.EntityManager em;

    @BeforeEach
    void limpar() {
        clientes.deleteAll(clientes.findByLojaIdOrderByNomeAsc(LOJA));
    }

    private Cliente com(String saldo, String gasto, int pedidos) {
        Cliente c = new Cliente();
        c.lojaId = LOJA;
        c.nome = "Cliente do teste";
        c.telefone = "62999990000";
        c.cashback = new BigDecimal(saldo);
        c.totalGasto = new BigDecimal(gasto);
        c.qtdPedidos = pedidos;
        return clientes.save(c);
    }

    private Cliente relido(Long id) {
        return clientes.findByIdAndLojaId(id, LOJA).orElseThrow();
    }

    private static void igual(String esperado, BigDecimal real) {
        assertEquals(0, new BigDecimal(esperado).compareTo(real), "esperava " + esperado + " e veio " + real);
    }

    // =============================================================== registrar a venda

    @Test
    void registrarVENDA_debitaOresgateEcreditaOcashbackNovo() {
        // Saldo 20, pedido de 100, resgate 6,57, cashback de 5% sobre o pago = 5,00
        Cliente c = com("20.00", "500.00", 9);

        assertEquals(1, clientes.registrarVenda(c.id, LOJA, new BigDecimal("100.00"),
                new BigDecimal("6.57"), new BigDecimal("5.00"), OffsetDateTime.now()));

        Cliente d = relido(c.id);
        igual("18.43", d.cashback);        // 20 − 6,57 + 5,00
        igual("600.00", d.totalGasto);
        assertEquals(10, d.qtdPedidos);
        assertNotNull(d.ultimoPedido);
    }

    @Test
    void registrarVENDA_RECUSAquandoOsaldoNaoCOBREoResgate() {
        // A cláusula que impede gastar o mesmo saldo duas vezes.
        Cliente c = com("5.00", "0.00", 0);

        assertEquals(0, clientes.registrarVenda(c.id, LOJA, new BigDecimal("100.00"),
                new BigDecimal("6.57"), new BigDecimal("5.00"), OffsetDateTime.now()));

        Cliente d = relido(c.id);
        igual("5.00", d.cashback);
        igual("0.00", d.totalGasto);
        assertEquals(0, d.qtdPedidos, "recusar nao pode mexer em nada");
    }

    @Test
    void registrarVENDA_resgateEXATAMENTEigualAoSaldoPASSA() {
        // O limite. Recusar aqui deixaria o ultimo centavo encalhado para sempre.
        Cliente c = com("6.57", "0.00", 0);

        assertEquals(1, clientes.registrarVenda(c.id, LOJA, new BigDecimal("100.00"),
                new BigDecimal("6.57"), new BigDecimal("5.00"), OffsetDateTime.now()));

        igual("5.00", relido(c.id).cashback);
    }

    @Test
    void oMESMOsaldoNAOpagaDOISpedidos() {
        // O defeito, reproduzido. Duas vendas de resgate 6,57 com saldo 10: a segunda tem que ser
        // RECUSADA. Antes, as duas liam 10 e as duas gravavam -- desconto de graca na segunda.
        Cliente c = com("10.00", "0.00", 0);

        assertEquals(1, clientes.registrarVenda(c.id, LOJA, new BigDecimal("100.00"),
                new BigDecimal("6.57"), BigDecimal.ZERO, OffsetDateTime.now()));
        assertEquals(0, clientes.registrarVenda(c.id, LOJA, new BigDecimal("100.00"),
                new BigDecimal("6.57"), BigDecimal.ZERO, OffsetDateTime.now()),
                "o segundo pedido nao pode gastar o saldo que o primeiro ja gastou");

        igual("3.43", relido(c.id).cashback);
    }

    @Test
    void semRESGATE_aVendaSOcredita() {
        Cliente c = com("0.00", "0.00", 0);

        assertEquals(1, clientes.registrarVenda(c.id, LOJA, new BigDecimal("50.00"),
                BigDecimal.ZERO, new BigDecimal("2.50"), OffsetDateTime.now()));

        igual("2.50", relido(c.id).cashback);
    }

    @Test
    void asTRESColunasDoSaldoSaoNOTNULL() {
        // Eu escrevi um teste supondo que cliente antigo pudesse ter `total_gasto` e `qtd_pedidos`
        // nulos, e o Postgres recusou: as tres colunas sao NOT NULL com padrao 0 (medido em
        // 21/10/2026). O `coalesce` e o `greatest` nas consultas ficam como defesa -- custam nada e
        // protegem se a coluna um dia virar anulavel --, mas NAO ha caso real a cobrir.
        //
        // Este teste trava a premissa: se alguem tornar uma delas anulavel, aqui reprova e a pessoa
        // confere as contas antes de seguir.
        for (String coluna : new String[]{"cashback", "total_gasto", "qtd_pedidos"}) {
            Object anulavel = em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_name = 'cliente' and column_name = ?1")
                    .setParameter(1, coluna).getSingleResult();
            assertEquals("NO", String.valueOf(anulavel),
                    "cliente." + coluna + " virou anulavel: confira o coalesce e o greatest das "
                            + "consultas de saldo em ClienteRepository");
        }
    }

    @Test
    void naoAlcancaClienteDeOUTRAloja() {
        Cliente c = com("20.00", "0.00", 0);

        assertEquals(0, clientes.registrarVenda(c.id, LOJA + 1, new BigDecimal("10.00"),
                BigDecimal.ZERO, new BigDecimal("1.00"), OffsetDateTime.now()));

        igual("20.00", relido(c.id).cashback);
    }

    // =============================================================== debitar e creditar

    @Test
    void debitarSEtiver_debitaEdevolveUm() {
        Cliente c = com("10.00", "0.00", 0);

        assertEquals(1, clientes.debitarSeTiver(c.id, LOJA, new BigDecimal("6.57")));

        igual("3.43", relido(c.id).cashback);
    }

    @Test
    void debitarSEtiver_RECUSAquandoNaoCOBRE_eNAOmexeNoSaldo() {
        Cliente c = com("5.00", "0.00", 0);

        assertEquals(0, clientes.debitarSeTiver(c.id, LOJA, new BigDecimal("6.57")));

        igual("5.00", relido(c.id).cashback);
    }

    @Test
    void creditar_NUNCAeRecusado() {
        // Devolver cashback de venda que nao aconteceu nao pode ser barrado por nada.
        Cliente c = com("0.00", "0.00", 0);

        assertEquals(1, clientes.creditar(c.id, LOJA, new BigDecimal("6.57")));

        igual("6.57", relido(c.id).cashback);
    }

    // =============================================================== desfazer a venda

    @Test
    void desfazerVENDA_devolveOresgateEtiraOcashbackQueElaGerou() {
        // Pedido de 200 que usou 6,57 e gerou 10,00 de cashback. Saldo atual 13,43.
        Cliente c = com("13.43", "200.00", 1);

        assertEquals(1, clientes.desfazerVenda(c.id, LOJA, new BigDecimal("200.00"),
                new BigDecimal("6.57"), new BigDecimal("10.00"), 1));

        Cliente d = relido(c.id);
        igual("10.00", d.cashback);     // 13,43 + 6,57 − 10,00
        igual("0.00", d.totalGasto);
        assertEquals(0, d.qtdPedidos);
    }

    @Test
    void desfazerVENDA_oSaldoNUNCAficaNEGATIVO() {
        // O cashback que a venda gerou ja foi gasto em outro pedido. Tirar 10 de um saldo de 2 daria
        // −8; o `greatest(0, ...)` no SQL impede. Em Java era um `.max(ZERO)` que so funcionava se
        // mais ninguem mexesse no saldo no meio.
        Cliente c = com("2.00", "200.00", 1);

        clientes.desfazerVenda(c.id, LOJA, new BigDecimal("200.00"),
                BigDecimal.ZERO, new BigDecimal("10.00"), 1);

        igual("0.00", relido(c.id).cashback);
    }

    @Test
    void desfazerVENDA_oHISTORICOnuncaFicaNegativo() {
        Cliente c = com("0.00", "50.00", 1);

        clientes.desfazerVenda(c.id, LOJA, new BigDecimal("200.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, 3);

        Cliente d = relido(c.id);
        igual("0.00", d.totalGasto, "total gasto nao pode ficar negativo");
        assertEquals(0, d.qtdPedidos, "contagem de pedidos nao pode ficar negativa");
    }

    @Test
    void desfazerVENDA_pixNUNCApago_devolveSOoResgate() {
        // PIX gerado e nao pago: houve apenas o DEBITO do resgate. O historico fica intacto, porque a
        // venda nunca entrou nele. Quem chama manda pago=0, credito=0 e pedidos=0.
        Cliente c = com("0.00", "500.00", 9);

        clientes.desfazerVenda(c.id, LOJA, BigDecimal.ZERO, new BigDecimal("20.00"), BigDecimal.ZERO, 0);

        Cliente d = relido(c.id);
        igual("20.00", d.cashback);
        igual("500.00", d.totalGasto, "intacto");
        assertEquals(9, d.qtdPedidos, "intacto");
    }

    @Test
    void desfazerVENDA_naoAlcancaClienteDeOUTRAloja() {
        Cliente c = com("5.00", "100.00", 1);

        assertEquals(0, clientes.desfazerVenda(c.id, LOJA + 1, new BigDecimal("100.00"),
                new BigDecimal("5.00"), BigDecimal.ZERO, 1));

        igual("5.00", relido(c.id).cashback);
    }

    // =============================================================== a conta e do banco

    @Test
    void duasOperacoesComAentidadeLIDAantesNAOseAtropelam() {
        // Reproduz o cenario real: dois checkouts leem o cliente, cada um com saldo 10, e so depois
        // cada um mexe. Com ler-somar-gravar os dois gravavam o mesmo numero e um debito desaparecia.
        Cliente c = com("10.00", "0.00", 0);
        Cliente lidoPeloA = relido(c.id);
        Cliente lidoPeloB = relido(c.id);
        igual("10.00", lidoPeloA.cashback);
        igual("10.00", lidoPeloB.cashback);

        clientes.debitarSeTiver(lidoPeloA.id, LOJA, new BigDecimal("4.00"));
        clientes.debitarSeTiver(lidoPeloB.id, LOJA, new BigDecimal("4.00"));

        igual("2.00", relido(c.id).cashback, "os DOIS debitos tem que contar");
    }

    private static void igual(String esperado, BigDecimal real, String porque) {
        assertEquals(0, new BigDecimal(esperado).compareTo(real),
                porque + " — esperava " + esperado + " e veio " + real);
    }
}
