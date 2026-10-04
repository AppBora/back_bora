package br.com.bora.service;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Fábrica de clientes HTTP com tempo limite.
 *
 * <p>Um {@code RestClient} sem timeout espera para sempre. Isso já era respeitado no código dos
 * marketplaces e na subconta do Asaas, e ignorado em todo o resto — inclusive no caminho mais
 * perigoso que existe aqui: o PIX do cardápio público. Lá a chamada acontece <b>dentro</b> da
 * transação do pedido, segurando uma conexão do banco. Com o pool padrão de 10 conexões, bastava o
 * Asaas ficar lento para dez pessoas pagando ao mesmo tempo travarem o painel de <i>todas</i> as
 * lojas, não só a que estava vendendo. E a rota é pública, sem freio.</p>
 *
 * <p>Timeout não conserta a arquitetura — a chamada externa continua dentro da transação. Mas troca
 * "trava para sempre" por "trava alguns segundos e volta", que é a diferença entre o sistema cair e o
 * sistema engasgar.</p>
 */
public final class TempoLimite {

    /** Serviço de pagamento, fiscal, WhatsApp: tem que responder rápido ou não responder. */
    private static final Duration CONECTAR = Duration.ofSeconds(4);
    private static final Duration LER = Duration.ofSeconds(10);

    /** A IA é lenta por natureza: gerar um cardápio a partir de uma foto não cabe em 10 segundos. */
    private static final Duration LER_IA = Duration.ofSeconds(60);

    private TempoLimite() {}

    public static RestClient.Builder cliente() {
        return RestClient.builder().requestFactory(fabrica(LER));
    }

    public static RestClient.Builder cliente(String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).requestFactory(fabrica(LER));
    }

    /** Para chamadas de IA, que demoram de verdade. */
    public static RestClient.Builder clienteDeIa() {
        return RestClient.builder().requestFactory(fabrica(LER_IA));
    }

    private static SimpleClientHttpRequestFactory fabrica(Duration ler) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) CONECTAR.toMillis());
        f.setReadTimeout((int) ler.toMillis());
        return f;
    }
}
