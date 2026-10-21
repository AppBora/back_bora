package br.com.bora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Toda consulta que ESCREVE no banco filtra por loja, e junta essa cláusula com {@code and}.
 *
 * <h2>Por que este teste existe</h2>
 * <p>Em 20/10/2026, pela <b>terceira vez no mesmo dia</b>, uma mutação dentro de uma {@code @Query}
 * passou com a suíte inteira verde. A pior foi esta: trocar
 * {@code where p.id = :id and p.lojaId = :lojaId} por {@code ... or p.lojaId = :lojaId} na exclusão de
 * pedido. Isso apaga <b>todos os pedidos da loja</b> a cada clique na lixeira — e 481 testes passavam.
 * Só o teste de integração acusou, e os {@code *IT} são excluídos do {@code mvn test} (pom.xml:34) e
 * do build da imagem do deploy, porque precisam de um Postgres no ar.</p>
 *
 * <p>Consertar caso a caso não resolve: o próximo {@code @Modifying} que alguém escrever cai no mesmo
 * buraco. Este teste lê <b>todas</b> as consultas de escrita do projeto e cobra a cláusula. Ele não
 * prova comportamento nenhum — quem prova é o banco, em {@link EstoqueNaoPerdeBaixaIT} e
 * {@link ApagarPedidoNaoVazaEntreLojasIT}. Mas roda no caminho que o deploy percorre.</p>
 *
 * <h2>As duas exceções, e por que são seguras HOJE</h2>
 * <p>Medido em 20/10/2026, lendo os chamadores:</p>
 * <ul>
 *   <li>{@code PedidoRepository.amarrarAoAcerto} — os ids vêm de
 *       {@code pedidos.entregasParaAcerto(lojaId, ...)}, já filtrado por loja
 *       ({@code AcertoEntregadorService:71}).</li>
 *   <li>{@code PromocaoRepository.somarUmUso} — o id vem de
 *       {@code descontos.resolver(lojaId, codigo, total)}, já filtrado por loja
 *       ({@code DescontoService:56}).</li>
 * </ul>
 * <p><b>Não há brecha aberta</b>: nenhuma das duas aceita um id que não tenha passado por uma leitura
 * da loja. O que falta é defesa em profundidade — se amanhã alguém chamar esses métodos a partir de
 * outro caminho, nada no SQL impede. Corrigir exige mudar as assinaturas e os chamadores, então ficou
 * registrado aqui em vez de feito às escondidas.</p>
 */
class TodaConsultaQueEscreveFiltraPorLojaTest {

    private static final Path REPOS = Path.of("src/main/java/br/com/bora/repository");

    /**
     * As consultas que sabidamente não filtram por loja, com o porquê no javadoc da classe.
     *
     * <p>Entrar aqui tem que ser uma <b>decisão</b>, não um atalho para o teste passar: quem adicionar
     * um nome precisa escrever no javadoc de onde vem o id e por que ele já está preso à loja.</p>
     */
    private static final Set<String> EXCECOES_CONHECIDAS = Set.of(
            "amarrarAoAcerto",   // ids vêm de entregasParaAcerto(lojaId, ...)
            "somarUmUso"         // id vem de resolver(lojaId, codigo, total)
    );

    /** Uma consulta de escrita achada no fonte. */
    private record Escrita(String arquivo, String metodo, String consulta) {}

