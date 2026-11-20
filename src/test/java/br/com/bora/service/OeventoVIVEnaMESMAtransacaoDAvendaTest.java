package br.com.bora.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Todo evento do outbox vive na MESMA transação do fato que o gerou.
 *
 * <h2>A promessa, e a lacuna que eu medi</h2>
 * <p>A entrega A2 promete uma coisa só: <b>ou os dois existem, ou nenhum</b>. O evento é gravado
 * dentro da transação da venda, então venda sem evento e evento sem venda são impossíveis.</p>
 *
 * <p><b>Nada provava isso.</b> Medido por mim em 19/11/2026, com dois mutantes:</p>
 *
 * <pre>
 * 1) tirar o @Transactional de PedidoService.criar     -&gt; 1.628 testes, 0 falhas
 * 2) mover o eventos.dePedido do alterarStatus para
 *    DEPOIS do fim do tx.execute                       -&gt; 1.628 testes, 0 falhas
 * </pre>
 *
 * <p>Os dois quebram a promessa central da fila e passavam inteiros. O achado é da varredura de
 * 18/11 (seção 4, "medido pelos agentes, não reconferido por mim") e eu reconferi: <b>estava
 * certo</b>.</p>
 *
 * <h2>O que este teste prova, e o que não prova</h2>
 * <p>Ele é <b>estrutural</b>, e isso é deliberado: fronteira de transação não aparece em teste com
 * mock — o dublê não tem transação para abrir. O que ele garante é que <b>todo</b> ponto que emite
 * evento está coberto por uma transação, por um dos dois caminhos que este arquivo usa:
 * {@code @Transactional} no método, ou dentro de um {@code tx.execute}.</p>
 *
 * <p>Ele <b>não</b> prova que o PostgreSQL desfaz o evento junto com o fato. Isso está provado
 * contra banco de verdade em {@code OeventoEoFATOcaemJUNTOSIT}.</p>
 *
 * <p><b>E ele vale para ponto de emissão NOVO:</b> não há lista de métodos escrita à mão aqui. Ele
 * acha todas as chamadas a {@code eventos.dePedido} e exige cobertura de cada uma — quem
 * acrescentar um gancho novo fora de transação encontra este teste vermelho.</p>
 */
class OeventoVIVEnaMESMAtransacaoDAvendaTest {

    private static final Path ARQUIVO =
            Path.of("src/main/java/br/com/bora/service/PedidoService.java");

    private static String fonte() throws Exception {
        assertTrue(Files.exists(ARQUIVO), "nao achei o PedidoService: " + ARQUIVO.toAbsolutePath());
        return Files.readString(ARQUIVO, StandardCharsets.UTF_8);
    }

    /** Um ponto que emite evento, com o que foi descoberto sobre ele. */
    private record Emissao(int linha, String metodo, boolean metodoTransacional, boolean dentroDeTx) {
        boolean coberto() { return metodoTransacional || dentroDeTx; }
    }

    /**
     * O fonte com comentário e texto apagados (trocados por espaço), mantendo as linhas.
     *
     * <p><b>Isto não é refinamento: sem ele este teste MENTE.</b> A primeira versão contava chaves
     * no fonte cru, e o {@code PedidoService} tem, DENTRO de um comentário, a sequência que fecha o
     * bloco do {@code tx.execute}. Aquela chave sem par derrubava a profundidade e o leitor concluía
     * que a emissão seguinte estava FORA do bloco — acusando um código correto. É a mesma armadilha
     * que uma chave dentro de comentário de CSS me pregou no mesmo dia.</p>
     */
    private static String soCodigo(String fonte) {
        final char ASPA = '"';
        final char APOSTROFO = '\'';
        final char BARRA = '\\';
        StringBuilder saida = new StringBuilder(fonte.length());
        boolean emBloco = false, emTexto = false, emChar = false;
        for (int i = 0; i < fonte.length(); i++) {
            char c = fonte.charAt(i);
            char prox = i + 1 < fonte.length() ? fonte.charAt(i + 1) : ' ';
            if (c == '\n') {
                saida.append(c);
                emTexto = false;
                emChar = false;
                continue;
            }
            if (emBloco) {
                if (c == '*' && prox == '/') { emBloco = false; saida.append("  "); i++; }
                else saida.append(' ');
                continue;
            }
            if (emTexto) {
                saida.append(' ');
                if (c == BARRA) { saida.append(' '); i++; }
                else if (c == ASPA) emTexto = false;
                continue;
            }
            if (emChar) {
                saida.append(' ');
                if (c == BARRA) { saida.append(' '); i++; }
                else if (c == APOSTROFO) emChar = false;
                continue;
            }
            if (c == '/' && prox == '*') { emBloco = true; saida.append("  "); i++; continue; }
            if (c == '/' && prox == '/') {
                while (i < fonte.length() && fonte.charAt(i) != '\n') { saida.append(' '); i++; }
                if (i < fonte.length()) saida.append('\n');
                continue;
            }
            if (c == ASPA) { emTexto = true; saida.append(' '); continue; }
            if (c == APOSTROFO) { emChar = true; saida.append(' '); continue; }
            saida.append(c);
        }
        return saida.toString();
    }

