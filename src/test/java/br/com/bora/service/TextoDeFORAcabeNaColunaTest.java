package br.com.bora.service;

import br.com.bora.entity.LarguraDeColuna;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Texto que vem de fora cabe na coluna — e quem grava passa pelo cortador.
 *
 * <h2>Itens 4.4 e 4.5 da varredura, e o que medir mudou neles</h2>
 * <p>As duas colunas têm a mesma armadilha do {@code pedido.endereco_entrega} (item 4.2): o
 * {@code ddl-auto: validate} confere nome e tipo de coluna, <b>não confere largura</b>, então texto
 * grande passa por toda a subida e por todo teste com mock, e só falha no Postgres. E não falha
 * truncando: o Postgres <b>recusa</b> a gravação.</p>
 *
 * <p><b>4.4 — {@code pagamento_assinatura.descricao}, varchar(200).</b> A varredura disse que a
 * observação "quebra o registro". Medindo, a consequência é pior: o método é {@code @Transactional},
 * então não é o texto que se perde, é <b>o registro inteiro do pagamento</b>. O dono digita uma
 * observação um pouco mais longa, vê um erro, e o dinheiro que ele acabou de receber não entra no
 * faturamento. É a função que ele usa para dar baixa no que recebeu fora do Asaas.</p>
 *
 * <p><b>4.5 — {@code integracao_canal.ultimo_erro}, varchar(500).</b> A varredura disse que duas
 * linhas gravam sem cortar. Medindo: dos cinco lugares que gravam a coluna, <b>um só</b> estava de
 * fato sem limite — o {@code IfoodClient} na renovação do token, com o {@code e.getMessage()} cru. Os
 * outros cabiam por um corte em 400 ou por serem texto fixo. Mas a parte grave da varredura está
 * certa e vale para todos: a gravação acontece <b>dentro do tratamento de erro</b>, então se ela
 * estoura a falha nunca é registrada e a tela de Integrações mostra a loja como se nada tivesse
 * acontecido.</p>
 *
 * <p>Esta classe mede o cortador e <b>cobra que todo gravador passe por ele</b>. Ler o fonte não
 * prova comportamento; quem prova a largura real é o {@code LarguraDasColunasBateComOBancoIT}, contra
 * Postgres. Mas este roda no caminho que o deploy percorre, e é o que faltou nas três vezes em que uma
 * mutação de SQL passou verde.</p>
 */
class TextoDeFORAcabeNaColunaTest {

    // =============================================================== o cortador

    @Test
    void textoQUEcabeVOLTAinteiro() {
        assertEquals("PIX na conta", LarguraDeColuna.encurtar("PIX na conta", 200, "teste"));
    }

    @Test
    void textoQUEnaoCABEehCORTADOnoTAMANHOexato() {
        String longo = "x".repeat(250);

        String cortado = LarguraDeColuna.encurtar(longo, 200, "teste");

        assertEquals(200, cortado.length());
    }

    @Test
    void oESPACOdasPONTAsaiANTESdeMEDIR() {
        // "   abc   " com limite 3 tem que caber, nao ser cortado em "   ".
        assertEquals("abc", LarguraDeColuna.encurtar("   abc   ", 3, "teste"));
    }

    @Test
    void nuloEvazioVIRAMnulo() {
        // Campo em branco nao e "texto vazio", e ausencia -- a mesma regra do endereco do marketplace.
        assertNull(LarguraDeColuna.encurtar(null, 200, "teste"));
        assertNull(LarguraDeColuna.encurtar("", 200, "teste"));
        assertNull(LarguraDeColuna.encurtar("    ", 200, "teste"));
    }

    @Test
    void limiteZEROouNEGATIVOnaoEstoura() {
        // Acontece quando o nome da loja ja ocupou a coluna inteira: a sobra para a observacao e zero
        // ou menos. Estourar aqui derrubaria o registro do pagamento, que e exatamente o que o
        // conserto existe para impedir.
        assertDoesNotThrow(() -> LarguraDeColuna.encurtar("qualquer coisa", 0, "teste"));
        assertEquals("", LarguraDeColuna.encurtar("qualquer coisa", 0, "teste"));
    }

    /** Pega as linhas que o LarguraDeColuna escreveu no log durante a acao. */
    private List<String> avisosDurante(Runnable acao) {
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(LarguraDeColuna.class);
        var coletor = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        coletor.start();
        logger.addAppender(coletor);
        try {
            acao.run();
        } finally {
            logger.detachAppender(coletor);
        }
        return coletor.list.stream().map(e -> e.getFormattedMessage()).toList();
    }

    @Test
    void oTEXTOqueCABEexatoNAOgeraAVISO() {
        // Achado por mutacao: trocar `<=` por `<` no limite devolve o MESMO texto (cortar em `max` um
        // texto de tamanho `max` nao muda nada), entao nenhuma asserção sobre o retorno pega a
        // mutacao. O que muda e o log: todo texto exatamente no limite passaria a gritar "foi
        // CORTADO" sem ter sido. Aviso que grita errado deixa de ser lido -- e este aviso e o unico
        // rastro de informacao perdida que existe.
        List<String> avisos = avisosDurante(() -> LarguraDeColuna.encurtar("x".repeat(200), 200, "teste"));

        assertTrue(avisos.isEmpty(), "texto que cabe exato nao foi cortado, e nao pode avisar que foi: "
                + avisos);
    }

