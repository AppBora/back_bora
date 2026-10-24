package br.com.bora.repository;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O avanço condicional continua funcionando quando a ENTIDADE já foi mexida.
 *
 * <h2>O defeito que isto fecha — achado em 24/10/2026, já no ar</h2>
 *
 * <p>{@code PedidoService.alterarStatus} fazia {@code p.status = status} <b>antes</b> de chamar o
 * {@code avancarSeAindaEsta}. O {@code p} é uma entidade <b>gerenciada</b>, e a consulta tinha
 * {@code @Modifying(flushAutomatically = true)}: o Hibernate gravava a mudança pendente na linha
 * <b>antes</b> de rodar o UPDATE condicional. Quando o UPDATE procurava
 * {@code where p.status = :esperado}, a linha <b>já estava com o status novo</b> — casava zero, e
 * o serviço devolvia 409.</p>
 *
 * <p>Resultado: <b>nenhum pedido mudava de status</b>, nem com uma requisição só. KDS, Pedidos,
 * Entregas, cancelamento e a Frente de Caixa (que fecha a venda e marca ENTREGUE em seguida) —
 * todos parados, com "Outra tela mudou este pedido agora" sem nenhuma outra tela existir.</p>
 *
 * <h2>Por que nenhum teste pegou</h2>
 *
 * <p>O {@code AvancoDeStatusNaoAconteceDuasVezesIT} chama o repositório <b>direto</b>, com a
 * entidade limpa, e por isso passa. Os testes de serviço <b>mockam</b> o {@code avancarSeAindaEsta}
 * para devolver 1, e por isso também passam. Ninguém exercitava a combinação que a aplicação usa:
 * entidade gerenciada e suja + UPDATE condicional no mesmo contexto.</p>
 *
 * <p>Como rodar: precisa do Postgres de teste em 55432 (ver o javadoc do
 * {@code PreviaDoAcertoBateComOPagamentoIT}).</p>
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
class AvancarComEntidadeSujaIT {

    private static final Long LOJA = 9043L;

    @Autowired private PedidoRepository pedidos;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    private Long pedidoId;

    @BeforeEach
    void semear() {
        pedidos.deleteAll();
        Pedido p = new Pedido();
        p.lojaId = LOJA;
        p.codigo = "SUJO-1";
        p.status = StatusPedido.RECEBIDO;
        p.valorTotal = new BigDecimal("50.00");
        p.criadoEm = OffsetDateTime.now();
        p.atualizadoEm = OffsetDateTime.now();
        pedidoId = pedidos.saveAndFlush(p).id;
    }

