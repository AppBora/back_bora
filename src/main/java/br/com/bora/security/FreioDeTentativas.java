package br.com.bora.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Freio para quem fica tentando senha atrás de senha.
 *
 * <p>Sem isto, o login e o cadastro público aceitavam tentativas infinitas: dava para varrer senhas de
 * um lojista a milhares por minuto. O cadastro era pior, porque respondia diferente para senha certa e
 * errada e virava um adivinhador de senha disfarçado.</p>
 *
 * <p>Fica na memória do processo de propósito: é uma trava simples, não um contador contábil. Reiniciar
 * a API zera o placar, e isso é aceitável — quem está atacando perde a janela do mesmo jeito. Se um dia
 * houver mais de uma instância, isto precisa virar contador no banco ou no proxy.</p>
 */
@Component
public class FreioDeTentativas {

    /** Tentativas erradas que ainda passam. A sexta dentro da janela já é barrada. */
    private static final int LIMITE = 5;
    private static final Duration JANELA = Duration.ofMinutes(15);
    /** Teto de chaves na memória: acima disso, limpamos o que já venceu (e, no pior caso, tudo). */
    private static final int TETO_DE_CHAVES = 20_000;

    private record Placar(int erros, Instant ate) {}

    private final Map<String, Placar> placares = new ConcurrentHashMap<>();

    /** Chama antes de conferir a senha. Barra com 429 quem já estourou o limite. */
    public void conferir(String chave) {
        Placar p = placares.get(chave);
        if (p == null) return;
        if (Instant.now().isAfter(p.ate())) { placares.remove(chave); return; }
        if (p.erros() >= LIMITE) {
            long faltam = Math.max(1, Duration.between(Instant.now(), p.ate()).toMinutes() + 1);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Muitas tentativas. Tente de novo em " + faltam + " minuto(s).");
        }
    }

    /** Chama quando a senha não confere. */
    public void errou(String chave) {
        limparSeCrescerDemais();
        placares.compute(chave, (k, p) -> {
            Instant agora = Instant.now();
            if (p == null || agora.isAfter(p.ate())) return new Placar(1, agora.plus(JANELA));
            return new Placar(p.erros() + 1, p.ate());
        });
    }

    /** Chama quando entrou: o placar da pessoa zera. */
    public void acertou(String chave) {
        placares.remove(chave);
    }

    /** Uma chave por e-mail e por endereço de origem — barrar só por e-mail deixaria varrer e-mails. */
    public static String chave(String parte1, String parte2) {
        return (parte1 == null ? "" : parte1.trim().toLowerCase()) + "|" + (parte2 == null ? "" : parte2);
    }

    /**
     * De onde veio a chamada.
     *
     * <p>Quem fala com o Tomcat é o Caddy, então {@code getRemoteAddr()} devolve o endereço do próprio
     * Caddy para todo mundo. Sem olhar o {@code X-Forwarded-For}, o contador vira um balde só: um
     * atacante martelando o e-mail de um lojista trancaria esse lojista de propósito, e cinco pessoas
     * diferentes errando a senha trancariam umas às outras.</p>
     *
     * <h2>Por que o ÚLTIMO da lista, e não o primeiro</h2>
     * <p><b>Medido em 19/10/2026, com o Caddy v2.11.4 — a mesma versão que roda em produção:</b> sem
     * {@code trusted_proxies} configurado, o Caddy <b>descarta</b> o {@code X-Forwarded-For} que o
     * cliente mandou e escreve só o IP que ele mesmo viu no socket. Mandando
     * {@code X-Forwarded-For: 1.2.3.4} e {@code "203.0.113.99, 9.9.9.9"}, a aplicação recebeu nos
     * dois casos apenas o IP real. Ou seja: hoje a lista tem <b>um item só</b>, e ele é confiável.</p>
     *
     * <p>Ler o último em vez do primeiro, então, <b>não conserta nada que esteja quebrado hoje</b> —
     * com um item só, os dois são o mesmo. É defesa para o dia em que a topologia mudar: um
     * {@code trusted_proxies} no Caddyfile, um CDN na frente, ou a troca do proxy fazem a lista
     * passar a ter o valor do cliente. Nesse dia, o primeiro item vira texto escolhido pelo atacante,
     * e uma chave nova a cada chamada é um freio que não freia.</p>
     *
     * <p>O último item é sempre o que o proxy mais próximo escreveu, e esse é o único que a gente
     * tem motivo para acreditar. Se um dia entrar outro proxy <b>antes</b> do Caddy, este método
     * precisa pular tantos itens quantos forem os proxies confiáveis — não dá para adivinhar isso
     * aqui, e aí o lugar certo de resolver passa a ser o {@code trusted_proxies}.</p>
     */
    public static String origem(jakarta.servlet.http.HttpServletRequest http) {
        if (http == null) return "";
        String encaminhado = http.getHeader("X-Forwarded-For");
        if (encaminhado != null && !encaminhado.isBlank()) {
            String[] saltos = encaminhado.split(",");
            // De trás para frente: o que o nosso proxy escreveu está no fim. Lista com item vazio
            // ("1.2.3.4, , ") não pode cair num valor em branco, que viraria balde único.
            for (int i = saltos.length - 1; i >= 0; i--) {
                String salto = saltos[i].trim();
                if (!salto.isEmpty()) return salto;
            }
        }
        return http.getRemoteAddr();
    }

    private void limparSeCrescerDemais() {
        if (placares.size() < TETO_DE_CHAVES) return;
        Instant agora = Instant.now();
        placares.values().removeIf(p -> agora.isAfter(p.ate()));
        if (placares.size() >= TETO_DE_CHAVES) placares.clear();
    }
}
