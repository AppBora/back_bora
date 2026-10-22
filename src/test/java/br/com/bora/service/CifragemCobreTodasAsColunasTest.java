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
