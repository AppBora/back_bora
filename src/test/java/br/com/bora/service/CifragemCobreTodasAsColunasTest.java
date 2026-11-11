package br.com.bora.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Toda coluna cifrada está na lista do serviço que cifra o que já está gravado.
 *
 * <h2>Por que este teste existe (item 8.4)</h2>
 * <p>O cifrador cifra <b>na escrita</b>. Quem põe {@code @Convert(SegredoGuardado)} numa coluna nova
 * protege o que for gravado dali para frente — e a credencial que já está lá fica em <b>texto puro
 * para sempre</b>, porque nada a regrava. Foi exatamente o que aconteceu com
 * {@code config_plataforma.valor}: o cifrador existe desde a v114 e essa tabela ficou de fora, e é a
 * que guarda o {@code client_secret} do iFood e da 99 <b>da plataforma</b>.</p>
 *
 * <p>Então: coluna cifrada nova sem entrada no {@code CifragemEmRepousoService} reprova aqui. É o
 * único lugar onde esse esquecimento aparece — ele não quebra nada, não dá erro, e só se descobre num
 * dump.</p>
 */
class CifragemCobreTodasAsColunasTest {

    private static final Path ENTIDADES = Path.of("src/main/java/br/com/bora/entity");
    private static final Path SERVICO =
            Path.of("src/main/java/br/com/bora/service/CifragemEmRepousoService.java");

    /** Uma coluna que passa pelo cifrador, achada no fonte das entidades. */
    private record Cifrada(String arquivo, String coluna) {}

    @Test
    void todaCOLUNAcifradaESTAnaLISTAdoSERVICO() throws IOException {
        String servico = Files.readString(SERVICO, StandardCharsets.UTF_8);
        List<Cifrada> deFora = new ArrayList<>();

        for (Cifrada c : cifradas()) {
            if (!servico.contains("\"" + c.coluna() + "\"")) deFora.add(c);
        }

        assertTrue(deFora.isEmpty(),
                "estas colunas passam pelo cifrador mas NAO estao na lista do CifragemEmRepousoService. "
                        + "O que ja estiver gravado nelas fica em texto puro para sempre, porque nada "
                        + "regrava. Acrescente cada uma em ALVOS: " + deFora);
    }

    @Test
    void oTESTEestaREALMENTEolhandoAsENTIDADES() throws IOException {
        // Sem isto, um erro no caminho ou no regex faria a lista vir vazia e o teste acima passar
        // medindo nada -- a pior forma de falhar.
        List<Cifrada> todas = cifradas();

        assertTrue(todas.size() >= 5, "esperava achar pelo menos 5 colunas cifradas, achei "
                + todas.size() + ". O caminho ou o regex quebrou.");
        assertTrue(todas.stream().anyMatch(c -> c.coluna().equals("asaas_api_key")),
                "nao achei a chave Asaas da loja, que e a que move dinheiro");
        assertTrue(todas.stream().anyMatch(c -> c.coluna().equals("valor")),
                "nao achei o config_plataforma.valor, que e o item 8.4");
    }

