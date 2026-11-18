package br.com.bora.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ler a chave do outbox NÃO pode derrubar a venda — nem quando a leitura ESTOURA.
 *
 * <h2>O defeito, e por que o teste unitário não o via</h2>
 * <p>{@link PublicadorDeEventos#ligado()} tem um {@code catch (RuntimeException)} e um comentário
 * meu dizendo "Não achar a chave não pode derrubar venda. Na dúvida, desligado". <b>Isso era
 * falso</b>, e o teste unitário {@code chaveQueEstouraAoSerLidaDeixaDesligado} nunca poderia ver,
 * porque ele usa um <i>mock</i> de repositório: num mock, a exceção nasce fora de qualquer proxy
 * transacional e o {@code catch} realmente resolve.</p>
 *
 * <p>Com o repositório DE VERDADE é outra coisa. {@code configs.findById} passa pelo proxy
 * transacional do Spring Data. Quando a exceção sai de dentro dele, o Spring marca a transação
 * corrente como <b>rollback-only</b> antes de o meu {@code catch} existir. Engolir a exceção depois
 * não desfaz a marca: a venda roda até o fim e o <b>commit</b> estoura com
 * {@code UnexpectedRollbackException}.</p>
 *
 * <h2>Quando isso acontece de verdade</h2>
 * <p>O valor de {@code config_plataforma.valor} é cifrado em repouso. O conversor
 * ({@code SegredoGuardado.convertToEntityAttribute}) <b>lança</b> {@code IllegalStateException} em
 * dois casos reais: a variável {@code BORA_SEGREDO_COLUNAS} ausente, e a chave TROCADA (o GCM
 * autentica o conteúdo, então um valor cifrado com outra chave não abre).</p>
 *
 * <p>Ou seja: perder ou trocar a chave de cifragem derrubaria <b>toda venda</b> enquanto o outbox
 * estivesse ligado — e ele está ligado em produção desde 18/11/2026 às 19:08. Uma vez a cada 30 s,
 * que é a validade do cache da chave.</p>
 *
 * <p><b>Como rodar:</b> precisa do Postgres de teste.</p>
 * <pre>
 * bash testes-de-integracao.sh --so-uma AchaveDoOutboxNAOpodeDERRUBARaVENDAIT
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
class AchaveDoOutboxNAOpodeDERRUBARaVENDAIT {

    /** Um valor que o conversor NÃO consegue abrir: prefixo certo, conteúdo que não é cifra válida. */
    private static final String ILEGIVEL = "enc:v1:LIXOqueNAOabreNUNCA==";

    @Autowired private PublicadorDeEventos publicador;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager gerente;

    @AfterEach
    void limpar() {
        jdbc.update("delete from config_plataforma where chave = ?", PublicadorDeEventos.CHAVE_LIGA);
        publicador.forcarReleituraDaChave();
    }

    /**
     * Grava o valor ilegível por SQL cru, de propósito: pelo repositório o conversor cifraria o
     * texto na ida e ele abriria na volta. O que se quer reproduzir é a linha que já está no banco
     * cifrada com uma chave que não se tem mais.
     */
    private void gravarChaveIlegivel() {
        jdbc.update("""
                insert into config_plataforma (chave, valor, atualizado_em)
                values (?, ?, now())
                on conflict (chave) do update set valor = excluded.valor
                """, PublicadorDeEventos.CHAVE_LIGA, ILEGIVEL);
        publicador.forcarReleituraDaChave();
    }

    // ----------------------------------------------------------------- a premissa

    /**
     * A PREMISSA: o valor que eu gravei realmente estoura na leitura.
     *
     * <p>Sem isto, um teste que passasse porque a leitura NÃO estourou pareceria prova de que a
     * venda está protegida. Aqui eu exijo que o caminho de falha exista de verdade.</p>
     */
    @Test
    void aPREMISSAoVALORilegivelREALMENTEestouraNAleitura() {
        gravarChaveIlegivel();
        String cru = jdbc.queryForObject(
                "select valor from config_plataforma where chave = ?", String.class,
                PublicadorDeEventos.CHAVE_LIGA);
        assertEquals(ILEGIVEL, cru, "o valor tem de estar cru no banco, sem passar pelo conversor");
    }

    // ----------------------------------------------------------------- o que importa

    /**
     * O TESTE QUE PEGA O DEFEITO: dentro de uma transação, ler a chave ilegível não pode marcar a
     * transação como rollback-only, e o commit tem de passar.
     *
     * <p>É isto que a venda faz: ela está numa transação quando o publicador consulta a chave.</p>
     */
    @Test
    void lerAchaveILEGIVELdentroDEumaTRANSACAOnaoDERRUBAocommit() {
        gravarChaveIlegivel();
        TransactionTemplate tx = new TransactionTemplate(gerente);

        boolean[] respondeu = new boolean[1];
        boolean[] marcadaParaDesfazer = new boolean[1];

        // Esta é a parte que reproduz a venda: a chamada acontece DENTRO da transação.
        assertDoesNotThrow(() -> tx.execute(status -> {
            respondeu[0] = publicador.ligado();
            marcadaParaDesfazer[0] = status.isRollbackOnly();
            return null;
        }), "o COMMIT estourou: a leitura da chave derrubou a transação da venda");

        assertFalse(respondeu[0], "chave ilegível tem de responder DESLIGADO — na dúvida, não grava");
        assertFalse(marcadaParaDesfazer[0],
                "a transação foi marcada como rollback-only ao ler a chave. O `catch` do ligado() "
                        + "engole a exceção, mas o proxy do Spring Data já marcou a transação antes "
                        + "dele. A venda roda inteira e morre no commit.");
    }

    /**
     * E o caminho inteiro: com a chave ilegível, gravar um fato e pedir o evento na mesma transação
     * tem de COMMITAR, com o fato gravado e nenhum evento.
     *
     * <p>Este é o teste que mede a promessa pelo resultado, e não pela bandeira interna do Spring:
     * depois do commit, a linha do fato existe.</p>
     */
    @Test
    void comChaveILEGIVELoFATOgravaEaVENDAsobrevive() {
        gravarChaveIlegivel();
        TransactionTemplate tx = new TransactionTemplate(gerente);
        String marca = "zz-teste-chave-ilegivel-" + System.nanoTime();

        assertDoesNotThrow(() -> tx.execute(status -> {
            // o "fato" da venda, numa tabela qualquer que não exige o mundo todo montado
            jdbc.update("insert into config_plataforma (chave, valor, atualizado_em) "
                    + "values (?, 'ok', now())", marca);
            publicador.dePedido("PedidoRecebido", umPedido(), java.util.Map.of());
            return null;
        }), "a venda inteira morreu no commit por causa da leitura da chave");

        Integer achou = jdbc.queryForObject(
                "select count(*) from config_plataforma where chave = ?", Integer.class, marca);
        assertEquals(1, achou, "o fato tinha de estar gravado depois do commit");

        jdbc.update("delete from config_plataforma where chave = ?", marca);
    }

    private br.com.bora.entity.Pedido umPedido() {
        br.com.bora.entity.Pedido p = new br.com.bora.entity.Pedido();
        p.id = 1L;
        p.lojaId = 1L;
        p.codigo = "#teste";
        p.status = br.com.bora.entity.StatusPedido.RECEBIDO;
        p.valorTotal = new java.math.BigDecimal("10.00");
        p.criadoEm = java.time.OffsetDateTime.now();
        return p;
    }
}