    /**
     * PREMISSA — a armadilha, provada: entidade mexida antes = avanço perdido.
     *
     * <p>Este teste <b>documenta o comportamento do Hibernate</b>, não um defeito a consertar.
     * Tentei resolver tirando o {@code flushAutomatically} da consulta e <b>não adiantou</b>: o
     * auto-flush antes de uma consulta que toca a mesma tabela é do Hibernate, não do Spring
     * Data. A consulta não tem como se defender.</p>
     *
     * <p>Por isso o conserto é a ORDEM em quem chama, e o teste abaixo
     * ({@link #ordemCerta_primeiroOavanco_depoisAentidade}) é o que guarda a correção.</p>
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void entidadeMexidaAntes_oAvancoPERDE() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        Integer linhas = tx.execute(st -> {
            Pedido p = pedidos.findById(pedidoId).orElseThrow();   // gerenciado
            StatusPedido anterior = p.status;

            // Era ISTO que o serviço fazia, e que travava a operação inteira.
            p.status = StatusPedido.CONFIRMADO;
            p.atualizadoEm = OffsetDateTime.now();

            return pedidos.avancarSeAindaEsta(pedidoId, LOJA, anterior,
                    StatusPedido.CONFIRMADO, p.atualizadoEm);
        });

        assertEquals(0, linhas,
                "o Hibernate deixou de gravar a mudança pendente antes da consulta — se isso mudou, "
                        + "reveja o comentário em PedidoRepository.avancarSeAindaEsta, que explica "
                        + "esta armadilha para quem vier depois");
    }

    /**
     * PREMISSA: a ordem certa — primeiro o UPDATE condicional, só depois a entidade.
     *
     * <p>É o que o {@code PedidoService.alterarStatus} faz desde o conserto de 24/10/2026. Se
     * alguém voltar a mexer na entidade antes, este teste cai.</p>
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void ordemCerta_primeiroOavanco_depoisAentidade() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        Integer linhas = tx.execute(st -> {
            Pedido p = pedidos.findById(pedidoId).orElseThrow();
            StatusPedido anterior = p.status;
            OffsetDateTime agora = OffsetDateTime.now();

            int ganhou = pedidos.avancarSeAindaEsta(pedidoId, LOJA, anterior,
                    StatusPedido.CONFIRMADO, agora);

            // só agora a entidade
            p.status = StatusPedido.CONFIRMADO;
            p.atualizadoEm = agora;
            pedidos.save(p);
            return ganhou;
        });

        assertEquals(1, linhas, "o avanço perdeu mesmo com a ordem certa");

        Pedido depois = pedidos.findById(pedidoId).orElseThrow();
        assertEquals(StatusPedido.CONFIRMADO, depois.status, "o status não chegou no banco");
    }

    /**
     * PREMISSA: o serviço não voltou a mexer na entidade antes do avanço.
     *
     * <p>Os testes acima provam o mecanismo no repositório. Este guarda o CAMINHO REAL: nenhum
     * dos testes de serviço veria o problema, porque todos eles <b>simulam</b> o
     * {@code avancarSeAindaEsta} devolvendo 1 — foi por isso que o defeito passou.</p>
     */
    @Test
    void oServicoNaoMexeNoPedidoAntesDoAvanco() throws Exception {
        String fonte = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/br/com/bora/service/PedidoService.java"));

        // DENTRO DO alterarStatus, e não do arquivo inteiro. A primeira versão deste teste
        // ancorava na primeira ocorrência de "StatusPedido anterior = p.status;" do arquivo — que
        // é de OUTRO método (o cancelamento vindo do marketplace, que nem usa o avanço
        // condicional). Ele estava medindo o trecho errado.
        int metodo = fonte.indexOf("public Pedido alterarStatus(");
        assertTrue(metodo > 0, "não achei o alterarStatus — este teste parou de medir");
        int avanco = fonte.indexOf("repo.avancarSeAindaEsta(", metodo);
        assertTrue(avanco > metodo, "o alterarStatus não chama mais o avanço condicional");

        // SEM OS COMENTÁRIOS: o comentário que explica o defeito cita `p.status = status` em
        // texto, e a busca crua casava com ele. Teste que não distingue código de comentário
        // mente nos dois sentidos.
        String antesDoAvanco = fonte.substring(metodo, avanco)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");
        assertFalse(antesDoAvanco.contains("p.status = status"),
                "o serviço voltou a mexer no status antes do avanço condicional — isso trava a "
                        + "mudança de status da operação inteira (ver o comentário no repositório)");
    }

    /**
     * A trava de concorrência tem de continuar valendo: quem chega com o status velho perde.
     *
     * <p>Sem isto, "consertar" o 409 removendo a condição passaria neste arquivo — e devolveria o
     * defeito de 23/10, em que a cozinha e o balcão avançavam o mesmo pedido e o cliente levava
     * dois avisos do mesmo passo no WhatsApp.</p>
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void quemChegaComOstatusVelho_perde() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        Integer primeiro = tx.execute(st -> pedidos.avancarSeAindaEsta(pedidoId, LOJA,
                StatusPedido.RECEBIDO, StatusPedido.CONFIRMADO, OffsetDateTime.now()));
        assertEquals(1, primeiro, "o primeiro avanço tinha de ganhar");

        // O segundo ainda acha que está em RECEBIDO — é a tela que não recarregou.
        Integer segundo = tx.execute(st -> pedidos.avancarSeAindaEsta(pedidoId, LOJA,
                StatusPedido.RECEBIDO, StatusPedido.EM_PREPARO, OffsetDateTime.now()));
        assertEquals(0, segundo,
                "a trava de concorrência caiu: duas telas voltam a avançar o mesmo pedido");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void pedidoDeOutraLoja_naoEhTocado() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Integer linhas = tx.execute(st -> pedidos.avancarSeAindaEsta(pedidoId, LOJA + 1,
                StatusPedido.RECEBIDO, StatusPedido.CONFIRMADO, OffsetDateTime.now()));
        assertEquals(0, linhas, "avançou pedido de outra loja");
    }
}
