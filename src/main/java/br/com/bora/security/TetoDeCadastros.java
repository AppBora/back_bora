package br.com.bora.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quantas lojas uma mesma origem pode criar por hora.
 *
 * <h2>O que isto fecha (pente fino de 29/10/2026)</h2>
 * <p>{@code POST /public/signup} nao tinha teto nenhum por origem. A unica trava era o
 * {@link FreioDeTentativas}, e ele conta <b>tentativa ERRADA</b>: no caminho de e-mail que ainda nao
 * existe -- que e exatamente o caminho que CRIA loja nova -- o {@code errou} nunca e chamado. E a
 * chave do freio inclui o e-mail, entao mudar o e-mail a cada chamada ja gera uma chave nova de
 * qualquer jeito.</p>
 *
 * <p>Ou seja: qualquer pessoa na internet criava lojas em sequencia, sem limite. Cada loja nascida e
 * um inquilino com dados semeados (formas de pagamento, horarios, motivos de cancelamento), espaco
 * no banco e uma linha na lista do administrador da plataforma.</p>
 *
 * <h2>O numero, e por que ele</h2>
 * <p><b>5 por hora</b> por origem. Dono de rede criando as lojas dele numa sentada e o caso legitimo
 * mais pesado que existe, e cinco cobre isso com folga; a sexta so espera. Nao e um numero sagrado:
 * se o dono quiser outro, e esta constante. O importante e que exista um.</p>
 *
 * <h2>O que isto NAO e</h2>
 * <p>Nao e defesa contra quem tem muitos enderecos. E o degrau que falta para o abuso deixar de ser
 * gratuito: hoje uma pessoa so, de um lugar so, criava quantas quisesse. Defesa de verdade contra
 * rede distribuida mora no proxy, e e outra conversa.</p>
 *
 * <p>Mesma limitacao do {@code FreioDeTentativas}: o placar vive na MEMORIA desta instancia.
 * Reiniciar a API zera. Com mais de uma instancia, isto tem de virar contador no banco ou no
 * proxy.</p>
 */
@Component
public class TetoDeCadastros {

    /** Lojas criadas por origem dentro da janela. A sexta ja e barrada. */
    private static final int LIMITE = 5;
    private static final Duration JANELA = Duration.ofHours(1);
    /** Teto de chaves na memoria: acima disso limpamos o que venceu (e, no pior caso, tudo). */
    private static final int TETO_DE_CHAVES = 20_000;

    private record Placar(int criadas, Instant ate) {}

    private final Map<String, Placar> placares = new ConcurrentHashMap<>();

    /**
     * Chama ANTES de criar a loja. Barra com 429 quem ja estourou o limite.
     *
     * <p>Antes, e nao depois: a criacao e transacional e semeia dados: deixar acontecer para so
     * depois reclamar gastaria o trabalho inteiro.</p>
     */
    public void conferir(String origem) {
        Placar p = placares.get(chave(origem));
        if (p == null) return;
        if (Instant.now().isAfter(p.ate())) { placares.remove(chave(origem)); return; }
        if (p.criadas() >= LIMITE) {
            long faltam = Math.max(1, Duration.between(Instant.now(), p.ate()).toMinutes() + 1);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Muitas lojas criadas deste mesmo lugar agora. Tente de novo em " + faltam
                            + " minuto(s), ou fale com o suporte se precisa cadastrar varias.");
        }
    }

    /** Chama quando a loja NASCEU de fato. Cadastro recusado nao conta. */
    public void criou(String origem) {
        limparSeCrescerDemais();
        placares.compute(chave(origem), (k, p) -> {
            Instant agora = Instant.now();
            if (p == null || agora.isAfter(p.ate())) return new Placar(1, agora.plus(JANELA));
            return new Placar(p.criadas() + 1, p.ate());
        });
    }

    private static String chave(String origem) {
        return origem == null ? "" : origem.trim();
    }

    private void limparSeCrescerDemais() {
        if (placares.size() < TETO_DE_CHAVES) return;
        Instant agora = Instant.now();
        placares.values().removeIf(p -> agora.isAfter(p.ate()));
        if (placares.size() >= TETO_DE_CHAVES) placares.clear();
    }
}
