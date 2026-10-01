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
     * <p>O cabeçalho é falsificável por quem fala direto com a aplicação — aqui ninguém fala, porque a
     * porta 8080 não é publicada no host. Se um dia for, este valor deixa de ser confiável.</p>
     */
    public static String origem(jakarta.servlet.http.HttpServletRequest http) {
        if (http == null) return "";
        String encaminhado = http.getHeader("X-Forwarded-For");
        if (encaminhado != null && !encaminhado.isBlank()) {
            String primeiro = encaminhado.split(",")[0].trim();   // o cliente original é o primeiro da lista
            if (!primeiro.isEmpty()) return primeiro;
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
