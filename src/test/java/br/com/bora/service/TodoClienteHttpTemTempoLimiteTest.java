package br.com.bora.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nenhum cliente HTTP pode nascer sem tempo limite.
 *
 * <p>Um {@code RestClient} sem timeout espera para sempre. No caminho do PIX do cardápio público a
 * chamada acontece dentro da transação do pedido, segurando uma conexão do banco: com o pool em 10,
 * bastava o Asaas ficar lento para dez pessoas pagando travarem o painel de todas as lojas. Sete
 * pontos do código estavam assim, enquanto marketplace e subconta já faziam certo.</p>
 *
 * <p>Este teste lê o próprio código-fonte porque a regra é sobre como o objeto é construído — não há
 * comportamento para exercitar. É a forma de impedir que o descuido volte no próximo serviço novo.</p>
 */
class TodoClienteHttpTemTempoLimiteTest {

    /** Quem pode construir RestClient na mão: são os lugares que definem o timeout. */
    private static final List<String> FABRICAS = List.of(
            "TempoLimite.java", "MarketplaceHttp.java", "AsaasSubcontaService.java");

    @Test
    void nenhumRestClientSemTempoLimite() throws IOException {
        Path raiz = Paths.get("src", "main", "java");
        List<String> suspeitos = new ArrayList<>();

        try (Stream<Path> arquivos = Files.walk(raiz)) {
            for (Path f : arquivos.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (FABRICAS.contains(f.getFileName().toString())) continue;
                String fonte = Files.readString(f);
                String[] linhas = fonte.split("\n");
                for (int i = 0; i < linhas.length; i++) {
                    String l = linhas[i];
                    boolean constroi = l.contains("RestClient.create()") || l.contains("RestClient.builder()");
                    if (constroi && !l.contains("requestFactory")) {
                        suspeitos.add(f.getFileName() + ":" + (i + 1) + "  ->  " + l.trim());
                    }
                }
            }
        }

        assertTrue(suspeitos.isEmpty(),
                "Cliente HTTP sem tempo limite (use br.com.bora.service.TempoLimite):\n  "
                        + String.join("\n  ", suspeitos));
    }

    @Test
    void aFabricaRealmenteDefineOsDoisTempos() throws IOException {
        String fonte = Files.readString(Paths.get("src/main/java/br/com/bora/service/TempoLimite.java"));

        assertTrue(fonte.contains("setConnectTimeout"), "sem tempo de conexao nao adianta");
        assertTrue(fonte.contains("setReadTimeout"), "o perigoso e o de leitura: e o que trava a thread");
    }
}
