package br.com.bora.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Teto de chamadas nos endpoints publicos do cardapio.
 *
 * <p><b>O que isto fecha (09/10/2026):</b> {@code POST /public/loja/{id}/pedido} nao tinha limite de
 * nada. O endpoint e sem autenticacao, os ids de loja sao sequenciais e cada chamada cria pedido,
 * baixa estoque, consome cashback e, no modo PIX, abre uma cobranca no Asaas DA LOJA. Um laço de
 * {@code curl} inundava a cozinha de um concorrente de pedidos falsos, zerava o estoque dele e
 * enchia a conta Asaas dele de cobranca. O {@link FreioDeTentativas} cobre login e cadastro; o
 * cardapio ficou de fora.</p>
 *
 * <p>A consulta de cashback tambem entra: ela devolve saldo a quem souber telefone e primeiro nome, e
 * sem teto dava para varrer numeros atras de nome comum.</p>
 *
 * <h2>Os numeros, e por que eles</h2>
 * <p>O limite e por endereco de origem, em janela fixa. Pedido: 10 por minuto e 30 por hora. Um
 * cliente de verdade faz UM pedido; dez por minuto do mesmo endereco ja e coisa de script. O teto nao
 * e baixo de proposito, porque varios clientes podem sair pelo mesmo endereco (wi-fi de predio,
 * operadora de celular) e barrar venda de verdade e pior que o abuso que isto evita. Quem quiser
 * inundar uma cozinha precisa de milhares, e e isso que para aqui.</p>
 *
 * <p>Os valores sao configuraveis para o dono afrouxar sem subir versao nova:
 * {@code bora.cardapio.limite-pedidos-minuto} e {@code bora.cardapio.limite-pedidos-hora}. Zero
 * desliga o freio.</p>
 *
 * <p>Fica na memoria do processo, igual ao {@link FreioDeTentativas}, e com a mesma ressalva: e uma
 * trava simples, nao contador contabil. Reiniciar a API zera o placar, e com duas instancias cada uma
 * tem o seu. Para valer de verdade em escala, isto vira limite no proxy.</p>
 */
@Component
public class FreioDoCardapio {

    /** Teto de chaves na memoria: acima disso limpamos o que venceu (e, no pior caso, tudo). */
    private static final int TETO_DE_CHAVES = 50_000;

    private final int porMinuto;
    private final int porHora;

    public FreioDoCardapio(
            @Value("${bora.cardapio.limite-pedidos-minuto:10}") int porMinuto,
            @Value("${bora.cardapio.limite-pedidos-hora:30}") int porHora) {
        this.porMinuto = porMinuto;
        this.porHora = porHora;
    }

    private record Contador(int chamadas, Instant ate) {}

    private final Map<String, Contador> contadores = new ConcurrentHashMap<>();

    /**
     * Conta uma tentativa de criar pedido e barra com 429 quem passou do teto.
     *
     * <p>Conta ANTES de criar o pedido: contar depois deixaria passar a enxurrada enquanto o estoque
     * ja estava baixando.</p>
     */
    public void pedido(jakarta.servlet.http.HttpServletRequest http) {
        String origem = FreioDeTentativas.origem(http);
        marcar("ped-min|" + origem, porMinuto, Duration.ofMinutes(1),
                "Muitos pedidos em sequencia. Espere um minuto e tente de novo.");
        marcar("ped-hora|" + origem, porHora, Duration.ofHours(1),
                "Muitos pedidos deste aparelho na ultima hora. Fale com a loja pelo WhatsApp.");
    }

    /** Teto para a consulta de saldo de cashback, que e protegida so por telefone + primeiro nome. */
    public void consultaDeSaldo(jakarta.servlet.http.HttpServletRequest http) {
        marcar("saldo|" + FreioDeTentativas.origem(http), 20, Duration.ofMinutes(1),
                "Muitas consultas em sequencia. Espere um minuto.");
    }

    /** Teto para a validacao de codigo de desconto, para nao servir de adivinhador de cupom. */
    public void codigoDeDesconto(jakarta.servlet.http.HttpServletRequest http) {
        marcar("cupom|" + FreioDeTentativas.origem(http), 30, Duration.ofMinutes(1),
                "Muitas tentativas de cupom. Espere um minuto.");
    }

    private void marcar(String chave, int limite, Duration janela, String recado) {
        if (limite <= 0) return; // freio desligado por configuracao
        limparSeCrescerDemais();
        Contador atualizado = contadores.compute(chave, (k, c) -> {
            Instant agora = Instant.now();
            if (c == null || agora.isAfter(c.ate())) return new Contador(1, agora.plus(janela));
            return new Contador(c.chamadas() + 1, c.ate());
        });
        if (atualizado.chamadas() > limite) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, recado);
        }
    }

    private void limparSeCrescerDemais() {
        if (contadores.size() < TETO_DE_CHAVES) return;
        Instant agora = Instant.now();
        contadores.values().removeIf(c -> agora.isAfter(c.ate()));
        if (contadores.size() >= TETO_DE_CHAVES) contadores.clear();
    }
}
