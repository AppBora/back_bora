package br.com.bora.service;

import br.com.bora.entity.ConfigPlataforma;
import br.com.bora.repository.ConfigPlataformaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Ou os dois existem, ou nenhum" — provado contra o PostgreSQL, não por estrutura de código.
 *
 * <h2>Por que este arquivo existe</h2>
 * <p>A promessa da entrega A2 é atômica: o evento é gravado na MESMA transação do fato, então venda
 * sem evento e evento sem venda são impossíveis.</p>
 *
 * <p>{@code OeventoVIVEnaMESMAtransacaoDAvendaTest} garante a parte do <b>código</b> — que todo
 * ponto de emissão está dentro de uma transação. Ele é estrutural, e eu escrevi lá o que ele não
 * prova: que o banco <b>de fato</b> desfaz os dois juntos. <b>É isso que se prova aqui</b>, e por
 * isso é um IT: transação não existe em teste com dublê.</p>
 *
 * <p>Os dois são necessários. O estrutural pega o mutante ("alguém tirou o {@code @Transactional}");
 * este pega a suposição ("eu acho que o PostgreSQL desfaz isso").</p>
 *
 * <p><b>Como rodar:</b> precisa do Postgres de teste.</p>
 * <pre>
 * bash testes-de-integracao.sh --so-uma OeventoEoFATOcaemJUNTOSIT
 * </pre>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:55432/bora",
        "spring.datasource.username=postgres",
        "spring.datasource.password=teste",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class OeventoEoFATOcaemJUNTOSIT {

    /** Tudo que este arquivo cria carrega esta marca, para a limpeza nao encostar em nada real. */
    private static final String MARCA = "zz-teste-atomico";

    @Autowired private PublicadorDeEventos publicador;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ConfigPlataformaRepository configs;
    @Autowired private PlatformTransactionManager gerente;

    private String chaveComoEstava;
    private boolean chaveExistia;

    @BeforeEach
    void ligarAfila() {
        configs.findById(PublicadorDeEventos.CHAVE_LIGA).ifPresentOrElse(c -> {
            chaveExistia = true;
            chaveComoEstava = c.getValor();
        }, () -> chaveExistia = false);

        // Pela MESMA porta que o painel usa (o repositorio, com o conversor de cifragem no meio).
        ConfigPlataforma c = configs.findById(PublicadorDeEventos.CHAVE_LIGA)
                .orElseGet(() -> {
                    ConfigPlataforma nova = new ConfigPlataforma();
                    nova.setChave(PublicadorDeEventos.CHAVE_LIGA);
                    return nova;
                });
        c.setValor("true");
        c.setAtualizadoEm(OffsetDateTime.now());
        configs.save(c);
        publicador.forcarReleituraDaChave();
        limparMarca();
    }

    @AfterEach
    void limpar() {
        limparMarca();
        if (chaveExistia) {
            ConfigPlataforma c = configs.findById(PublicadorDeEventos.CHAVE_LIGA).orElseThrow();
            c.setValor(chaveComoEstava);
            configs.save(c);
        } else {
            configs.deleteById(PublicadorDeEventos.CHAVE_LIGA);
        }
        publicador.forcarReleituraDaChave();
    }

    private void limparMarca() {
        jdbc.update("delete from evento_outbox where tipo like ?", MARCA + "%");
        jdbc.update("delete from config_plataforma where chave like ?", MARCA + "%");
    }

    /** O "fato" da venda: uma linha numa tabela que nao exige o mundo todo montado. */
    private void gravarOfato(String marca) {
        jdbc.update("insert into config_plataforma (chave, valor, atualizado_em) "
                + "values (?, 'ok', now())", marca);
    }

    private int fatosComAmarca(String marca) {
        return jdbc.queryForObject(
                "select count(*) from config_plataforma where chave = ?", Integer.class, marca);
    }

    private int eventosDoTeste() {
        return jdbc.queryForObject(
                "select count(*) from evento_outbox where tipo like ?", Integer.class, MARCA + "%");
    }

    private br.com.bora.entity.Pedido umPedido() {
        br.com.bora.entity.Pedido p = new br.com.bora.entity.Pedido();
        p.id = 1L;
        p.lojaId = 1L;
        p.codigo = "#atomico";
        p.status = br.com.bora.entity.StatusPedido.RECEBIDO;
        p.valorTotal = new BigDecimal("10.00");
        p.criadoEm = OffsetDateTime.now();
        return p;
    }

    // ----------------------------------------------------------------- a premissa

    /**
     * A PREMISSA: com a fila ligada, o evento REALMENTE e gravado quando a transacao commita.
     *
     * <p>Sem esta medicao, o teste de rollback abaixo passaria com a fila desligada — "nenhum
     * evento" por nao ter nascido nenhum, e nao por ter sido desfeito. E seria uma prova falsa da
     * atomicidade.</p>
     */
    @Test
    void aPREMISSAoCOMMITdeixaOSdoisGRAVADOS() {
        String marca = MARCA + "-commit-" + System.nanoTime();
        TransactionTemplate tx = new TransactionTemplate(gerente);

        tx.execute(st -> {
            gravarOfato(marca);
            publicador.dePedido(MARCA + "-PedidoRecebido", umPedido(), java.util.Map.of());
            return null;
        });

        assertEquals(1, fatosComAmarca(marca), "o fato tinha de estar gravado depois do commit");
        assertEquals(1, eventosDoTeste(), "o evento tinha de estar gravado depois do commit");
    }

    // ----------------------------------------------------------------- o que importa

    /**
     * O ROLLBACK LEVA OS DOIS. E a prova de "ou os dois existem, ou nenhum".
     *
     * <p>Se o evento sobrevivesse ao desfazimento da venda, quem consome a fila montaria um pedido
     * que nunca existiu — e a correcao disso, depois, exige saber quais eventos eram orfaos.</p>
     */
    @Test
    void oROLLBACKdesfazOfatoEOeventoJUNTOS() {
        String marca = MARCA + "-rollback-" + System.nanoTime();
        TransactionTemplate tx = new TransactionTemplate(gerente);

        tx.execute(st -> {
            gravarOfato(marca);
            publicador.dePedido(MARCA + "-PedidoRecebido", umPedido(), java.util.Map.of());
            st.setRollbackOnly();   // a venda falhou depois de ja ter gravado os dois
            return null;
        });

        assertEquals(0, fatosComAmarca(marca),
                "o fato sobreviveu ao rollback — a premissa deste teste esta errada, revise antes "
                        + "de confiar no resultado");
        assertEquals(0, eventosDoTeste(),
                "O EVENTO SOBREVIVEU AO ROLLBACK DA VENDA. A promessa da entrega A2 (\"ou os dois "
                        + "existem, ou nenhum\") esta quebrada: quem consome a fila vai montar um "
                        + "pedido que nunca existiu.");
    }

    /**
     * E o caminho em que o FATO estoura depois do evento: nada fica.
     *
     * <p>E a ordem real de uma venda que falha no meio — o evento ja foi gravado e a instrucao
     * seguinte recusa. No PostgreSQL a instrucao que falha envenena a transacao inteira, e e
     * exatamente por isso que o evento tambem nao fica.</p>
     */
    @Test
    void seOfatoESTOURAdepoisDOevento_nadaFICA() {
        String marca = MARCA + "-estouro-" + System.nanoTime();
        TransactionTemplate tx = new TransactionTemplate(gerente);

        assertThrows(RuntimeException.class, () -> tx.execute(st -> {
            gravarOfato(marca);
            publicador.dePedido(MARCA + "-PedidoRecebido", umPedido(), java.util.Map.of());
            // a mesma chave de novo: a chave primaria recusa, e a transacao morre
            gravarOfato(marca);
            return null;
        }));

        assertEquals(0, fatosComAmarca(marca), "o fato ficou, e a transacao tinha falhado");
        assertEquals(0, eventosDoTeste(),
                "o evento ficou depois de a venda falhar — e evento de venda que nao aconteceu");
    }
}