    /**
     * Acha cada {@code eventos.dePedido(} e descobre o método e a cobertura.
     *
     * <p>O método de um ponto é a última assinatura de nível de método (indentação de exatamente 4)
     * antes dele. A anotação é procurada acima dessa assinatura, e <b>nas duas formas que o arquivo
     * usa</b>: a curta e a totalmente qualificada — procurar só a curta foi o meu erro na primeira
     * versão, e ela acusou dois métodos que estão corretos.</p>
     *
     * <p>"Dentro de {@code tx.execute}" é decidido por <b>profundidade de chaves no fonte limpo</b>:
     * enquanto o nível estiver acima do nível em que o {@code tx.execute(} abriu, a chamada está
     * dentro do lambda. Chave é mais confiável que parêntese aqui, porque a chamada do evento ocupa
     * várias linhas e os parênteses dela sobem e descem no meio do bloco.</p>
     */
    private static List<Emissao> emissoes(String fonte) {
        String[] linhas = fonte.split("\n", -1);
        String[] limpas = soCodigo(fonte).split("\n", -1);
        List<Emissao> achadas = new ArrayList<>();

        String metodoAtual = "(fora de metodo)";
        boolean transacionalAtual = false;
        boolean anotacaoPendente = false;
        int profundidade = 0;
        int profundidadeDoTx = -1;

        for (int i = 0; i < linhas.length; i++) {
            String bruta = linhas[i];
            String limpa = i < limpas.length ? limpas[i] : "";
            String semEspaco = bruta.strip();

            if (semEspaco.startsWith("@") && semEspaco.contains("Transactional")) {
                anotacaoPendente = true;
                continue;
            }

            boolean assinatura = bruta.startsWith("    ") && !bruta.startsWith("     ")
                    && (semEspaco.startsWith("public ") || semEspaco.startsWith("private ")
                        || semEspaco.startsWith("protected "))
                    && bruta.contains("(") && !semEspaco.endsWith(";");
            if (assinatura) {
                metodoAtual = semEspaco;
                transacionalAtual = anotacaoPendente;
                anotacaoPendente = false;
                profundidadeDoTx = -1;
            } else if (!semEspaco.isEmpty() && !semEspaco.startsWith("*")
                    && !semEspaco.startsWith("/*") && !semEspaco.startsWith("//")
                    && !semEspaco.startsWith("@")) {
                anotacaoPendente = false;
            }

            boolean abriuTx = limpa.contains("tx.execute(");
            int nivelAntes = profundidade;

            if (limpa.contains("eventos.dePedido(")) {
                boolean dentro = profundidadeDoTx >= 0 && profundidade > profundidadeDoTx;
                achadas.add(new Emissao(i + 1, metodoAtual, transacionalAtual, dentro));
            }

            for (char c : limpa.toCharArray()) {
                if (c == '{') profundidade++;
                else if (c == '}') profundidade--;
            }

            if (abriuTx) profundidadeDoTx = nivelAntes;
            else if (profundidadeDoTx >= 0 && profundidade <= profundidadeDoTx) profundidadeDoTx = -1;
        }
        return achadas;
    }

    // ----------------------------------------------------------------- a premissa

    /**
     * A PREMISSA: o leitor acha os pontos de emissão. Sem isto, uma lista vazia passaria por
     * "nenhum ponto descoberto" e o teste viraria enfeite.
     */
    @Test
    void aPREMISSAoLEITORachaOSpontosDEemissao() throws Exception {
        List<Emissao> e = emissoes(fonte());
        assertTrue(e.size() >= 5,
                "achei so " + e.size() + " ponto(s) que emite evento no PedidoService, e a varredura "
                        + "mediu 6. O leitor deste teste parou de entender o arquivo — revise antes "
                        + "de confiar no resultado dele.");
    }

