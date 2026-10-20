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

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code apagarDaLoja}: apaga o pedido certo, da loja certa, sem depender do estado do objeto.
 *
 * <h2>Por que esta consulta existe</h2>
 * <p>O {@code excluir} passou a devolver estoque e cashback <b>antes</b> de apagar (item 5 da
 * varredura de 18/10). O estorno roda consultas com {@code clearAutomatically = true}, que limpam o
 * contexto do Hibernate — o objeto do pedido fica <b>desanexado</b>. Um {@code delete(entidade)} num
 * objeto desanexado faz {@code merge} primeiro: grava o estado que está em memória de volta na linha,
 * e só depois apaga. Funciona por acidente, e depende de um detalhe do ORM que ninguém lembra de
 * olhar quando mexe no código em volta.</p>
 *
 * <p>Isto só pode ser provado contra um banco de verdade: o que está em teste é o SQL que o Hibernate
 * gera e o que ele faz com a linha. Mock de repositório devolve o que o teste mandar.</p>
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
class ApagarPedidoNaoVazaEntreLojasIT {

    private static final Long LOJA = 9007L;
    private static final Long OUTRA_LOJA = 9008L;

    @Autowired private PedidoRepository pedidos;

    @BeforeEach
    void limpar() {
        pedidos.deleteAll(pedidos.findByLojaIdOrderByCriadoEmDesc(LOJA));
        pedidos.deleteAll(pedidos.findByLojaIdOrderByCriadoEmDesc(OUTRA_LOJA));
    }

    private Pedido novo(Long lojaId, String codigo) {
        Pedido p = new Pedido();
        p.lojaId = lojaId;
        p.codigo = codigo;
        p.status = StatusPedido.RECEBIDO;
        p.valorTotal = new BigDecimal("30.00");
        p.origem = "Balcao";
        p.criadoEm = OffsetDateTime.now();
        p.atualizadoEm = OffsetDateTime.now();
        return pedidos.save(p);
    }

    @Test
    void apagaOpedidoDaLoja() {
        Pedido p = novo(LOJA, "A1");

        assertEquals(1, pedidos.apagarDaLoja(p.id, LOJA));

        assertTrue(pedidos.findByIdAndLojaId(p.id, LOJA).isEmpty());
    }

    @Test
    void NAOapagaPedidoDeOUTRAloja() {
        // A segunda tranca de tenant. A primeira e o buscarDaLoja do servico; esta esta no SQL.
        Pedido meu = novo(LOJA, "A1");
        Pedido alheio = novo(OUTRA_LOJA, "B1");

        assertEquals(0, pedidos.apagarDaLoja(alheio.id, LOJA),
                "pedido de outra loja nao pode ser apagado");

        assertTrue(pedidos.findByIdAndLojaId(alheio.id, OUTRA_LOJA).isPresent(),
                "o pedido da outra loja tem que continuar la");
        assertTrue(pedidos.findByIdAndLojaId(meu.id, LOJA).isPresent(),
                "e o meu nao pode ter sido apagado no lugar");
    }

    @Test
    void apagarDuasVezesDevolveZeroNaSegunda() {
        // Idempotente: dois cliques na lixeira nao podem estourar.
        Pedido p = novo(LOJA, "A1");

        assertEquals(1, pedidos.apagarDaLoja(p.id, LOJA));
        assertEquals(0, pedidos.apagarDaLoja(p.id, LOJA));
    }

    @Test
    void apagaSOoPedidoPEDIDO_naoOsVizinhos() {
        Pedido a = novo(LOJA, "A1");
        Pedido b = novo(LOJA, "A2");

        pedidos.apagarDaLoja(a.id, LOJA);

        assertTrue(pedidos.findByIdAndLojaId(a.id, LOJA).isEmpty());
        assertTrue(pedidos.findByIdAndLojaId(b.id, LOJA).isPresent(), "o vizinho tem que ficar");
    }

    @Test
    void idInexistenteDevolveZeroEnaoEstoura() {
        assertEquals(0, pedidos.apagarDaLoja(999999999L, LOJA));
    }
}
