package br.com.bora.controller;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A chamada ao Asaas nao segura conexao do banco.
 *
 * <h2>O defeito (achado pelo QA em 26/10/2026)</h2>
 * <p>O {@code pedirOnline} foi escrito em tres passos, e o comentario do passo 2 afirma: <i>"o Asaas
 * FORA da transacao. A conexao do banco ja voltou para o pool."</i></p>
 *
 * <p>O metodo tinha {@code @Transactional}. Com propagacao padrao (REQUIRED), os tres
 * {@code TransactionTemplate} <b>entravam</b> na transacao do metodo em vez de serem curtos: a
 * chamada HTTP ao Asaas rodava com uma conexao do banco na mao, exatamente o que a estrutura de tres
 * passos existia para evitar. <b>A anotacao desmentia o comentario.</b></p>
 *
 * <h2>Por que derruba a loja toda</h2>
 * <p>O pool de conexoes e compartilhado. Asaas lento com varios clientes no cardapio ao mesmo tempo
 * prende uma conexao por checkout; quando o pool acaba, param tambem o painel, o KDS e os
 * marketplaces — por causa de um servico de fora. Um pedido falhar e aceitavel; a API inteira parar
 * nao e.</p>
 *
 * <h2>O que este teste mede, e o que ele NAO mede</h2>
 * <p>Ele mede a <b>forma</b>: a anotacao ausente no metodo, a transacao do laco de itens presente no
 * passo 1, e a chamada ao Asaas fora dela. Nao mede o pool sob carga — para isso seria preciso um
 * Asaas lento de verdade e um pool pequeno, e esta dito abaixo o que ficou sem prova.</p>
 *
 * <p>Vale assim porque o defeito era de forma: ninguem escreveu "segura a conexao". A anotacao estava
 * la por um motivo que o passo 1 passou a cobrir, e quem a leu depois nao tinha como notar que ela
 * cancelava o desenho dos tres passos.</p>
 */
class OasaasNaoSEGURAconexaoDoBancoTest {

    private static final Path FONTE =
            Path.of("src/main/java/br/com/bora/controller/PublicController.java");

    private static String fonte() {
        return assertDoesNotThrow(() -> Files.readString(FONTE, StandardCharsets.UTF_8));
    }

    /** O codigo sem comentario: o defeito era justamente um comentario que afirmava o contrario. */
    private static String semComentarios(String texto) {
        List<String> fora = new ArrayList<>();
        boolean bloco = false;
        for (String linha : texto.split("\n", -1)) {
            String t = linha.trim();
            if (bloco) {
                if (t.contains("*/")) bloco = false;
                continue;
            }
            if (t.startsWith("/*")) {
                if (!t.contains("*/")) bloco = true;
                continue;
            }
            if (t.startsWith("//") || t.startsWith("*")) continue;
            fora.add(linha);
        }
        return String.join("\n", fora);
    }

    /** O corpo do metodo, do cabecalho dele ate o cabecalho do proximo. */
    private static String corpoDoPedirOnline(String codigo) {
        int i = codigo.indexOf("public Map<String, Object> pedirOnline(");
        assertTrue(i > 0, "nao achei o pedirOnline: este teste mede nada");
        int fim = codigo.indexOf("\n    private record Rascunho", i);
        assertTrue(fim > i, "nao achei o fim do pedirOnline");
        return codigo.substring(i, fim);
    }

    // ---------------------------------------------------------------- o conserto
    @Test
    void oMETODOdoPEDIDOnaoEHtransacionalINTEIRO() {
        // Reflexao, e nao texto: pega tambem a anotacao herdada ou posta em outro lugar.
        Method m = assertDoesNotThrow(() -> PublicController.class.getMethod("pedirOnline",
                Long.class, java.util.Map.class, jakarta.servlet.http.HttpServletRequest.class));

        assertNull(m.getAnnotation(org.springframework.transaction.annotation.Transactional.class),
                "voltou o @Transactional no metodo: os tres passos entram numa transacao so e a "
                        + "chamada HTTP ao Asaas volta a segurar conexao do banco");
    }

    @Test
    void aTRANSACAOdoLACOdeITENScontinuaEXISTINDO() {
        // O que a anotacao protegia nao pode ter sido perdido: recusar no terceiro item nao pode
        // deixar o pedido e os dois primeiros itens gravados, com o estoque deles baixado.
        String corpo = corpoDoPedirOnline(semComentarios(fonte()));
        assertTrue(corpo.contains("tx.execute(st ->"),
                "o passo 1 perdeu a transacao: a recusa no meio do laco volta a deixar rastro");
    }

