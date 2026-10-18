package br.com.bora.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O {@code application.yml} não pode ter a mesma chave duas vezes.
 *
 * <p><b>Por que isto existe:</b> em 18/10/2026 eu acrescentei {@code bora.suporte.whatsapp} criando
 * um SEGUNDO bloco {@code bora:} no fim do arquivo. Em YAML isso não soma — o último ganha. O
 * primeiro bloco, que carrega {@code bora.seguranca.segredo-colunas}, sumiria: a cifra em repouso
 * das credenciais de Asaas e marketplace voltaria a ficar desligada, <b>sem erro e sem aviso</b>, e o
 * jeito de descobrir seria um dump com as chaves dos lojistas em texto puro.</p>
 *
 * <p>Nada no build reclama disso: o Spring sobe feliz com a configuração pela metade. Então o teste
 * lê o arquivo e confere que cada ramo que o código usa continua de pé.</p>
 */
class ApplicationYmlNaoTemChaveRepetidaTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> ler() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull(in, "application.yml não está no classpath");
            return new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("application.yml não abriu: " + e.getMessage(), e);
        }
    }

    /** Caminha por "a.b.c" e devolve o que achou, ou null. */
    @SuppressWarnings("unchecked")
    private Object em(Map<String, Object> raiz, String caminho) {
        Object atual = raiz;
        for (String parte : caminho.split("\\.")) {
            if (!(atual instanceof Map)) return null;
            atual = ((Map<String, Object>) atual).get(parte);
        }
        return atual;
    }

    @Test
    void naoHaChaveDePRIMEIROnivelRepetida() {
        // O SnakeYAML resolve a duplicata em silencio, entao olhar o mapa pronto nao acusa nada:
        // e preciso contar no texto. Linha que comeca na coluna zero e termina em ":" e raiz.
        List<String> raizes = new ArrayList<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull(in);
            for (String linha : new String(in.readAllBytes()).split("\r?\n")) {
                if (linha.isBlank() || linha.startsWith("#") || linha.startsWith(" ")) continue;
                if (linha.startsWith("---")) continue;
                int i = linha.indexOf(':');
                if (i > 0) raizes.add(linha.substring(0, i).trim());
            }
        } catch (Exception e) {
            fail("não consegui ler o application.yml: " + e.getMessage());
        }
        List<String> repetidas = raizes.stream().distinct()
                .filter(k -> raizes.stream().filter(k::equals).count() > 1).toList();
        assertTrue(repetidas.isEmpty(),
                "chave repetida no primeiro nível — o segundo bloco APAGA o primeiro: " + repetidas);
    }

    @Test
    void osRamosQueOCodigoLEcontinuamDePe() {
        Map<String, Object> y = ler();
        // Cada um destes tem um @Value apontando para ele. Perder um nao quebra o build nem o start:
        // vira comportamento desligado, que e o pior jeito de descobrir.
        for (String caminho : List.of(
                "bora.seguranca.segredo-colunas",   // cifra das credenciais de terceiros
                "bora.suporte.whatsapp",            // aviso de "esqueci minha senha"
                "asaas.base-url",
                "asaas.api-key",
                "asaas.url-publica",
                "server.port")) {
            assertNotNull(em(y, caminho), "sumiu do application.yml: " + caminho);
        }
    }
}
