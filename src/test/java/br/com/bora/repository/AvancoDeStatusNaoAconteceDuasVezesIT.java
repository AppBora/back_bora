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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Duas telas não avançam o mesmo pedido ao mesmo tempo.
 *
 * <p><b>O defeito que isto fecha:</b> {@code alterarStatus} lia o pedido, validava o avanço contra o
 * valor LIDO e gravava. A cozinha e o balcão, no pico, leem o mesmo {@code RECEBIDO} antes de
 * qualquer uma gravar: as duas passam pela validação e as duas gravam. Medido em 23/10/2026, em
 * requisições de verdade contra a API: em <b>2 de 10</b> tentativas as duas receberam 200, e o
 * cliente levou DOIS avisos do mesmo passo no WhatsApp.</p>
 *
 * <p><b>Por que com duas threads de verdade.</b> Os ITs de concorrência que já existiam aqui —
 * {@code EstoqueNaoPerdeBaixaIT}, {@code SaldoDeCashbackEhAtomicoIT} — reproduzem a leitura velha
 * numa thread só, e os próprios comentários deles dizem isso. Isso prova que a consulta é atômica,
 * mas não prova o que acontece quando duas conexões disputam a MESMA linha. Aqui as duas threads
 * esperam numa barreira e largam juntas, cada uma na sua transação e na sua conexão.</p>
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
class AvancoDeStatusNaoAconteceDuasVezesIT {

    private static final Long LOJA = 9042L;

    @Autowired private PedidoRepository pedidos;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    private Long pedidoId;

    @BeforeEach
    void semear() {
        pedidos.deleteAll();
        Pedido p = new Pedido();
        p.lojaId = LOJA;
        p.codigo = "CONC-1";
        p.status = StatusPedido.RECEBIDO;
        p.valorTotal = new BigDecimal("50.00");
        p.criadoEm = OffsetDateTime.now();
        p.atualizadoEm = OffsetDateTime.now();
        pedidoId = pedidos.saveAndFlush(p).id;
    }

    /**
     * Uma tentativa de avanço, na sua própria transação, como uma requisição faria.
     *
     * <p>A transação não é decoração: {@code @Modifying} sem transação estoura em "No EntityManager
     * with actual transaction available".</p>
     */
    private int tentarAvancar(Long loja, StatusPedido de, StatusPedido para) {
        var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        Integer linhas = tx.execute(st ->
                pedidos.avancarSeAindaEsta(pedidoId, loja, de, para, OffsetDateTime.now()));
        return linhas == null ? 0 : linhas;
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void duasThreadsDisputandoOmesmoPedido_soUMAganha() throws Exception {
        final int RODADAS = 15;
        int ganhosTotais = 0;

        for (int rodada = 0; rodada < RODADAS; rodada++) {
            semear();
            CyclicBarrier largada = new CyclicBarrier(2);
            AtomicInteger ganhou = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(2);

            Callable<Integer> tentativa = () -> {
                largada.await(5, TimeUnit.SECONDS);   // as duas largam juntas, de verdade
                var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
                Integer linhas = tx.execute(st -> pedidos.avancarSeAindaEsta(
                        pedidoId, LOJA, StatusPedido.RECEBIDO, StatusPedido.EM_PREPARO,
                        OffsetDateTime.now()));
                if (linhas != null && linhas == 1) ganhou.incrementAndGet();
                return linhas == null ? 0 : linhas;
            };

            Future<Integer> a = pool.submit(tentativa);
            Future<Integer> b = pool.submit(tentativa);
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
            pool.shutdown();

            assertEquals(1, ganhou.get(),
                    "rodada " + rodada + ": " + ganhou.get() + " threads acharam que avancaram o "
                            + "pedido. Com 2, o cliente leva dois avisos do mesmo passo.");
            ganhosTotais += ganhou.get();
        }

        assertEquals(RODADAS, ganhosTotais);
        assertEquals(StatusPedido.EM_PREPARO, pedidos.findById(pedidoId).orElseThrow().status);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void PREMISSA_doJEITOantigo_asDUASacreditamQueAvancaram() throws Exception {
        // Reproduz o que o servico fazia: le a entidade, confere o status LIDO, muda e grava. Se este
        // teste parar de falhar em "as duas ganharam", o defeito nao existe mais no banco e a trava
        // condicional virou enfeite -- entao vale apagar a trava, nao este teste.
        int rodadasComDuasGanhando = 0;
        for (int rodada = 0; rodada < 15; rodada++) {
            semear();
            CyclicBarrier largada = new CyclicBarrier(2);
            AtomicInteger acharamQueAvancaram = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(2);

            Callable<Void> comoEraAntes = () -> {
                var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
                tx.execute(st -> {
                    Pedido lido = pedidos.findById(pedidoId).orElseThrow();
                    StatusPedido statusLido = lido.status;
                    try { largada.await(5, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
                    if (statusLido == StatusPedido.RECEBIDO) {   // a validacao contra o valor LIDO
                        lido.status = StatusPedido.EM_PREPARO;
                        lido.atualizadoEm = OffsetDateTime.now();
                        pedidos.saveAndFlush(lido);
                        acharamQueAvancaram.incrementAndGet();
                    }
                    return null;
                });
                return null;
            };

            Future<Void> a = pool.submit(comoEraAntes);
            Future<Void> b = pool.submit(comoEraAntes);
            try { a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS); } catch (Exception ignorado) { }
            pool.shutdown();
            if (acharamQueAvancaram.get() == 2) rodadasComDuasGanhando++;
        }

        assertTrue(rodadasComDuasGanhando > 0,
                "em 15 rodadas o jeito antigo nunca deixou as duas passarem -- se for sempre assim, "
                        + "a trava condicional nao esta protegendo de nada e este teste e que esta errado");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void quemChegaComOstatusVELHOnaoGrava() {
        // A segunda tela leu RECEBIDO, a primeira ja avancou para EM_PREPARO. A segunda nao pode
        // escrever nada -- e o caso sequencial do mesmo defeito.
        assertEquals(1, tentarAvancar(LOJA, StatusPedido.RECEBIDO, StatusPedido.EM_PREPARO));
        assertEquals(0, tentarAvancar(LOJA, StatusPedido.RECEBIDO, StatusPedido.PRONTO),
                "gravou por cima usando um status que ja nao era o atual");
        assertEquals(StatusPedido.EM_PREPARO, pedidos.findById(pedidoId).orElseThrow().status);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aTrancaDaLojaContinuaValendo() {
        assertEquals(0, tentarAvancar(9999L, StatusPedido.RECEBIDO, StatusPedido.EM_PREPARO),
                "outra loja conseguiu mexer no pedido");
        assertEquals(StatusPedido.RECEBIDO, pedidos.findById(pedidoId).orElseThrow().status);
    }
}
