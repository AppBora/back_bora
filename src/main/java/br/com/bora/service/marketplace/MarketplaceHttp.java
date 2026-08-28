package br.com.bora.service.marketplace;

import br.com.bora.entity.IntegracaoCanal;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Infraestrutura compartilhada pelos clientes de marketplace.
 *
 * <p>Existe por dois motivos, ambos aprendidos com o desenho anterior:</p>
 *
 * <ul>
 *   <li><b>Timeout obrigatório.</b> Um {@code RestClient} sem timeout espera para sempre. Como o
 *       polling é o batimento cardíaco que mantém a loja online no iFood, uma única loja com a
 *       conexão pendurada seguraria o ciclo e tiraria <i>todas</i> as outras do ar.</li>
 *   <li><b>Trava por integração.</b> A renovação do token é uma operação de leitura-decisão-escrita.
 *       Com o polling paralelo, duas threads poderiam renovar ao mesmo tempo e gravar refresh tokens
 *       diferentes — o marketplace invalida o anterior a cada rotação, e a loja precisaria
 *       reautorizar do zero.</li>
 * </ul>
 */
@Component
public class MarketplaceHttp {

    private static final Duration CONECTAR = Duration.ofSeconds(5);
    private static final Duration LER = Duration.ofSeconds(12);

    private final ConcurrentHashMap<Long, Object> travas = new ConcurrentHashMap<>();

    /** Cliente HTTP com timeout — nunca construa um {@code RestClient} sem passar por aqui. */
    public RestClient client(String baseUrl, String bearer) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout((int) CONECTAR.toMillis());
        fabrica.setReadTimeout((int) LER.toMillis());

        RestClient.Builder b = RestClient.builder().baseUrl(baseUrl).requestFactory(fabrica);
        if (bearer != null && !bearer.isBlank()) b.defaultHeader("Authorization", "Bearer " + bearer);
        return b.build();
    }

    public RestClient client(String baseUrl) {
        return client(baseUrl, null);
    }

    /**
     * Trava exclusiva desta integração. Serializa a renovação de token entre o ciclo de polling e
     * qualquer requisição do painel que atinja a mesma loja.
     */
    public Object trava(IntegracaoCanal i) {
        Long chave = i == null || i.id == null ? -1L : i.id;
        return travas.computeIfAbsent(chave, k -> new Object());
    }
}
