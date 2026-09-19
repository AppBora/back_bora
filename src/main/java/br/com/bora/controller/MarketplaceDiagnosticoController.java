package br.com.bora.controller;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.service.marketplace.MarketplaceClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Suporte da plataforma: o pedido EXATAMENTE como o marketplace devolve, sem passar pelo normalizador.
 *
 * <p>Existe porque os nomes de campo do iFood (bandeira do cartão, troco, cupons, observação do item,
 * retirada) não dá para confirmar pela documentação — a página fica atrás de verificação anti-robô — e
 * três furos seguidos (PLC, cancellationCode, central 0800) vieram de campo suposto em vez de visto.
 * Só o ADMINISTRADOR_BORA consulta; o conteúdo não vai para o log, só quem consultou o quê.</p>
 */
@RestController
@RequestMapping("/admin-bora/marketplace")
public class MarketplaceDiagnosticoController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MarketplaceDiagnosticoController.class);

    private final IntegracaoCanalRepository integracoes;
    private final List<MarketplaceClient> clients;
    private final AuthContext ctx;

    public MarketplaceDiagnosticoController(IntegracaoCanalRepository integracoes, List<MarketplaceClient> clients,
                                            AuthContext ctx) {
        this.integracoes = integracoes;
        this.clients = clients;
        this.ctx = ctx;
    }

    @GetMapping("/pedido-bruto")
    public Map<String, Object> pedidoBruto(@RequestParam Long lojaId, @RequestParam String canal,
                                           @RequestParam String pedido) {
        ctx.requireAdminBora();
        String c = canal == null ? "" : canal.trim().toUpperCase();
        MarketplaceClient client = clients.stream().filter(x -> x.canal().equalsIgnoreCase(c)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Canal sem integração oficial: " + canal));
        IntegracaoCanal i = integracoes.findByLojaIdAndCanal(lojaId, c)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Esta loja não tem integração com " + c));
        log.warn("AUDITORIA plataforma: usuario {} consultou o pedido bruto {} do {} da loja {}",
                ctx.atual().userId(), pedido, c, lojaId);
        Map<String, Object> bruto = client.detalhePedido(i, pedido.trim());
        if (bruto == null || bruto.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "O marketplace não devolveu o pedido " + pedido);
        }
        return bruto;
    }
}
