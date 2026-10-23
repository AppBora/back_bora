package br.com.bora.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Comparacao de segredo em tempo constante.
 *
 * <h2>Item 8.8 da varredura</h2>
 * <p>{@code String.equals} para em cima do primeiro caractere diferente. Quem chama o endpoint
 * muitas vezes com palpites mede essa diferenca de tempo e descobre o segredo um caractere por vez,
 * sem nunca acertar o todo. Na rede, com um token de 32+ caracteres, o ruido engole o sinal e o
 * ataque e pouco pratico — a propria varredura chama de prioridade baixa. Mas o projeto ja fazia
 * certo em tres lugares ({@code WhatsAppController}, {@code OpenDeliveryClient},
 * {@code RepetirPedidoService}), cada um com a sua copia, e dois ficaram com {@code equals}.</p>
 *
 * <p>Agora ha um lugar so para isto. Os tres que ja estavam certos continuam como estao: mexer em
 * codigo que funciona para ganhar simetria e trocar risco por arrumacao.</p>
 *
 * <h2>O que NAO esta coberto por teste, e nao da para cobrir</h2>
 * <p>A rodada de mutacao do item 8.8 trocou o {@code MessageDigest.isEqual} por {@code equals} e o
 * mutante <b>sobreviveu</b> — como tinha de ser. Os dois devolvem a <b>mesma resposta</b>; o que muda
 * e o <b>tempo</b>, e tempo nao da para afirmar num teste: a medicao depende da maquina, da carga e
 * do JIT, e um teste assim passa e falha sozinho sem ninguem ter mexido em nada.</p>
 *
 * <p>Entao a propriedade aqui esta garantida pela ESCOLHA DA FUNCAO, nao por assercao — e e por isso
 * que existe um lugar so: um lugar e facil de reler e julgar. O que os testes cobrem e o
 * comportamento: igual, diferente, prefixo, nulo. Nao inventei um teste de cronometro para fingir
 * cobertura.</p>
 */
public final class Segredos {

    private Segredos() {}

    /**
     * Os dois textos sao iguais? Nulo nunca e igual a nada — nem a outro nulo.
     *
     * <p>Nulo recusando e deliberado: "sem token" tem que falhar como "token errado". Tratar nulo
     * igual a nulo faria uma integracao <b>sem</b> segredo cadastrado aceitar chamada <b>sem</b>
     * segredo, que e o webhook aberto para qualquer um.</p>
     */
    public static boolean iguais(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
