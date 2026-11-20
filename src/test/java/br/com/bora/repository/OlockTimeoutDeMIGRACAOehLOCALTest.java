package br.com.bora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code set lock_timeout} em migração tem de ser {@code SET LOCAL} — nas novas.
 *
 * <h2>O defeito, medido por mim em 19/11/2026</h2>
 * <p><b>Sete</b> migrações usam {@code set lock_timeout} sem {@code LOCAL}. Sem {@code LOCAL} o
 * ajuste vale para a <b>sessão</b>, não para a transação: a conexão volta ao pool do Flyway
 * carregando o timeout, e o que vier depois nela herda um limite que ninguém pediu.</p>
 *
 * <p>O efeito prático hoje é pequeno — só alcança banco novo, porque num banco já migrado essas
 * sete não rodam de novo. <b>Mas é padrão, e padrão se copia.</b> A próxima migração que travar uma
 * tabela grande vai ser escrita olhando para uma dessas sete.</p>
 *
 * <h2>Por que as sete ficam como estão</h2>
 * <p><b>Migração já aplicada não se edita.</b> O Flyway guarda o checksum de cada arquivo; mudar
 * uma linha de qualquer uma delas faz o próximo {@code validate} recusar e <b>derruba o deploy</b>.
 * Corrigir exigiria {@code flyway repair} em produção, para um ganho que não existe em banco já
 * migrado. Então elas são <b>anistiadas por nome</b> aqui, e a regra vale da V77 em diante.</p>
 *
 * <p>A lista de anistia é explícita de propósito: ela documenta exatamente quais são, e não cresce
 * sozinha — migração nova que repita o padrão encontra este teste vermelho.</p>
 */
class OlockTimeoutDeMIGRACAOehLOCALTest {

    private static final Path PASTA = Path.of("src/main/resources/db/migration");

    /**
     * As sete que já foram aplicadas em produção e não podem ser editadas.
     *
     * <p>Medidas em 19/11/2026. Esta lista <b>não deve crescer</b>.</p>
     */
    private static final Set<String> ANISTIADAS = Set.of(
            "V48__pedido_guarda_entrega_e_aceite.sql",
            "V49__colunas_de_segredo_cabem_cifradas.sql",
            "V51__produto_guarda_o_codigo_do_marketplace.sql",
            "V52__assinatura_guarda_a_anterior.sql",
            "V53__endereco_do_pedido_cabe_o_que_o_cliente_cabe.sql",
            "V54__indices_do_laco_do_painel.sql",
            "V56__indices_que_servem.sql");

    private static List<Path> migracoes() throws IOException {
        assertTrue(Files.isDirectory(PASTA), "nao achei a pasta de migracoes: " + PASTA.toAbsolutePath());
        try (Stream<Path> s = Files.list(PASTA)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        }
    }

    /** Usa `set lock_timeout` sem o `LOCAL`? */
    private static boolean temLockTimeoutSemLocal(Path arq) throws IOException {
        String sql = Files.readString(arq, StandardCharsets.UTF_8).toLowerCase();
        int i = 0;
        while ((i = sql.indexOf("lock_timeout", i)) >= 0) {
            String antes = sql.substring(Math.max(0, i - 40), i);
            // interessa so o comando de ajuste, nao mencao em comentario
            if (antes.contains("set ") && !antes.contains("set local ")) return true;
            i += "lock_timeout".length();
        }
        return false;
    }

    // ----------------------------------------------------------------- a premissa

    /**
     * A PREMISSA: o leitor acha as sete que existem. Sem isto, um teste verde poderia significar
     * "nao achei arquivo nenhum" em vez de "nenhuma migracao nova repete o padrao".
     */
    @Test
    void aPREMISSAasSETEqueEXISTEMsaoACHADAS() throws Exception {
        List<String> achadas = new ArrayList<>();
        for (Path p : migracoes()) {
            if (temLockTimeoutSemLocal(p)) achadas.add(p.getFileName().toString());
        }
        assertEquals(ANISTIADAS.size(), achadas.size(),
                "eu esperava achar exatamente as " + ANISTIADAS.size() + " anistiadas e achei "
                        + achadas.size() + ": " + achadas + ". Se uma das anistiadas foi EDITADA, "
                        + "cuidado: migracao aplicada tem checksum no Flyway e editar derruba o "
                        + "deploy. Se e uma migracao NOVA, o outro teste deste arquivo explica.");
        assertEquals(ANISTIADAS, Set.copyOf(achadas),
                "a lista de anistiadas nao bate com o que esta na pasta");
    }

    // ----------------------------------------------------------------- o que importa

    /** Migração NOVA não pode repetir o padrão. */
    @Test
    void migracaoNOVAusaSETLOCALlockTimeout() throws Exception {
        List<String> novasComOpadrao = new ArrayList<>();
        for (Path p : migracoes()) {
            String nome = p.getFileName().toString();
            if (ANISTIADAS.contains(nome)) continue;
            if (temLockTimeoutSemLocal(p)) novasComOpadrao.add(nome);
        }

        assertTrue(novasComOpadrao.isEmpty(),
                "migracao(oes) usando `set lock_timeout` SEM `LOCAL`: " + novasComOpadrao + "\n"
                        + "Sem o LOCAL o ajuste vale para a SESSAO, e a conexao volta ao pool do "
                        + "Flyway carregando o timeout -- o que vier depois nela herda um limite que "
                        + "ninguem pediu. Escreva `SET LOCAL lock_timeout = '...'`, que morre com a "
                        + "transacao.\n"
                        + "As sete anistiadas deste arquivo ja foram aplicadas em producao e NAO "
                        + "devem ser editadas: o Flyway guarda o checksum delas.");
    }
}
