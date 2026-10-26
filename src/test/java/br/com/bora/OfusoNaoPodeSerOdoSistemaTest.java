package br.com.bora;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data sem fuso e proibida no codigo de producao — e isto ja mordeu quatro vezes.
 *
 * <h2>Por que um teste que le arquivos</h2>
 * <p>{@code LocalDate.now()} e {@code LocalDateTime.now()} usam o fuso do SISTEMA. Na maquina de
 * desenvolvimento o padrao e America/Sao_Paulo e tudo funciona; <b>o container roda em UTC</b>.
 * Entre 00:00 e 03:00 UTC — ou seja das 21h a meia-noite de Brasilia, <b>dentro do horario de
 * jantar</b> — o "hoje" sem fuso ja e o dia seguinte.</p>
 *
 * <p>Nao da para pegar isso com teste de comportamento sem amarrar o teste a hora do dia: o defeito
 * aparece em tres das vinte e quatro horas, e passa nas outras vinte e uma. Foi exatamente assim que
 * ele sobreviveu tanto tempo — e foi descoberto cada vez por acidente, sempre a noite.</p>
 *
 * <h2>Os quatro casos reais</h2>
 * <ul>
 *   <li><b>Cupom</b>: o cupom com validade "hoje" deixava de valer as 21h. O lojista divulgava e o
 *       cliente levava "cupom vencido" na hora do pedido.</li>
 *   <li><b>PagamentoForaDoAsaas</b> e <b>PromocaoDaDescontoDeVerdade</b>: dois testes que passavam
 *       na maquina e falhavam no container. Os dois tem o comentario sobre isso.</li>
 *   <li><b>AssinaturaService</b> (24/10/2026): o {@code nextDue} da assinatura. O teste gratis
 *       nascia com 8 dias em vez de 7, e a reassinatura vencia em 2 dias em vez de 1 — justo o
 *       numero que aquele bloco existe para conter.</li>
 *   <li><b>LiberarAcessoNaoInventaPagamentoTest</b> (24/10/2026): o pior, porque o
 *       {@code Dockerfile} roda {@code mvn package} e teste vermelho impede a imagem de nascer.
 *       <b>Qualquer deploy tentado nessas tres horas falhava.</b> Descoberto tentando subir a v146
 *       as 21h15.</li>
 * </ul>
 *
 * <p>Nada contra {@code OffsetDateTime.now()} ou {@code Instant.now()}: esses carregam o instante e
 * nao dependem de fuso para dizer QUANDO. O problema e so com data e hora locais, que precisam saber
 * de qual relogio estao falando — e o relogio que importa aqui e o da loja.</p>
 */
class OfusoNaoPodeSerOdoSistemaTest {

    private static final Path FONTES = Paths.get("src/main/java");

    /**
     * Tudo que resolve fuso pelo SISTEMA. A lista existe porque a primeira versao deste teste
     * proibia dois padroes e eu disse ao dono que ela "proibia a classe toda".
     *
     * <h2>O que escapou, e o que custou (achado pelo QA em 26/10/2026)</h2>
     * <p>A versao de 25/10 procurava so {@code LocalDate.now()} e {@code LocalDateTime.now()}.
     * Passaram tres instancias, todas em caminho de dinheiro:</p>
     * <ul>
     *   <li>{@code AsaasWebhookController.dia()} com {@code ZoneId.systemDefault()} — gravava o dia
     *       do pagamento em UTC enquanto a guarda contra duplicidade procurava em Brasilia. As tres
     *       primeiras horas do dia ficavam fora da janela, e o faturamento podia contar o mesmo
     *       R$ 199 duas vezes. Esse faturamento e a base da nota fiscal.</li>
     *   <li>{@code PlataformaController.faturamento()} com {@code YearMonth.now()} — entre 21h e
     *       meia-noite do ultimo dia do mes, a tela mostrava o mes seguinte, zerado.</li>
     *   <li>o mesmo metodo com {@code ZoneId.systemDefault()} nas bordas do mes.</li>
     * </ul>
     *
     * <p><b>Licao:</b> teste que proibe "alguns padroes" nao proibe uma classe de defeito. Quando o
     * mesmo defeito aparece pela terceira vez, a lista tem de cobrir as formas que ele sabe tomar —
     * e crescer quando aparecer uma nova.</p>
     *
     * <p>Nao entram aqui {@code OffsetDateTime.now()} nem {@code Instant.now()}: esses carregam o
     * instante e nao dependem de fuso para dizer QUANDO. O problema e so com data e hora locais,
     * que precisam saber de qual relogio estao falando — e o relogio que importa e o da loja.</p>
     */
    private static final List<String> PROIBIDOS = List.of(
            "LocalDate.now()",
            "LocalDateTime.now()",
            "LocalTime.now()",
            "YearMonth.now()",
            "ZoneId.systemDefault()",
            "TimeZone.getDefault()");

