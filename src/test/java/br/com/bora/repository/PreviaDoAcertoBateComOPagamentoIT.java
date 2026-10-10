package br.com.bora.repository;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A previa do acerto tem que mostrar o MESMO que o acerto vai pagar — contra um Postgres de verdade.
 *
 * <p><b>O bug que isto fecha (09/10/2026):</b> a {@code previaAcerto} agrupava por
 * {@code p.entregador} cru e o {@code entregasParaAcerto} casava por {@code lower(trim(...))}. Com
 * "Joao" num pedido e "joao " em outro, a tela mostrava DUAS linhas de R$ 5,00 e fechar qualquer uma
 * delas pagava R$ 10,00. O entregador e texto livre digitado em cada pedido, entao isso acontece com
 * qualquer variacao de digitacao — e ninguem percebia, porque os dois numeros vinham do sistema.</p>
 *
 * <p><b>Por que este teste e de integracao e nao unitario:</b> o defeito mora na consulta JPQL. Mock
 * de repositorio devolve o que o teste mandar e provaria exatamente nada. Este e o primeiro teste do
 * projeto que sobe o JPA contra um banco real; de quebra, qualquer erro de sintaxe nas 30+ consultas
 * {@code @Query} passa a derrubar o build, o que antes nao acontecia.</p>
 *
 * <p><b>Como rodar:</b> precisa de um Postgres. Com Docker:</p>
 * <pre>
 * docker run -d --name bora-teste-pg -e POSTGRES_PASSWORD=teste -e POSTGRES_DB=bora -p 55432:5432 postgres:16
 * mvn test -Dtest='*IT'
 * </pre>
 * <p>Sem banco no ar o teste falha em vez de passar calado — teste que se pula sozinho nao protege
 * nada (foi o que aconteceu com o {@code VersaoDosTermosTest} e o {@code assumeTrue}).</p>
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
class PreviaDoAcertoBateComOPagamentoIT {

    private static final Long LOJA = 9001L;
    private static final OffsetDateTime ONTEM = OffsetDateTime.now().minusDays(1);

    @Autowired
    private PedidoRepository pedidos;

    @BeforeEach
    void limpar() {
        pedidos.deleteAll(pedidos.findByLojaIdOrderByCriadoEmDesc(LOJA));
    }

    private void entregaDe(String entregador, String taxa) {
        Pedido p = new Pedido();
        p.lojaId = LOJA;
        p.status = StatusPedido.ENTREGUE;
        p.entregador = entregador;
        p.taxaEntrega = new BigDecimal(taxa);
        p.valorTotal = new BigDecimal("50.00");
        p.formaPagamento = "Dinheiro";
        p.criadoEm = ONTEM;
        p.entregueEm = ONTEM;
        pedidos.save(p);
    }

    private List<Object[]> previa() {
        return pedidos.previaAcerto(LOJA, ONTEM.minusDays(1), ONTEM.plusDays(1));
    }

    @Test
    void mesmoEntregadorDigitadoDeFormasDiferentesEhUmaLinhaSo() {
        entregaDe("Joao", "5.00");
        entregaDe("joao ", "5.00");
        entregaDe(" JOAO", "5.00");

        List<Object[]> linhas = previa();

        assertEquals(1, linhas.size(), "tres grafias do mesmo nome tem que dar UMA linha");
        assertEquals(3L, ((Number) linhas.get(0)[1]).longValue());
        assertEquals(0, ((BigDecimal) linhas.get(0)[2]).compareTo(new BigDecimal("15.00")),
                "a previa tem que somar as tres taxas, que e o que o acerto vai pagar");
    }

    @Test
    void oQueAPreviaMostraEhExatamenteOQueOAcertoVaiPagar() {
        entregaDe("Joao", "5.00");
        entregaDe("joao ", "5.00");

        Object[] linha = previa().get(0);
        BigDecimal mostrado = (BigDecimal) linha[2];
        String nomeExibido = (String) linha[0];

        // O "fazer acerto" busca por este nome. Antes, o nome exibido pegava as entregas das duas
        // grafias enquanto o valor mostrado era de uma so.
        BigDecimal aPagar = pedidos.entregasParaAcerto(LOJA, nomeExibido, ONTEM.minusDays(1), ONTEM.plusDays(1))
                .stream().map(p -> p.taxaEntrega).reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, mostrado.compareTo(aPagar),
                "previa mostrou " + mostrado + " e o acerto pagaria " + aPagar);
    }

    @Test
    void entregadoresDiferentesContinuamSeparados() {
        entregaDe("Joao", "5.00");
        entregaDe("Maria", "7.00");

        assertEquals(2, previa().size());
    }

    @Test
    void entregaJaAcertadaNaoApareceNaPrevia() {
        entregaDe("Joao", "5.00");
        Pedido p = pedidos.findByLojaIdOrderByCriadoEmDesc(LOJA).get(0);
        p.acertoId = 777L;
        pedidos.save(p);

        assertTrue(previa().isEmpty());
    }

    @Test
    void nomeComSoEspacosNaoViraEntregador() {
        entregaDe("   ", "5.00");

        assertTrue(previa().isEmpty(), "entregador em branco nao e entregador");
    }
}