    /** E a premissa inversa: ele sabe distinguir coberto de descoberto, nos tres casos. */
    @Test
    void aPREMISSAoLEITORsabeVERumPONTOdescoberto() {
        String falso = """
                class X {
                    public void semTransacao() {
                        eventos.dePedido("PedidoRecebido", p, Map.of());
                    }
                    @Transactional
                    public void comAnotacaoCurta() {
                        eventos.dePedido("PedidoRecebido", p, Map.of());
                    }
                    @org.springframework.transaction.annotation.Transactional
                    public void comAnotacaoLonga() {
                        eventos.dePedido("PedidoRecebido", p, Map.of());
                    }
                    public void comBloco() {
                        Pedido salvo = tx.execute(st -> {
                            // a chave de fechamento }); escrita neste comentario nao pode confundir
                            eventos.dePedido("PedidoRecebido", p, Map.of());
                            return p;
                        });
                    }
                }
                """;
        List<Emissao> e = emissoes(falso);
        assertEquals(4, e.size(), "o leitor tinha de achar os quatro pontos do exemplo");
        assertFalse(e.get(0).coberto(), "o primeiro NAO tem transacao e tinha de aparecer descoberto");
        assertTrue(e.get(1).metodoTransacional(), "a anotacao curta tem de ser reconhecida");
        assertTrue(e.get(2).metodoTransacional(), "a anotacao LONGA tem de ser reconhecida");
        assertTrue(e.get(3).dentroDeTx(),
                "o ponto dentro do tx.execute tem de ser reconhecido, inclusive com a sequencia de "
                        + "fechamento escrita num comentario antes dele");
    }

    // ----------------------------------------------------------------- o que importa

    /** TODO ponto de emissão está dentro de uma transação. É este teste que mata os dois mutantes. */
    @Test
    void TODOpontoQUEemiteEVENTOestaDENTROdeUMAtransacao() throws Exception {
        List<Emissao> descobertos = emissoes(fonte()).stream().filter(e -> !e.coberto()).toList();

        StringBuilder onde = new StringBuilder();
        for (Emissao e : descobertos) {
            onde.append("   linha ").append(e.linha()).append(", em ").append(e.metodo()).append("\n");
        }

        assertTrue(descobertos.isEmpty(),
                "ha ponto(s) emitindo evento FORA de transacao no PedidoService. A promessa da fila "
                        + "(ou os dois existem, ou nenhum) se perde ali: da para gravar venda sem "
                        + "evento, ou evento de venda que foi desfeita.\n" + onde);
    }

    /** E o `criar`, especificamente — foi o mutante 1. */
    @Test
    void oCRIARcontinuaTRANSACIONAL() throws Exception {
        String f = fonte();
        int i = f.indexOf("public Pedido criar(NovoPedidoRequest req, String chaveIdempotencia)");
        assertTrue(i > 0, "nao achei o criar(req, chave)");
        String antes = f.substring(Math.max(0, i - 120), i);
        assertTrue(antes.contains("Transactional"),
                "o `criar` perdeu o @Transactional. Sem ele, o evento e a venda viram duas "
                        + "transacoes: a venda commita e o evento pode nao existir. Mutante medido "
                        + "em 19/11/2026 — passava 1.628 testes.");
    }

    /** E o `alterarStatus`, que usa o outro caminho — foi o mutante 2. */
    @Test
    void oALTERARSTATUScontinuaComOeventoDENTROdoBLOCO() throws Exception {
        List<Emissao> emAlterarStatus = emissoes(fonte()).stream()
                .filter(e -> e.metodo().contains("alterarStatus"))
                .toList();
        assertFalse(emAlterarStatus.isEmpty(), "nao achei emissao de evento no alterarStatus");
        for (Emissao e : emAlterarStatus) {
            assertTrue(e.dentroDeTx(),
                    "a emissao da linha " + e.linha() + " saiu de DENTRO do tx.execute. Depois do "
                            + "fim do bloco e outra transacao — e e justamente onde o estorno de "
                            + "estoque acontece. Mutante medido em 19/11/2026: passava 1.628 testes.");
        }
    }
}
