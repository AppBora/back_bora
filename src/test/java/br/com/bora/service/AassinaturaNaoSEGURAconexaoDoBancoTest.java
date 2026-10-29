package br.com.bora.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A assinatura fala com o Asaas <b>sem</b> uma conexao do banco na mao.
 *
 * <h2>O defeito (pente fino de 29/10/2026)</h2>
 * <p>{@code assinar()} era {@code @Transactional} e, dentro da transacao, fazia ate TRES chamadas
 * HTTP em sequencia ao Asaas: {@code criarCliente}, {@code criarAssinatura} e
 * {@code cancelarAssinatura}. O {@code sincronizarValorComMotivo} e o
 * {@code cancelarPorAdministracao} tinham o mesmo desenho.</p>
 *
 * <h2>Por que isto derruba a loja toda, e nao so a assinatura</h2>
 * <p>O pool de conexoes e COMPARTILHADO. O tempo limite configurado para o Asaas e de dezenas de
 * segundos; com varios lojistas assinando ao mesmo tempo e o Asaas lento, cada clique prende uma
 * conexao do pool. Quando o pool acaba, param tambem o painel, o KDS, o cardapio publico e os
 * marketplaces -- por causa de um servico de fora. Uma assinatura falhar e aceitavel; a API inteira
 * parar nao e.</p>
 *
 * <p>Nao se perde atomicidade: nos tres metodos o unico momento que grava e o {@code repo.save} do
 * fim, e o {@code save} do Spring Data abre a transacao propria dele.</p>
 *
 * <h2>O que este teste mede, e o que NAO mede</h2>
 * <p>Mede a <b>forma</b>: a anotacao ausente nos tres metodos, e a chamada ao Asaas acontecendo antes
 * do {@code save}. Nao mede o pool sob carga -- para isso seria preciso um Asaas lento de verdade e
 * um pool pequeno. Vale assim porque o defeito era de forma, e e o mesmo criterio do
 * {@code OasaasNaoSEGURAconexaoDoBancoTest}, que guarda o checkout do cardapio.</p>
 */
class AassinaturaNaoSEGURAconexaoDoBancoTest {

    private static final Path FONTE =
            Path.of("src/main/java/br/com/bora/service/AssinaturaService.java");

    /** Os metodos que falam com o Asaas e gravam depois. */
    private static final List<String> METODOS = List.of(
            "public Assinatura assinar(String cpfCnpj) {",
            "public String sincronizarValorComMotivo(Long lojaId) {",
            "public String cancelarPorAdministracao(Long lojaId) {");

    private static String fonte() {
        return assertDoesNotThrow(() -> Files.readString(FONTE, StandardCharsets.UTF_8));
    }

    /**
     * O codigo sem comentario.
     *
     * <p>Sem isto o teste leria a propria nota que explica a ausencia da anotacao -- e foi exatamente
     * um comentario que afirmava o contrario do codigo que escondeu o defeito irmao no checkout.</p>
     */
    private static List<String> linhasSemComentario(String texto) {
        List<String> fora = new ArrayList<>();
        boolean bloco = false;
        for (String linha : texto.split("\r?\n", -1)) {
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
        return fora;
    }

    @Test
    void nenhumDOStresMETODOSabreTRANSACAO() {
        List<String> linhas = linhasSemComentario(fonte());

        for (String assinatura : METODOS) {
            int i = indiceDa(linhas, assinatura);
            assertTrue(i > 0, "nao achei no fonte: " + assinatura
                    + " -- se o metodo foi renomeado, atualize ESTE teste em vez de apaga-lo");
            // A anotacao, quando existe, e a linha imediatamente anterior (ou a de antes dela).
            String antes = linhas.get(i - 1).trim();
            String antesDeAntes = i >= 2 ? linhas.get(i - 2).trim() : "";
            assertFalse(antes.startsWith("@Transactional") || antesDeAntes.startsWith("@Transactional"),
                    "o metodo abaixo voltou a ser @Transactional, e com ele a chamada HTTP ao Asaas "
                            + "volta a segurar uma conexao do pool:\n   " + assinatura);
        }
    }

    @Test
    void oASAASeCHAMADOantesDOsaveNOassinar() {
        // A ordem e o que torna a ausencia da anotacao suficiente: lê, fala com o Asaas, e grava uma
        // vez no fim. Gravar no meio exigiria a transacao de volta.
        String corpo = corpoDe("public Assinatura assinar(String cpfCnpj) {");

        int criarAssinatura = corpo.indexOf("asaas.criarAssinatura(");
        int save = corpo.indexOf("repo.save(a)");
        assertTrue(criarAssinatura > 0, "nao achei a chamada ao Asaas no assinar");
        assertTrue(save > 0, "nao achei a gravacao no assinar");
        assertTrue(criarAssinatura < save,
                "a gravacao passou a acontecer ANTES da chamada ao Asaas: assim o save precisaria "
                        + "estar na mesma transacao da chamada, e a conexao volta a ficar presa");
    }

    @Test
    void oSAVEehAUNICAgravacaoDOassinar() {
        // Se aparecer uma segunda gravacao no meio, a atomicidade passa a importar e a transacao tem
        // de voltar -- junto com a reorganizacao da chamada HTTP para fora dela.
        String corpo = corpoDe("public Assinatura assinar(String cpfCnpj) {");

        assertEquals1(contar(corpo, "repo.save("), "repo.save(");
        assertEquals1(contar(corpo, "lojas.save("), "lojas.save(");
        assertEquals0(contar(corpo, "usuarios.save("), "usuarios.save(");
    }

    private static void assertEquals1(int quantas, String o) {
        assertTrue(quantas <= 1, "o assinar passou a ter " + quantas + " chamadas de " + o
                + ": com mais de uma gravacao a transacao volta a ser necessaria, e ai a chamada ao "
                + "Asaas precisa sair de dentro dela");
    }

    private static void assertEquals0(int quantas, String o) {
        assertTrue(quantas == 0, "o assinar passou a gravar por " + o + ": ver a nota acima");
    }

    private static int contar(String texto, String agulha) {
        int n = 0;
        int i = texto.indexOf(agulha);
        while (i >= 0) {
            n++;
            i = texto.indexOf(agulha, i + 1);
        }
        return n;
    }

    private static int indiceDa(List<String> linhas, String assinatura) {
        for (int i = 0; i < linhas.size(); i++) {
            if (linhas.get(i).trim().equals(assinatura)) return i;
        }
        return -1;
    }

    /** Da assinatura ate a chave que fecha o metodo, contando as chaves. */
    private static String corpoDe(String assinatura) {
        String fonte = fonte();
        int ini = fonte.indexOf(assinatura);
        assertTrue(ini > 0, "nao achei no fonte: " + assinatura);
        int nivel = 0;
        for (int i = ini + assinatura.length() - 1; i < fonte.length(); i++) {
            char c = fonte.charAt(i);
            if (c == '{') nivel++;
            else if (c == '}' && --nivel == 0) return fonte.substring(ini, i + 1);
        }
        return fonte.substring(ini);
    }
}
