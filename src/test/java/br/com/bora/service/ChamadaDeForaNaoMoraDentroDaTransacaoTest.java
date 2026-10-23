package br.com.bora.service;

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
 * Método que fala com terceiro não pode estar dentro de uma transação.
 *
 * <h2>Os itens 9.3, 9.4 e 9.5 são o mesmo defeito, em três lugares</h2>
 * <p>{@code @Transactional} em volta de um método que faz chamada HTTP mantém a transação — e a
 * conexão do pool, que tem <b>20</b> — presa pelo tempo da resposta de outra empresa. O PostgreSQL
 * também fica impedido de limpar versões antigas das linhas tocadas enquanto ela viver, e no caso do
 * 9.4 a linha da loja ficava <b>travada</b> esperando o Asaas.</p>
 *
 * <ul>
 *   <li><b>9.3</b> {@code recuperarClientes}: WhatsApp, uma chamada por cliente. Com a Meta fora do
 *       ar e 2.000 clientes, a varredura estimou ~7 horas de transação aberta.</li>
 *   <li><b>9.4</b> {@code reconciliarSubcontas}: Asaas, uma chamada por loja, com gravação no meio.</li>
 *   <li><b>9.5</b> {@code importarCardapio}: Anthropic, tempo limite de 64 s.</li>
 * </ul>
 *
 * <p>E não é novidade no projeto: o cobrador de PIX já tinha tido esse mesmo conserto.</p>
 *
 * <h2>Por que este teste lê o fonte</h2>
 * <p>"Não há transação em volta" é uma propriedade da <b>anotação</b>, não do comportamento — não dá
 * para observá-la chamando o método. Teste de comportamento não distingue uma chamada dentro de uma
 * transação de uma fora; só o Postgres distingue, e só sob carga. Então a verificação honesta aqui é
 * estrutural, e ela trava o que importa: <b>se alguém puser {@code @Transactional} de volta nesses
 * três métodos, este teste falha e conta por quê.</b></p>
 *
 * <p>O que ele <b>não</b> prova: que não exista outro método com o mesmo problema. Para isso ele
 * também varre o projeto procurando o padrão, e essa parte é a que pega caso novo.</p>
 */
class ChamadaDeForaNaoMoraDentroDaTransacaoTest {

    private static final Path FONTES = Path.of("src/main/java/br/com/bora");

    /** O nome dos clientes HTTP do projeto: quem aparece num metodo, fala com fora. */
    private static final Pattern FALA_COM_FORA = Pattern.compile(
            "TempoLimite\\.cliente|RestClient|whats\\.enviar|atualizarStatus\\(|http\\.client\\(");

