package br.com.bora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Todo {@code @Query} de escrita tem {@code @Modifying} colado nele — e nenhum de leitura tem.
 *
 * <h2>O defeito real que este teste existe para impedir</h2>
 * <p>Em 27/10/2026 (commit 45e20d2) um metodo novo foi inserido no {@code PedidoRepository}
 * <b>entre o {@code @Modifying} e o metodo que ele anotava</b>. Em Java a anotacao cola na PROXIMA
 * declaracao: ela foi para o {@code semAceiteConfirmado}, que e um SELECT, e o
 * {@code marcarAceiteEnviado}, que e um UPDATE, ficou sem ela. Os dois lados ficaram errados de
 * uma vez, e o arquivo continua compilando.</p>
 *
 * <p>Sem {@code @Modifying}, o Spring Data nao reconhece a consulta como escrita: ela cai em
 * {@code SingleEntityExecution}, chama {@code getSingleResult()}, e o Hibernate estoura em
 * {@code verifySelect()}. Nao e "as vezes": e em toda chamada. No caso de 27/10 o estrago era o
 * pior possivel — o {@code confirm} ia para o iFood/99, isto estourava logo depois, o evento nunca
 * era confirmado no canal, e o aceite voltava a ser enviado a cada 30 segundos por 2 horas.</p>
 *
 * <h2>Por que um teste que LE O CODIGO-FONTE</h2>
 * <p>O defeito e de ORDEM DE TEXTO, e o compilador nao reclama. Teste de comportamento tambem nao
 * pegaria: os dois testes que mencionam {@code marcarAceiteEnviado} usam
 * {@code mock(PedidoService.class)}, entao nenhum deles chega ao repositorio — e os 1238 testes do
 * projeto passaram com o defeito dentro por dois dias. Teste de integracao com banco pegaria o
 * UPDATE, mas nao pegaria o SELECT que ficou com o {@code @Modifying} sobrando, e so pegaria o
 * metodo que alguem lembrasse de exercitar. Aqui a regra vale para os 53 {@code @Query} do projeto,
 * inclusive os que ainda nao existem.</p>
 *
 * <h2>O que ele mede</h2>
 * <p>Para cada {@code @Query}, qual e a primeira palavra do JPQL e se existe {@code @Modifying} na
 * REGIAO dessa declaracao — o trecho entre o fim da declaracao anterior ({@code ;}, {@code &#123;}
 * ou {@code &#125;}) e o fim desta. Comentarios sao apagados antes, porque e exatamente ali que o
 * javadoc intruso se esconde; o conteudo dos textos tambem, para que um {@code ;} dentro de um JPQL
 * nunca seja lido como fim de declaracao.</p>
 */
class OmodifyingNaoDesgarraTest {

    private static final Path REPOSITORIOS = Paths.get("src/main/java/br/com/bora/repository");

    /** Pega as duas formas que o projeto usa: {@code @Query} e o nome por extenso. */
    private static final Pattern A_QUERY =
            Pattern.compile("@(?:org\\.springframework\\.data\\.jpa\\.repository\\.)?Query\\s*\\(");

    @Test
    void todaEscritaTemModifyingColadoNela() throws IOException {
        List<String> erros = new ArrayList<>();
        int quantas = 0;

        try (Stream<Path> fontes = Files.list(REPOSITORIOS)) {
            for (Path f : fontes.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String bruto = Files.readString(f, StandardCharsets.UTF_8);
                String limpo = semComentarios(bruto);   // o javadoc intruso sai daqui
                String esqueleto = semTextos(limpo);    // e o JPQL deixa de poder fingir pontuacao

                Matcher m = A_QUERY.matcher(esqueleto);
                while (m.find()) {
                    quantas++;
                    int fimDaAnotacao = fechaParentese(esqueleto, m.end() - 1);
                    String jpql = textosDe(limpo.substring(m.end() - 1, fimDaAnotacao + 1));
                    boolean escrita = ehEscrita(jpql);
                    String regiao = regiaoDaDeclaracao(esqueleto, m.start(), fimDaAnotacao);
                    boolean temModifying = regiao.contains("Modifying");

                    if (escrita && !temModifying) {
                        erros.add(f.getFileName() + ": ESCRITA sem @Modifying -> " + resumo(regiao)
                                + "\n      jpql: " + primeirasPalavras(jpql));
                    } else if (!escrita && temModifying) {
                        erros.add(f.getFileName() + ": LEITURA com @Modifying -> " + resumo(regiao)
                                + "\n      jpql: " + primeirasPalavras(jpql));
                    }
                }
            }
        }

        assertTrue(quantas >= 50,
                "este teste encontrou so " + quantas + " @Query — ou o pacote mudou de lugar, "
                        + "ou o reconhecedor parou de funcionar. Teste que nao mede nada passa sempre.");
        assertTrue(erros.isEmpty(),
                "@Modifying desgarrado em " + erros.size() + " lugar(es) de " + quantas + " @Query.\n"
                        + "Em Java a anotacao cola na PROXIMA declaracao: nao insira metodo entre ela e a "
                        + "assinatura dela.\n   " + String.join("\n   ", erros));
    }

    /**
     * A regiao da declaracao: do fim da declaracao anterior ate o fim desta.
     *
     * <p>Comeca depois do ultimo {@code ;}, {@code &#123;} ou {@code &#125;} antes da anotacao — e
     * por isso que o metodo intruso de 27/10 ROUBA a anotacao: o {@code ;} dele passou a ser a
     * fronteira. Termina no primeiro {@code ;} (metodo de interface) ou {@code &#123;} (metodo
     * {@code default}) depois do fim da anotacao.</p>
     */
    private static String regiaoDaDeclaracao(String esqueleto, int inicioDaAnotacao, int fimDaAnotacao) {
        int ini = 0;
        for (int i = inicioDaAnotacao - 1; i >= 0; i--) {
            char c = esqueleto.charAt(i);
            if (c == ';' || c == '{' || c == '}') { ini = i + 1; break; }
        }
        int fim = esqueleto.length();
        for (int i = fimDaAnotacao + 1; i < esqueleto.length(); i++) {
            char c = esqueleto.charAt(i);
            if (c == ';' || c == '{') { fim = i; break; }
        }
        return esqueleto.substring(ini, fim);
    }

    /** Onde fecha o parentese aberto em {@code abre}. */
    private static int fechaParentese(String s, int abre) {
        int d = 0;
        for (int i = abre; i < s.length(); i++) {
            if (s.charAt(i) == '(') d++;
            else if (s.charAt(i) == ')' && --d == 0) return i;
        }
        return s.length() - 1;
    }

    /**
     * {@code update} e {@code delete} sao escrita; o resto nao.
     *
     * <p>O JPQL do projeto e montado com {@code +} em varias linhas, entao o valor chega aqui ja
     * concatenado. {@code value = "..."} e {@code nativeQuery = true} nao atrapalham: o primeiro
     * texto da anotacao e sempre a consulta.</p>
     */
    private static boolean ehEscrita(String jpql) {
        String p = jpql.trim().toLowerCase();
        return p.startsWith("update ") || p.startsWith("delete ");
    }

    /** Junta o conteudo de todos os textos entre aspas — e o JPQL, sem as aspas e sem os {@code +}. */
    private static String textosDe(String trecho) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("\"([^\"]*)\"").matcher(trecho);
        while (m.find()) sb.append(m.group(1));
        return sb.toString();
    }

    /**
     * Troca todo comentario por espacos, SEM mexer nos indices.
     *
     * <p>Preservar o tamanho e o que permite usar os indices desta copia na outra. As quebras de
     * linha ficam, para o resumo do erro sair legivel.</p>
     */
    static String semComentarios(String fonte) {
        char[] c = fonte.toCharArray();
        int i = 0;
        boolean emTexto = false;
        while (i < c.length) {
            if (emTexto) {
                if (c[i] == '\\') { i += 2; continue; }
                if (c[i] == '"') emTexto = false;
                i++;
                continue;
            }
            if (c[i] == '"') { emTexto = true; i++; continue; }
            if (c[i] == '/' && i + 1 < c.length && c[i + 1] == '/') {
                while (i < c.length && c[i] != '\n') { c[i] = ' '; i++; }
                continue;
            }
            if (c[i] == '/' && i + 1 < c.length && c[i + 1] == '*') {
                while (i + 1 < c.length && !(c[i] == '*' && c[i + 1] == '/')) {
                    if (c[i] != '\n' && c[i] != '\r') c[i] = ' ';
                    i++;
                }
                if (i + 1 < c.length) { c[i] = ' '; c[i + 1] = ' '; i += 2; }
                continue;
            }
            i++;
        }
        return new String(c);
    }

    /** Apaga o conteudo dos textos, mantendo as aspas e os indices. Um {@code ;} de JPQL nao e fim. */
    static String semTextos(String s) {
        char[] c = s.toCharArray();
        int i = 0;
        while (i < c.length) {
            if (c[i] == '"') {
                i++;
                while (i < c.length && c[i] != '"') {
                    if (c[i] == '\\' && i + 1 < c.length) { c[i] = ' '; i++; }
                    c[i] = ' ';
                    i++;
                }
                if (i < c.length) i++;
                continue;
            }
            i++;
        }
        return new String(c);
    }

    private static String resumo(String regiao) {
        String r = regiao.replaceAll("\\s+", " ").trim();
        return r.length() <= 120 ? r : "..." + r.substring(r.length() - 117);
    }

    private static String primeirasPalavras(String jpql) {
        String j = jpql.replaceAll("\\s+", " ").trim();
        return j.length() <= 80 ? j : j.substring(0, 80) + "...";
    }
}