    @Test
    void todaConsultaDeEscritaOuFiltraPorLojaOuEstaNaListaDeExcecoes() throws IOException {
        List<Escrita> semFiltro = new ArrayList<>();
        for (Escrita e : escritas()) {
            // Duas formas, porque o projeto usa as duas: JPQL (`c.lojaId = :lojaId`) e SQL nativo
            // (`loja_id = :lojaId`). A primeira versao deste teste so entendia JPQL e reprovou uma
            // consulta nativa que ESTAVA filtrada -- o teste e que nao sabia ler.
            boolean filtra = e.consulta().matches("(?s).*\\b\\w+\\.lojaId\\s*=\\s*:lojaId\\b.*")
                    || e.consulta().matches("(?s).*\\bloja_id\\s*=\\s*:lojaId\\b.*");
            if (!filtra && !EXCECOES_CONHECIDAS.contains(e.metodo())) {
                semFiltro.add(e);
            }
        }
        assertTrue(semFiltro.isEmpty(),
                "estas consultas ESCREVEM no banco sem filtrar por loja. Acrescente "
                        + "`and <alias>.lojaId = :lojaId`, ou — se o id realmente ja vem de uma "
                        + "leitura da loja — adicione o nome em EXCECOES_CONHECIDAS e EXPLIQUE no "
                        + "javadoc desta classe de onde ele vem: " + semFiltro);
    }

    @Test
    void oFiltroDeLojaEhJuntadoComANDeNuncaComOR() throws IOException {
        // A mutação que passou verde: `and p.lojaId = :lojaId` virando `or p.lojaId = :lojaId` numa
        // exclusão. Com `or`, um DELETE pelo id apaga a loja inteira.
        List<String> suspeitas = new ArrayList<>();
        for (Escrita e : escritas()) {
            if (e.consulta().matches("(?s).*\\bor\\s+\\w+\\.lojaId\\s*=\\s*:lojaId\\b.*")) {
                suspeitas.add(e.arquivo() + "." + e.metodo());
            }
        }
        assertTrue(suspeitas.isEmpty(),
                "o filtro de loja esta ligado por OR, o que o torna inutil -- um DELETE ou UPDATE "
                        + "pelo id passa a alcancar a loja inteira: " + suspeitas);
    }

    @Test
    void oTesteEstaREALMENTEolhandoAsConsultas() throws IOException {
        // Sem isto, um erro no caminho ou no regex faria a lista vir vazia e os dois testes acima
        // passariam medindo nada -- a pior forma de falhar.
        List<Escrita> todas = escritas();

        assertTrue(todas.size() >= 14,
                "esperava achar pelo menos 14 consultas de escrita, achei " + todas.size()
                        + ". O caminho ou o regex quebrou, e os outros testes desta classe estao "
                        + "medindo nada.");
        assertTrue(todas.stream().anyMatch(e -> e.metodo().equals("apagarDaLoja")),
                "nao achei a exclusao de pedido, que e a consulta que motivou este teste");
        assertTrue(todas.stream().anyMatch(e -> e.metodo().equals("baixarEstoqueSeTiver")));
    }

    // ------------------------------------------------------------------ a leitura do fonte

    /**
     * Cada {@code @Modifying} do projeto, com o texto da sua {@code @Query} e o nome do metodo.
     *
     * <p>Casa tanto o {@code @Modifying} quanto o {@code @Query} com ou sem o pacote por extenso, e
     * tanto a consulta em uma linha quanto em bloco de texto, porque o projeto usa as duas formas.</p>
     */
    private List<Escrita> escritas() throws IOException {
        assertTrue(Files.isDirectory(REPOS), "nao achei " + REPOS.toAbsolutePath());
        Pattern bloco = Pattern.compile(
                "@(?:org\\.springframework\\.data\\.jpa\\.repository\\.)?Modifying[^;]*?"
                        + "@(?:org\\.springframework\\.data\\.jpa\\.repository\\.)?Query\\s*\\("
                        + "(?<consulta>.*?)\\)\\s*(?:@\\w[^;]*?)?"
                        + "(?:int|void|long)\\s+(?<metodo>\\w+)\\s*\\(",
                Pattern.DOTALL);
        List<Escrita> achadas = new ArrayList<>();
        try (var arquivos = Files.list(REPOS)) {
            for (Path f : arquivos.filter(x -> x.toString().endsWith(".java")).toList()) {
                String fonte = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = bloco.matcher(fonte);
                while (m.find()) {
                    achadas.add(new Escrita(f.getFileName().toString(),
                            m.group("metodo"), m.group("consulta")));
                }
            }
        }
        return achadas;
    }
}