    private String fonte(String caminho) throws IOException {
        Path p = FONTES.resolve(caminho);
        assertTrue(Files.isRegularFile(p), "nao achei " + p.toAbsolutePath());
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** O trecho de um metodo, do javadoc dele ate a assinatura. Basta para ver a anotacao. */
    private String cabecalhoDoMetodo(String fonte, String assinatura) {
        int fim = fonte.indexOf(assinatura);
        assertTrue(fim > 0, "nao achei a assinatura: " + assinatura);
        int inicio = Math.max(0, fim - 400);
        return fonte.substring(inicio, fim);
    }

    // =============================================================== os tres itens

    @Test
    void item93_recuperarClientesNAOeTransacional() throws IOException {
        String cabecalho = cabecalhoDoMetodo(fonte("service/IaService.java"),
                "public Map<String, Object> recuperarClientes(Long lojaId)");

        assertFalse(cabecalho.contains("@Transactional"),
                "item 9.3: este metodo manda WhatsApp num laco, uma chamada por cliente. Com "
                        + "@Transactional, a Meta fora do ar deixava a transacao aberta por horas, "
                        + "segurando uma conexao do pool de 20");
    }

    @Test
    void item94_reconciliarSubcontasNAOeTransacional() throws IOException {
        String cabecalho = cabecalhoDoMetodo(fonte("service/AsaasSubcontaService.java"),
                "public void reconciliarSubcontas()");

        assertFalse(cabecalho.contains("@Transactional"),
                "item 9.4: este metodo fala com o Asaas uma vez por loja e grava no meio. Com "
                        + "@Transactional, a linha da loja ficava travada esperando terceiro");
    }

    @Test
    void item95_importarCardapioNAOeTransacional() throws IOException {
        String cabecalho = cabecalhoDoMetodo(fonte("service/IaService.java"),
                "public Map<String, Object> importarCardapio(Long lojaId");

        assertFalse(cabecalho.contains("@Transactional"),
                "item 9.5: este metodo chama a Anthropic com tempo limite de 64 s. Com "
                        + "@Transactional, era uma conexao do pool presa por isso a cada importacao");
    }

    @Test
    void item95_masAgRAVACAOdosPRODUTOScontinuaATOMICA() throws IOException {
        // Tirar a transacao nao pode custar a atomicidade: ou o cardapio entra inteiro, ou nao entra.
        // A transacao agora comeca DEPOIS da IA responder.
        String fonte = fonte("service/IaService.java");
        int inicio = fonte.indexOf("public Map<String, Object> importarCardapio(Long lojaId");
        int fim = fonte.indexOf("// ---------- (3)", inicio);
        assertTrue(fim > inicio, "nao achei o fim do metodo");
        String corpo = fonte.substring(inicio, fim);

        assertTrue(corpo.contains("tx.execute("),
                "a gravacao dos produtos tem que estar numa transacao curta, so ela");
        assertTrue(corpo.indexOf("api.anthropic.com") < corpo.indexOf("tx.execute("),
                "a chamada a IA tem que vir ANTES da transacao comecar; se estiver dentro, o conserto "
                        + "nao serviu de nada");
    }

    // =============================================================== o teto do laco (9.3)

    @Test
    void item93_oTETOdoLACOcontaTENTATIVAeNAOsucesso() throws IOException {
        // A metade do 9.3 que nao e sobre transacao: o `break` olhava `enviados`, que so subia no
        // ramo de SUCESSO. Com o WhatsApp fora do ar o contador ficava em zero, o break nunca
        // disparava, e o laco percorria a base inteira -- um timeout por cliente.
        String fonte = fonte("service/IaService.java");
        int inicio = fonte.indexOf("public Map<String, Object> recuperarClientes(Long lojaId)");
        int fim = fonte.indexOf("/** ROI dos", inicio);
        assertTrue(fim > inicio);
        String corpo = fonte.substring(inicio, fim);

        Matcher quebra = Pattern.compile("if \\((\\w+) >= saldo\\) break").matcher(corpo);
        assertTrue(quebra.find(), "nao achei o teto do laco");
        assertEquals("tentativas", quebra.group(1),
                "o teto tem que contar TENTATIVA. Com `enviados`, falha nao gasta cota e o laco nao "
                        + "termina -- que e o defeito do item 9.3");
        assertTrue(corpo.contains("tentativas++"), "e a tentativa tem que ser contada antes do envio");
    }

    // =============================================================== e o caso novo

    @Test
    void nenhumMETODOtransacionalFALAcomTERCEIRO() throws IOException {
        // Esta e a parte que pega o PROXIMO. Varre o projeto inteiro procurando o padrao, em vez de
        // so travar os tres que eu conserto hoje.
        List<String> suspeitos = new ArrayList<>();
        try (var caminhos = Files.walk(FONTES)) {
            for (Path f : caminhos.filter(p -> p.toString().endsWith(".java")).toList()) {
                String s = Files.readString(f, StandardCharsets.UTF_8);
                for (String metodo : metodosTransacionais(s)) {
                    if (FALA_COM_FORA.matcher(metodo).find()) {
                        suspeitos.add(f.getFileName() + ": " + primeiraLinha(metodo));
                    }
                }
            }
        }

        assertTrue(suspeitos.isEmpty(),
                "estes metodos sao @Transactional E falam com terceiro. A transacao -- e a conexao do "
                        + "pool, que tem 20 -- fica presa pelo tempo de resposta de outra empresa. "
                        + "Tire a chamada externa de dentro, como nos itens 9.3, 9.4 e 9.5: "
                        + suspeitos);
    }

    /**
     * Cada método anotado com {@code @Transactional}, do início até a chave que fecha o corpo DELE.
     *
     * <p>A primeira versão pegava 4000 caracteres a partir da anotação, e isso <b>vazava</b> para os
     * métodos seguintes: os três que acabei de consertar apareciam como suspeitos porque a janela de
     * um método anterior passava por cima deles. Recorte errado acusa inocente — e esconde culpado.</p>
     */
    private List<String> metodosTransacionais(String fonte) {
        List<String> out = new ArrayList<>();
        // `(?m)^\s*` de propósito: sem isso o regex casava o `@Transactional` que aparece dentro dos
        // MEUS javadocs explicando o conserto, e o teste acusava justamente os métodos que eu acabei
        // de consertar. Anotação de verdade começa uma linha.
        Matcher m = Pattern.compile("(?m)^\\s*@(?:\\w+\\.)*Transactional\\b").matcher(fonte);
        while (m.find()) {
            int abre = fonte.indexOf('{', m.end());
            if (abre < 0) continue;
            out.add(fonte.substring(m.end(), fechaDoBloco(fonte, abre)));
        }
        return out;
    }

    /** O índice logo após a chave que fecha o bloco aberto em {@code abre}. */
    private int fechaDoBloco(String fonte, int abre) {
        int nivel = 0;
        for (int i = abre; i < fonte.length(); i++) {
            char c = fonte.charAt(i);
            if (c == '{') nivel++;
            else if (c == '}' && --nivel == 0) return i + 1;
        }
        return fonte.length();
    }

    private String primeiraLinha(String metodo) {
        for (String linha : metodo.split("\n")) {
            String t = linha.trim();
            if (t.startsWith("public") || t.startsWith("protected")) return t;
        }
        return metodo.lines().findFirst().orElse("?").trim();
    }

    @Test
    void oTESTEestaREALMENTEvarrendoOprojeto() throws IOException {
        // Sem isto, um erro no caminho ou no regex faria a varredura vir vazia e o teste acima passar
        // medindo nada -- a pior forma de falhar.
        int transacionais = 0;
        try (var caminhos = Files.walk(FONTES)) {
            for (Path f : caminhos.filter(p -> p.toString().endsWith(".java")).toList()) {
                transacionais += metodosTransacionais(Files.readString(f, StandardCharsets.UTF_8)).size();
            }
        }

        assertTrue(transacionais >= 8,
                "esperava achar pelo menos 8 metodos transacionais no projeto, achei " + transacionais
                        + ". O caminho ou o regex quebrou");
    }
}