    @Test
    void aLISTAdoSERVICOnaoTEMcolunaINVENTADA() throws IOException {
        // O contrario do primeiro teste: entrada na lista para coluna que nao e cifrada faria o
        // serviço cifrar algo que o JPA vai ler em claro -- e aí o valor volta ilegivel para quem usa.
        String servico = Files.readString(SERVICO, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("new Alvo\\(\"(\\w+)\", \"\\w+\", \"(\\w+)\"\\)").matcher(servico);
        List<String> colunasDaLista = new ArrayList<>();
        while (m.find()) colunasDaLista.add(m.group(2));

        assertFalse(colunasDaLista.isEmpty(), "nao achei nenhuma entrada ALVOS no servico");
        List<String> cifradasNoCodigo = cifradas().stream().map(Cifrada::coluna).toList();
        for (String coluna : colunasDaLista) {
            assertTrue(cifradasNoCodigo.contains(coluna),
                    "a lista do CifragemEmRepousoService tem \"" + coluna + "\", mas nenhuma entidade "
                            + "cifra essa coluna. Cifrar valor que o JPA le em claro deixa o dado "
                            + "ilegivel para quem usa.");
        }
    }

    /**
     * O CAMINHO INVERSO: toda coluna de segredo tem que PASSAR pelo cifrador.
     *
     * <p>Os testes acima cobram que toda coluna <b>com</b> {@code @Convert} esteja na lista do
     * serviço. Nenhum deles cobra o que falhou de verdade: uma coluna de credencial que nunca ganhou
     * o {@code @Convert}. Essa não aparece em lista nenhuma, e por isso não aparecia em teste nenhum.
     *
     * <p><b>Medido em produção em 11/11/2026</b>, no meio da tarefa de cifrar as que estavam em texto
     * puro: {@code loja.asaas_webhook_token} tinha 1 valor gravado, em claro, sem {@code @Convert}.
     * Varri o {@code information_schema} inteiro atrás de colunas com cara de segredo justamente
     * porque cifrar 20 e dizer "pronto" deixaria a 21ª no dump de todo dia. Era a única.
     *
     * <p>A lista de exceções é curta de propósito, e cada uma tem o motivo escrito. Coluna de segredo
     * nova sem {@code @Convert} e sem motivo aqui reprova.
     */
    @Test
    void todaCOLUNAdeSEGREDOpassaPELOcifrador() throws IOException {
        // Coluna que casa com isto e NAO e cifrada tem que estar nas excecoes abaixo, com motivo.
        Pattern cheiroDeSegredo =
                Pattern.compile("secret|token|senha|password|api_?key|credencial", Pattern.CASE_INSENSITIVE);

        // As excecoes, uma por uma, com o porque:
        Map<String, String> excecoes = Map.of(
                // Hash BCrypt, nao segredo recuperavel: ja e irreversivel, e quem o compara e o
                // encoder. Cifrar um hash nao protege nada que o hash nao proteja.
                "senha_hash", "e hash BCrypt, nao credencial recuperavel");

        List<String> deFora = new ArrayList<>();
        for (Campo c : camposDeTexto()) {
            if (!cheiroDeSegredo.matcher(c.coluna()).find()) continue;
            if (c.cifrado() || excecoes.containsKey(c.coluna())) continue;
            deFora.add(c.arquivo() + " -> " + c.coluna());
        }

        assertTrue(deFora.isEmpty(),
                "estas colunas tem cara de credencial e NAO passam pelo cifrador: saem em texto puro "
                        + "em todo pg_dump. Ponha @Convert(SegredoGuardado) e a entrada em ALVOS, ou, "
                        + "se nao for segredo, acrescente a excecao COM O MOTIVO neste teste: " + deFora);
    }

    @Test
    void oINVERSOestaREALMENTEenxergandoOsCAMPOS() throws IOException {
        // O mesmo cuidado do outro teste-do-teste: se a leitura do fonte quebrar, a lista vem vazia e
        // o teste acima passa medindo nada.
        List<Campo> todos = camposDeTexto();

        assertTrue(todos.size() >= 40, "esperava pelo menos 40 campos de texto nas entidades, achei "
                + todos.size() + ". A leitura do fonte quebrou.");
        Campo whk = todos.stream().filter(c -> c.coluna().equals("asaas_webhook_token")).findFirst()
                .orElseThrow(() -> new AssertionError("nao achei loja.asaas_webhook_token"));
        assertTrue(whk.cifrado(), "o asaas_webhook_token tem que estar cifrado: era a coluna de "
                + "credencial que estava em texto puro no banco em 11/11/2026");
        Campo senha = todos.stream().filter(c -> c.coluna().equals("senha_hash")).findFirst()
                .orElseThrow(() -> new AssertionError("nao achei usuario.senha_hash; o regex quebrou"));
        assertFalse(senha.cifrado(), "o senha_hash nao e cifrado, e por isso ele prova que a excecao "
                + "do teste acima esta sendo exercitada de verdade");
    }

    @Test
    void oSERVICOnaoGRAVAoQUEjaESTAcifrado() throws IOException {
        // A idempotencia: rodar duas vezes nao pode cifrar duas vezes nem reescrever a toa.
        String servico = Files.readString(SERVICO, StandardCharsets.UTF_8);

        assertTrue(servico.contains("not like 'enc:v1:%'"),
                "a consulta tem que excluir o que ja esta cifrado, senao rodar de novo reescreve tudo");
        assertTrue(servico.contains("cifrado.equals(claro)"),
                "e se o cifrador devolver o mesmo valor (chave desligada), nao se grava nada");
    }

    @Test
    void oSERVICOnaoIMPRIMEvalorNENHUM() throws IOException {
        // Ele mexe em credencial de terceiro. Um log com o valor dentro seria o mesmo furo noutro
        // lugar -- e o log vai para arquivo, que vai para backup.
        String servico = Files.readString(SERVICO, StandardCharsets.UTF_8);
        assertTrue(servico.contains("AUDITORIA.warn"), "tem que haver log de auditoria");

        // TODA chamada de log do arquivo, nao so a de auditoria: a primeira versao deste teste olhava
        // uma linha, e uma mutacao que pusesse `log.warn("{}", claro)` DENTRO do laco -- onde a
        // variavel em claro existe -- passaria verde.
        Matcher m = Pattern.compile(
                "(?:log|AUDITORIA)\\.(?:trace|debug|info|warn|error)\\s*\\(([^;]*);",
                Pattern.DOTALL).matcher(servico);
        List<String> comSegredo = new ArrayList<>();
        int chamadas = 0;
        while (m.find()) {
            chamadas++;
            String args = m.group(1);
            if (args.matches("(?s).*\\bclaro\\b.*") || args.matches("(?s).*\\bcifrado\\b.*")) {
                comSegredo.add(args.replaceAll("\\s+", " "));
            }
        }

        assertTrue(chamadas >= 1, "nao achei chamada de log nenhuma; o regex quebrou");
        assertTrue(comSegredo.isEmpty(),
                "estas chamadas de log mencionam a credencial. O log vai para arquivo, e o arquivo vai "
                        + "para backup -- seria o mesmo furo noutro lugar: " + comSegredo);
    }

    // ------------------------------------------------------------------ a leitura do fonte

    /** Um campo de texto de entidade: a coluna no banco e se ela passa pelo cifrador. */
    private record Campo(String arquivo, String coluna, boolean cifrado) {}

    /**
     * Todo campo {@code String} das entidades, com o nome da coluna e se tem {@code @Convert}.
     *
     * <p>Só campo de texto: o cifrador é {@code AttributeConverter<String, String>}, então data,
     * número e booleano não entram nem poderiam.</p>
     */
    private List<Campo> camposDeTexto() throws IOException {
        assertTrue(Files.isDirectory(ENTIDADES), "nao achei " + ENTIDADES.toAbsolutePath());
        Pattern declaracao = Pattern.compile(
                "^\\s*(?:public|private|protected)\\s+(?:final\\s+)?(?:java\\.lang\\.)?String\\s+(\\w+)\\s*[;=]");
        Pattern nomeDaColuna = Pattern.compile("@Column\\s*\\([^)]*name\\s*=\\s*\"(\\w+)\"");
        List<Campo> out = new ArrayList<>();
        try (var arquivos = Files.list(ENTIDADES)) {
            for (Path f : arquivos.filter(x -> x.toString().endsWith(".java")).toList()) {
                // As anotacoes do campo que vem a seguir. Javadoc e qualquer outra linha zeram a
                // pilha: anotacao vem SEMPRE logo antes do campo, depois do javadoc.
                List<String> anotacoes = new ArrayList<>();
                for (String linha : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String t = linha.trim();
                    if (t.startsWith("@")) { anotacoes.add(t); continue; }
                    Matcher d = declaracao.matcher(linha);
                    if (d.find()) {
                        String juntas = String.join("\n", anotacoes);
                        Matcher c = nomeDaColuna.matcher(juntas);
                        String coluna = c.find() ? c.group(1) : paraSnake(d.group(1));
                        out.add(new Campo(f.getFileName().toString(), coluna,
                                juntas.contains(CONVERSOR)));
                    }
                    anotacoes.clear();
                }
            }
        }
        return out;
    }

    /** {@code asaasWebhookToken} -> {@code asaas_webhook_token}: o padrao do Hibernate sem @Column. */
    private static String paraSnake(String campo) {
        return campo.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    /** Cada {@code @Convert(SegredoGuardado)} do projeto, com o nome da coluna no banco. */
    private List<Cifrada> cifradas() throws IOException {
        assertTrue(Files.isDirectory(ENTIDADES), "nao achei " + ENTIDADES.toAbsolutePath());
        List<Cifrada> achadas = new ArrayList<>();
        Pattern nomeDaColuna = Pattern.compile("@Column\\s*\\([^)]*name\\s*=\\s*\"(\\w+)\"");
        Pattern nomeDoCampo = Pattern.compile("(?:public|private|protected)\\s+\\S+\\s+(\\w+)\\s*[;=]");
        try (var arquivos = Files.list(ENTIDADES)) {
            for (Path f : arquivos.filter(x -> x.toString().endsWith(".java")).toList()) {
                String fonte = Files.readString(f, StandardCharsets.UTF_8);
                int i = fonte.indexOf(CONVERSOR);
                while (i >= 0) {
                    // O @Column pode vir ANTES (olha para tras) ou o nome vir do proprio campo
                    // (olha para frente). A segunda forma e a do ConfigPlataforma, e a primeira
                    // versao deste teste nao a enxergava -- passou verde sem ver o item 8.4.
                    String antes = fonte.substring(Math.max(0, i - 200), i);
                    String depois = fonte.substring(i + CONVERSOR.length(),
                            Math.min(fonte.length(), i + CONVERSOR.length() + 200));
                    Matcher c = nomeDaColuna.matcher(antes);
                    String coluna = null;
                    while (c.find()) coluna = c.group(1);   // o @Column mais proximo
                    if (coluna == null) {
                        Matcher cd = nomeDaColuna.matcher(depois);
                        if (cd.find()) coluna = cd.group(1);
                    }
                    if (coluna == null) {
                        Matcher campo = nomeDoCampo.matcher(depois);
                        if (campo.find()) coluna = campo.group(1);
                    }
                    assertNotNull(coluna, "achei um @Convert(SegredoGuardado) em "
                            + f.getFileName() + " e nao consegui descobrir o nome da coluna");
                    achadas.add(new Cifrada(f.getFileName().toString(), coluna));
                    i = fonte.indexOf(CONVERSOR, i + 1);
                }
            }
        }
        return achadas;
    }

    private static final String CONVERSOR =
            "@jakarta.persistence.Convert(converter = br.com.bora.config.SegredoGuardado.class)";

}
