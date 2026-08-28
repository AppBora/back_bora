package br.com.bora.service.marketplace;

import br.com.bora.dto.InboundOrder;
import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Pedido;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.service.IntegracaoService;
import br.com.bora.service.MarketplaceNormalizer;
import br.com.bora.service.PedidoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Motor de recebimento dos marketplaces oficiais.
 *
 * <p>A cada 30 segundos consulta os eventos de cada loja conectada, transforma os pedidos novos
 * em pedidos do BoraHapp e confirma o recebimento dos eventos. O intervalo não é arbitrário: no
 * iFood a loja só aparece como <b>online</b> enquanto o polling acontece — se este serviço parar,
 * o restaurante some do aplicativo.</p>
 *
 * <p>Falha de uma loja não derruba as outras: cada uma é processada isoladamente e o erro fica
 * registrado na própria integração, visível no painel.</p>
 */
@Slf4j
@Service
public class MarketplacePoller {

    /** Eventos que representam um pedido novo a ser criado aqui dentro. */
    private static final List<String> EVENTOS_DE_PEDIDO = List.of("PLACED", "CREATED", "ORDER_PLACED", "CONFIRMED");

    private final List<MarketplaceClient> clients;
    private final IntegracaoCanalRepository repo;
    private final MarketplaceNormalizer normalizer;
    private final PedidoService pedidos;
    private final boolean habilitado;

    /**
     * Cada loja é consultada em paralelo, com teto de tempo para o ciclo inteiro. Serial não serve:
     * uma loja lenta atrasaria o batimento das demais e o iFood tiraria todas do ar.
     */
    private final ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "marketplace-poller");
        t.setDaemon(true);
        return t;
    });

    public MarketplacePoller(List<MarketplaceClient> clients, IntegracaoCanalRepository repo,
                             MarketplaceNormalizer normalizer, PedidoService pedidos,
                             @Value("${marketplace.polling.habilitado:true}") boolean habilitado) {
        this.clients = clients;
        this.repo = repo;
        this.normalizer = normalizer;
        this.pedidos = pedidos;
        this.habilitado = habilitado;
    }

    @Scheduled(fixedDelayString = "${marketplace.polling.intervalo-ms:30000}")
    public void rodar() {
        if (!habilitado) return;

        List<Callable<Void>> tarefas = new ArrayList<>();
        for (MarketplaceClient client : clients) {
            if (!client.configurado()) continue;   // app da plataforma sem credencial: nada a fazer
            for (IntegracaoCanal i : conectadas(client.canal())) {
                tarefas.add(() -> {
                    try {
                        processar(client, i);
                    } catch (Exception e) {
                        log.warn("[{}] loja {}: ciclo de polling abortado: {}",
                                client.canal(), i.lojaId, e.getMessage());
                    }
                    return null;
                });
            }
        }
        if (tarefas.isEmpty()) return;

        try {
            // Teto abaixo do intervalo: o que não terminar é abandonado e tentado no próximo ciclo,
            // em vez de empurrar o batimento de todas as lojas para frente.
            pool.invokeAll(tarefas, 25, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void encerrar() {
        pool.shutdownNow();
    }

    private List<IntegracaoCanal> conectadas(String canal) {
        List<IntegracaoCanal> out = new ArrayList<>();
        for (IntegracaoCanal i : repo.findByAtivoTrue()) {
            if (canal.equalsIgnoreCase(i.canal) && i.prontaParaSincronizar()) out.add(i);
        }
        return out;
    }

    private void processar(MarketplaceClient client, IntegracaoCanal i) {
        List<Map<String, Object>> eventos = client.polling(i);
        if (eventos.isEmpty()) return;

        List<String> paraConfirmar = new ArrayList<>();
        for (Map<String, Object> ev : eventos) {
            String eventoId = str(ev.get("id"));
            if (eventoId == null) continue;

            String tipo = str(firstNonNull(ev.get("code"), ev.get("fullCode"), ev.get("eventType")));
            String orderId = str(firstNonNull(ev.get("orderId"), ev.get("correlationId"), ev.get("resourceId")));
            boolean ehPedidoNovo = orderId != null && tipo != null
                    && EVENTOS_DE_PEDIDO.contains(tipo.toUpperCase());

            if (!ehPedidoNovo) {
                // Evento que não nos interessa ainda precisa ser confirmado, senão volta para sempre.
                paraConfirmar.add(eventoId);
                continue;
            }

            try {
                criarPedido(client, i, orderId);
                paraConfirmar.add(eventoId);
            } catch (Exception e) {
                // NÃO confirma: falha transitória (banco fora, timeout) faria o pedido sumir para
                // sempre, porque o marketplace só reenvia o que não foi confirmado.
                log.warn("[{}] loja {}: falha ao importar o pedido {} — evento mantido para a próxima tentativa: {}",
                        client.canal(), i.lojaId, orderId, e.getMessage());
            }
        }

        client.acknowledge(i, paraConfirmar);
    }

    private void criarPedido(MarketplaceClient client, IntegracaoCanal i, String orderId) {
        Map<String, Object> detalhe = client.detalhePedido(i, orderId);
        if (detalhe.isEmpty()) return;

        InboundOrder normalizado = normalizer.normalizar(i.canal, detalhe);
        Pedido criado = pedidos.criarExterno(i.lojaId, i.canal, IntegracaoService.label(i.canal), normalizado);
        i.pedidosRecebidos = (i.pedidosRecebidos == null ? 0 : i.pedidosRecebidos) + 1;
        i.ultimaSync = java.time.OffsetDateTime.now();
        repo.save(i);
        log.info("[{}] loja {}: pedido externo {} importado como #{}", i.canal, i.lojaId, orderId, criado.id);

        // Aceite automático: confirma no marketplace assim que o pedido entra aqui.
        if (Boolean.TRUE.equals(i.autoAceitar)) {
            client.enviarStatus(i, orderId, MarketplaceClient.ACEITE_INICIAL);
        }
    }

    private static Object firstNonNull(Object... vs) {
        for (Object v : vs) if (v != null) return v;
        return null;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
