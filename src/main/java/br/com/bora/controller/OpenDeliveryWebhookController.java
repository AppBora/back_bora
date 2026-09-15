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
    private final OpenDeliveryClient client;
    private final MarketplacePoller poller;
    private final ObjectMapper json;

    public OpenDeliveryWebhookController(IntegracaoCanalRepository integracoes, OpenDeliveryClient client,
                                         MarketplacePoller poller, ObjectMapper json) {
        this.integracoes = integracoes;
        this.client = client;
        this.poller = poller;
        this.json = json;
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
}