    @Test
    void oTEXTOqueNAOcabeGERAumAVISOso() {
        List<String> avisos = avisosDurante(() -> LarguraDeColuna.encurtar("x".repeat(201), 200, "teste"));

        assertEquals(1, avisos.size(), "foi: " + avisos);
        assertTrue(avisos.get(0).contains("201"), "o aviso tem que dizer o tamanho que veio");
        assertTrue(avisos.get(0).contains("teste"), "e onde aconteceu");
    }

    @Test
    void oCORTEguardaOtextoINTEIROnoLOG() {
        // Cortar perde informacao. Se nao houver uma linha no log com o texto completo, a informacao
        // desaparece sem rastro -- e quem procura o motivo nao tem onde olhar.
        String fonte = fonte("src/main/java/br/com/bora/entity/LarguraDeColuna.java");
        int i = fonte.indexOf("public static String encurtar(");
        assertTrue(i > 0);
        String metodo = fonte.substring(i, fonte.indexOf("\n    }", i));

        assertTrue(metodo.contains("log.warn"), "o corte tem que avisar");
        assertTrue(metodo.contains("Texto completo"),
                "o aviso tem que trazer o texto inteiro, senao o que foi cortado se perde");
    }

    // =============================================================== todo gravador passa pelo cortador

    /** Os campos que recebem texto de fora e tem coluna estreita, com o fonte de quem grava. */
    private static final List<String> ARQUIVOS_DO_ULTIMO_ERRO = List.of(
            "src/main/java/br/com/bora/service/marketplace/IfoodClient.java",
            "src/main/java/br/com/bora/service/marketplace/OpenDeliveryClient.java",
            "src/main/java/br/com/bora/service/marketplace/MarketplacePoller.java");

    @Test
    void todaGRAVACAOdoULTIMOERROpassaPELOcortador() throws IOException {
        // Consertar caso a caso nao resolve: o proximo marketplace que alguem integrar cai no mesmo
        // buraco, e o estouro acontece dentro do catch -- onde ninguem ve.
        List<String> cruas = new ArrayList<>();
        int achadas = 0;
        for (String arquivo : ARQUIVOS_DO_ULTIMO_ERRO) {
            String fonte = fonte(arquivo);
            // Da atribuicao ate o `;` QUE FECHA A LINHA, nao uma janela de tamanho fixo.
            //
            // Achado por mutacao: trocar a constante do limite por 999999 faz o cortador nao cortar
            // nada -- o defeito de volta, com o cortador ainda sendo chamado. A primeira versao deste
            // teste passava verde nisso, porque so perguntava se o `encurtar` aparecia. Com janela
            // fixa de 120 caracteres a constante ficava FORA dela em alguns sitios.
            //
            // E o `;` tem que ser o de fim de linha, nao qualquer um: a mensagem do
            // MarketplacePoller tem um ponto e virgula DENTRO do texto ("; sera reenviado"), e a
            // versao anterior deste regex parava nele e reprovava uma gravacao que estava correta.
            Matcher m = Pattern.compile("ultimoErro\\s*=\\s*([\\s\\S]{0,600}?);\\s*\\n", Pattern.DOTALL)
                    .matcher(fonte);
            while (m.find()) {
                achadas++;
                String atribuicao = m.group(1);
                boolean seguro = atribuicao.startsWith("null")
                        || (atribuicao.contains("LarguraDeColuna.encurtar")
                            && atribuicao.contains("INTEGRACAO_ULTIMO_ERRO"));
                if (!seguro) {
                    cruas.add(arquivo.substring(arquivo.lastIndexOf('/') + 1) + ": "
                            + atribuicao.replaceAll("\\s+", " ").substring(0, Math.min(70, atribuicao.length())));
                }
            }
        }

        assertTrue(achadas >= 10, "esperava achar pelo menos 10 gravacoes de ultimoErro, achei "
                + achadas + ". O caminho ou o regex quebrou e este teste esta medindo nada.");
        assertTrue(cruas.isEmpty(),
                "estas gravacoes de `ultimo_erro` nao passam pelo LarguraDeColuna.encurtar COM a "
                        + "constante INTEGRACAO_ULTIMO_ERRO -- cortador com limite errado e o defeito "
                        + "de volta. A coluna "
                        + "e varchar(" + LarguraDeColuna.INTEGRACAO_ULTIMO_ERRO + ") e a gravacao "
                        + "acontece DENTRO do tratamento de erro: se o Postgres recusar, a falha nunca "
                        + "e registrada e a tela de Integracoes mostra a loja como se estivesse bem. "
                        + cruas);
    }

    @Test
    void aDESCRICAOdoPAGAMENTOnaoEHmontadaSEMcortar() throws IOException {
        // A concatenacao crua era `p.descricao + " · " + observacao.trim()`. Se ela voltar, o registro
        // do pagamento volta a poder ser desfeito por uma observacao longa.
        String fonte = fonte("src/main/java/br/com/bora/service/AssinaturaService.java");

        assertFalse(fonte.contains("+ observacao.trim()"),
                "a observacao do pagamento manual nao pode ser concatenada direto na descricao: "
                        + "use o descricaoComObservacao, que respeita a largura da coluna");
        assertTrue(fonte.contains("descricaoComObservacao("),
                "o metodo que monta a descricao dentro da largura tem que existir e ser usado");
    }

    private String fonte(String caminho) {
        Path p = Path.of(caminho);
        assertTrue(Files.isRegularFile(p), "nao achei " + p.toAbsolutePath());
        return assertDoesNotThrow(() -> Files.readString(p, StandardCharsets.UTF_8));
    }
}
