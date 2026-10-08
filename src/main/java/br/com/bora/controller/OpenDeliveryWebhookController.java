package br.com.bora.controller;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.service.marketplace.MarketplacePoller;
import br.com.bora.service.marketplace.OpenDeliveryClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Webhook do Open Delivery: a 99Food chama {@code POST /v1/newEvent} no endereco de callback que
 * cadastramos no aplicativo dela. Aqui fica em {@code /public/opendelivery/v1/newEvent}.
 *
 * <p>Nao da para reaproveitar o {@code /webhooks/{canal}}: aquele exige {@code ?loja=&token=} na
 * URL, que a 99 nao manda — ela identifica a loja pelo header {@code X-App-MerchantId} e prova
 * que e ela pelo {@code X-App-Signature}, um HMAC-SHA256 do corpo com o client secret. A checklist
 * de homologacao cobra "response aos nossos webhooks".</p>
 *
 * <p>Respostas conforme a especificacao: 204 processado, 403 assinatura invalida, 404 loja
 * desconhecida. Falha ao processar devolve 503 — a 99 reenvia, em vez de o pedido se perder.</p>
 */
@Slf4j
@RestController
@RequestMapping("/public/opendelivery")
public class OpenDeliveryWebhookController {

    private final IntegracaoCanalRepository integracoes;
    private final br.com.bora.repository.LojaRepository lojas;
    private final br.com.bora.security.RegraDeAcesso regra;
    private final OpenDeliveryClient client;
    private final MarketplacePoller poller;
    private final ObjectMapper json;
    /** Para o aviso do entregador fechar o pedido sem contar a novidade de volta para a 99. */
    private final br.com.bora.service.PedidoService pedidos;

    public OpenDeliveryWebhookController(IntegracaoCanalRepository integracoes, OpenDeliveryClient client,
                                         br.com.bora.repository.LojaRepository lojas,
                                         br.com.bora.security.RegraDeAcesso regra,
                                         MarketplacePoller poller, ObjectMapper json,
                                         br.com.bora.service.PedidoService pedidos) {
        this.integracoes = integracoes;
        this.lojas = lojas;
        this.regra = regra;
        this.client = client;
        this.poller = poller;
        this.json = json;
        this.pedidos = pedidos;
    }

    @PostMapping("/v1/newEvent")
    public ResponseEntity<Void> novoEvento(@RequestHeader(value = "X-App-MerchantId", required = false) String merchantId,
                                           @RequestHeader(value = "X-App-Signature", required = false) String assinatura,
                                           @RequestBody(required = false) String corpo) {
        if (merchantId == null || merchantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Header X-App-MerchantId ausente");
        }
        IntegracaoCanal i = integracoes.findFirstByCanalAndMerchantId(client.canal(), merchantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Nenhuma loja conectada com o App Shop ID " + merchantId));

        if (!client.assinaturaValida(i, corpo, assinatura)) {
            log.warn("Open Delivery webhook: assinatura invalida para a loja {} (app shop {})", i.lojaId, merchantId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Assinatura invalida");
        }

        // A assinatura confere, mas a LOJA pode estar suspensa, arquivada ou com o prazo vencido.
        // Aceitar o pedido aqui significa confirmar para o cliente final uma venda que ninguem vai
        // preparar: o painel da loja esta fechado. Respondemos 503 para a 99 reenviar ou expirar, em
        // vez de 2xx, que seria dizer "aceito" por uma loja que nao pode atender.
        if (!regra.podeOperar(lojas.findById(i.lojaId).orElse(null))) {
            log.warn("Open Delivery webhook: loja {} nao pode operar agora; evento recusado", i.lojaId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Loja indisponivel no momento");
        }

        Map<String, Object> evento;
        try {
            evento = json.readValue(corpo, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Evento ilegivel");
        }

        try {
            poller.tratarEvento(client, i, evento);
        } catch (Exception e) {
            log.warn("Open Delivery webhook: falha ao processar o evento da loja {}: {}", i.lojaId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Falha ao processar; reenviar");
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * A 99 avisa o que o entregador dela fez: pegou o pedido, entregou, cancelou.
     *
     * <p>Em pedido que a 99 entrega, quem sabe onde o motoboy esta e ela. Sem este aviso o pedido
     * parava em PRONTO no painel para sempre: o lojista nunca via "saiu" nem "entregue", e o quadro
     * do dia ficava cheio de pedido que ja chegou na casa do cliente.</p>
     *
     * <p>A especificacao manda responder <b>200 com corpo vazio</b>; qualquer outra coisa faz a 99
     * reenviar. Entao: evento desconhecido tambem responde 200 — reenviar um aviso que nao sabemos
     * tratar nao melhora nada e so enche a fila deles.</p>
     */
    @PostMapping("/v1/trackingEvent")
    public ResponseEntity<Void> eventoDeRastreio(
            @RequestHeader(value = "X-App-MerchantId", required = false) String merchantId,
            @RequestHeader(value = "X-App-Signature", required = false) String assinatura,
            @RequestBody(required = false) String corpo) {
        if (merchantId == null || merchantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Header X-App-MerchantId ausente");
        }
        IntegracaoCanal i = integracoes.findFirstByCanalAndMerchantId(client.canal(), merchantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Nenhuma loja conectada com o App Shop ID " + merchantId));
        if (!client.assinaturaValida(i, corpo, assinatura)) {
            log.warn("Open Delivery rastreio: assinatura invalida para a loja {} (app shop {})", i.lojaId, merchantId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Assinatura invalida");
        }

        Map<String, Object> evento;
        try {
            evento = json.readValue(corpo, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Evento ilegivel");
        }

        String pedido = texto(evento.get("orderId"));
        Object ev = evento.get("event");
        String tipo = ev instanceof Map<?, ?> m ? texto(m.get("type")) : null;
        if (pedido == null || tipo == null) {
            log.warn("Open Delivery rastreio: evento sem orderId ou type na loja {}", i.lojaId);
            return ResponseEntity.ok().build();
        }

        // Ao contrario do pedido novo, aqui NAO recusamos loja suspensa: este aviso so fecha um pedido
        // que ja entrou antes. Travar isso deixaria o pedido pendurado para sempre no painel dela.
        switch (tipo.toUpperCase()) {
            case "PICKED_UP" -> pedidos.avancarPorMarketplace(i.lojaId, client.canal(), pedido,
                    br.com.bora.entity.StatusPedido.SAIU_PARA_ENTREGA);
            case "DELIVERED" -> pedidos.avancarPorMarketplace(i.lojaId, client.canal(), pedido,
                    br.com.bora.entity.StatusPedido.ENTREGUE);
            case "CANCELLED" -> pedidos.cancelarPorMarketplace(i.lojaId, client.canal(), pedido,
                    primeiroNaoVazio(ev instanceof Map<?, ?> m2 ? texto(m2.get("message")) : null,
                            "Cancelado pela logistica da 99Food"));
            default -> log.info("Open Delivery rastreio: evento {} na loja {} nao muda o pedido {}",
                    tipo, i.lojaId, pedido);
        }
        return ResponseEntity.ok().build();
    }

    private static String texto(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static String primeiroNaoVazio(String a, String b) {
        return a == null || a.isBlank() ? b : a;
    }
}