    @Test
    void oASAASehCHAMADOdepoisQUEoPASSO1fecha() {
        String corpo = corpoDoPedirOnline(semComentarios(fonte()));
        int passo1 = corpo.indexOf("tx.execute(st ->");
        int fimDoPasso1 = corpo.indexOf("rascunho.resposta()", passo1);
        int asaas = corpo.indexOf("pix.criarCobranca(");

        assertTrue(asaas > 0, "nao achei a chamada ao Asaas");
        assertTrue(asaas > fimDoPasso1,
                "a chamada ao Asaas entrou para dentro do passo 1: ela passa a rodar com a conexao "
                        + "do banco na mao");
    }

    @Test
    void oPASSO1naoCHAMAoASAASnemNADAexterno() {
        // A trava na outra direcao: mover a chamada para dentro do bloco do passo 1 tem de reprovar.
        String corpo = corpoDoPedirOnline(semComentarios(fonte()));
        int passo1 = corpo.indexOf("tx.execute(st ->");
        String dentro = corpo.substring(passo1, corpo.indexOf("rascunho.resposta()", passo1));

        for (String externo : new String[] { "pix.criarCobranca(", "RestTemplate", "HttpClient",
                "WebClient", "HttpRequest" }) {
            assertFalse(dentro.contains(externo),
                    "chamada externa (" + externo + ") dentro da transacao do passo 1");
        }
    }

    @Test
    void aGRAVACAOdaCOBRANCAehOUTRAtransacaoCURTA() {
        String corpo = corpoDoPedirOnline(semComentarios(fonte()));
        int asaas = corpo.indexOf("pix.criarCobranca(");
        String depois = corpo.substring(asaas);

        assertTrue(depois.contains("tx.executeWithoutResult(st ->"),
                "o passo 3 perdeu a transacao propria: a gravacao do paymentId fica sem transacao");
    }

    @Test
    void aCOMPENSACAOexisteEHfalhaDOasaas() {
        // Com o passo 1 commitado de verdade, o pedido existe e ja consumiu cashback. Sem a
        // compensacao, o cliente perde o saldo num pedido que nao tem como pagar.
        //
        // Antes do conserto esse codigo nao compensava nada: gravava o cancelamento e o
        // ResponseStatusException logo depois desfazia essa mesma gravacao no rollback do metodo.
        String corpo = corpoDoPedirOnline(semComentarios(fonte()));
        int asaas = corpo.indexOf("pix.criarCobranca(");
        String tratamento = corpo.substring(asaas);

        assertTrue(tratamento.contains("catch"), "a falha do Asaas deixou de ser tratada");
        assertTrue(tratamento.contains("desfazerPedidoSemPix("),
                "sem a compensacao o cliente perde o cashback num pedido que nao tem como pagar");
    }

    // ---------------------------------------------------------------- o webhook segue certo
    @Test
    void oWEBHOOKdoPIXcontinuaTRANSACIONAL_eNAOchamaNADAdeFORA() {
        // Ele pode ser transacional porque nao faz chamada externa: conferido em 27/10/2026.
        Method m = assertDoesNotThrow(() -> PublicController.class.getMethod("pixWebhook",
                Long.class, String.class, java.util.Map.class));
        assertNotNull(m.getAnnotation(org.springframework.transaction.annotation.Transactional.class),
                "o webhook do PIX perdeu a transacao: ele reabre pedido e devolve estoque");

        String codigo = semComentarios(fonte());
        int i = codigo.indexOf("public Map<String, String> pixWebhook(");
        assertTrue(i > 0, "nao achei o pixWebhook");
        String corpo = codigo.substring(i, Math.min(codigo.length(), i + 6000));
        for (String externo : new String[] { "pix.criarCobranca(", "RestTemplate", "HttpClient",
                "WebClient" }) {
            assertFalse(corpo.contains(externo),
                    "entrou chamada externa (" + externo + ") na transacao do webhook");
        }
    }

    /**
     * O que este arquivo NAO prova: que o pool nao esgota sob carga.
     *
     * <p>Isso precisa de um Asaas lento de verdade e um pool pequeno, e esta registrado como o elo
     * que falta em vez de ficar sem registro. O que da para garantir aqui e a forma -- que foi onde o
     * defeito morava.</p>
     */
    @Test
    void oELOqueFALTAestaREGISTRADO() {
        assertTrue(fonte().contains("conexao do banco na mao"),
                "o motivo do conserto saiu do codigo: quem ler depois volta a por a anotacao");
    }
}