    @Test
    void nenhumaDataLOCALsemFUSOnoCodigoDePRODUCAO() throws IOException {
        List<String> achados = new ArrayList<>();
        try (Stream<Path> arquivos = Files.walk(FONTES)) {
            for (Path p : arquivos.filter(f -> f.toString().endsWith(".java")).toList()) {
                String fonte = Files.readString(p);
                String[] linhas = fonte.split("\n", -1);
                for (int i = 0; i < linhas.length; i++) {
                    String l = linhas[i];
                    // Comentario e javadoc falam DESTE defeito de proposito; nao sao chamadas.
                    String semEspaco = l.stripLeading();
                    if (semEspaco.startsWith("//") || semEspaco.startsWith("*")
                            || semEspaco.startsWith("/*")) continue;
                    for (String proibido : PROIBIDOS) {
                        if (l.contains(proibido)) {
                            achados.add(FONTES.relativize(p) + ":" + (i + 1) + "  " + l.trim());
                            break;
                        }
                    }
                }
            }
        }
        assertTrue(achados.isEmpty(),
                "data local sem fuso no codigo de producao. O container roda em UTC, e das 21h a "
                        + "meia-noite de Brasilia o \"hoje\" sem fuso ja e amanha. Use "
                        + "LocalDate.now(ZONE) com ZoneId.of(\"America/Sao_Paulo\"), como fazem o "
                        + "Cupom e os services:\n  " + String.join("\n  ", achados));
    }

    @Test
    void PREMISSA_oTesteESTAlendoOcodigoDeVERDADE() throws IOException {
        // Sem isto, um caminho errado faria o teste acima passar varrendo pasta vazia — a pior forma
        // de falhar, porque ninguem olha um teste verde.
        assertTrue(Files.isDirectory(FONTES), "nao achei src/main/java a partir de " + Paths.get("").toAbsolutePath());
        long java;
        try (Stream<Path> s = Files.walk(FONTES)) {
            java = s.filter(f -> f.toString().endsWith(".java")).count();
        }
        assertTrue(java > 100, "esperava mais de 100 arquivos .java; achei " + java);

        // E prova que o padrao PEGA de verdade, contra um texto conhecido.
        String isca = "        String due = LocalDate.now().plusDays(7).toString();";
        assertTrue(isca.contains("LocalDate.now()"), "o padrao buscado nao casa nem com a isca");
    }

    @Test
    void oFUSOcorretoESTAemUSOnosLUGARESqueJAforamCONSERTADOS() throws IOException {
        // O teste de cima proibe o errado. Este confirma que o certo esta la -- senao alguem poderia
        // "passar" apagando a linha em vez de consertando o fuso.
        for (String[] alvo : new String[][] {
                { "entity/Cupom.java", "LocalDate.now(ZONE)" },
                { "service/AssinaturaService.java", "LocalDate.now(ZONE)" },
                { "service/PixService.java", "LocalDate.now(ZONE)" },
        }) {
            String fonte = Files.readString(FONTES.resolve("br/com/bora").resolve(alvo[0]));
            assertTrue(fonte.contains(alvo[1]),
                    alvo[0] + " perdeu o " + alvo[1] + ": ou voltou ao fuso do sistema, ou a data "
                            + "deixou de ser calculada -- as duas coisas merecem um olhar");
        }
    }
}
