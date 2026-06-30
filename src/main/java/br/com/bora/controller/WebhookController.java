package br.com.bora.controller;

import br.com.bora.dto.InboundOrder;
import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Pedido;
import br.com.bora.service.IntegracaoService;
import br.com.bora.service.MarketplaceNormalizer;
import br.com.bora.service.PedidoService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Entrada de pedidos dos marketplaces (público, autenticado por token de webhook).
 * URL por loja/canal: POST /webhooks/{canal}?loja={lojaId}&token={webhookToken}
 */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private final IntegracaoService integracoes;
    private final MarketplaceNormalizer normalizer;
    private final PedidoService pedidos;

    public WebhookController(IntegracaoService integracoes, MarketplaceNormalizer normalizer, PedidoService pedidos) {
        this.integracoes = integracoes;
        this.normalizer = normalizer;
        this.pedidos = pedidos;
    }

    @PostMapping("/{canal}")
    public Map<String, Object> receber(@PathVariable String canal,
                                       @RequestParam Long loja,
                                       @RequestParam String token,
                                       @RequestBody Map<String, Object> payload) {
        IntegracaoCanal cfg = integracoes.autenticarWebhook(loja, canal, token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook inválido ou não configurado"));

        InboundOrder pedidoNormalizado = normalizer.normalizar(cfg.canal, payload);
        Pedido criado = pedidos.criarExterno(loja, cfg.canal, IntegracaoService.label(cfg.canal), pedidoNormalizado);
        integracoes.registrarRecebido(cfg);

        return Map.of("received", true, "pedidoId", criado.id, "canal", cfg.canal);
    }
}
