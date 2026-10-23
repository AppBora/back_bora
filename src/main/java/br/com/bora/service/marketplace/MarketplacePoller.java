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

    /** O log de auditoria: o que o dono precisa achar depois, nao o ruido do polling. */
    private static final org.slf4j.Logger AUDITORIA =
            org.slf4j.LoggerFactory.getLogger("AUDITORIA");

    private final List<MarketplaceClient> clients;
    private final IntegracaoCanalRepository repo;
    private final MarketplaceNormalizer normalizer;
    private final PedidoService pedidos;
    private final br.com.bora.repository.LojaRepository lojas;
    private final br.com.bora.security.RegraDeAcesso regra;
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
                             br.com.bora.repository.LojaRepository lojas,
                             br.com.bora.security.RegraDeAcesso regra,
                             @Value("${marketplace.polling.habilitado:true}") boolean habilitado) {
        this.clients = clients;
        this.repo = repo;
        this.normalizer = normalizer;
        this.pedidos = pedidos;
        this.lojas = lojas;
        this.regra = regra;
        this.habilitado = habilitado;
    }

    @Scheduled(fixedDelayString = "${marketplace.polling.intervalo-ms:30000}")
    public void rodar() {
        if (!habilitado) return;

        List<Callable<Void>> tarefas = new ArrayList<>();
        for (MarketplaceClient client : clients) {
            for (IntegracaoCanal i : conectadas(client.canal())) {
                // Sem credencial (nem da loja, nem da plataforma) não há o que sincronizar.
                if (!client.configurado(i)) continue;
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
            // Quem esta em ERRO tambem entra, mas espacado: ver IntegracaoCanal.mereceNovaTentativa.
            // Sem isso, integracao que caiu nunca mais se levanta sozinha, nem com o problema
            // resolvido.
            if (!canal.equalsIgnoreCase(i.canal)
                    || !(i.prontaParaSincronizar() || i.mereceNovaTentativa())) continue;
            // A integração estar conectada não basta: a LOJA precisa poder operar. Suspender ou
            // arquivar um cliente não encostava nas integrações, então o robô seguia puxando e
            // aceitando pedido de uma loja que ninguém conseguia abrir — o cliente final pedia, o
            // marketplace confirmava e não havia cozinha do outro lado.
            if (!regra.podeOperar(lojas.findById(i.lojaId).orElse(null))) {
                log.info("Loja {} nao pode operar agora (suspensa, arquivada ou com prazo vencido): "
                        + "pulando o {} neste ciclo", i.lojaId, canal);
                continue;
            }
            out.add(i);
        }
        return out;
    }

    private void processar(MarketplaceClient client, IntegracaoCanal i) {
        List<Map<String, Object>> eventos = client.polling(i);
        if (eventos.isEmpty()) return;

        List<Map<String, Object>> paraConfirmar = new ArrayList<>();
        for (Map<String, Object> ev : eventos) {
            // Cada canal nomeia o id do evento do seu jeito (iFood "id", Open Delivery "eventId").
            // Ler o campo errado fazia TODO evento do 99 cair aqui e sumir sem ser importado.
            if (client.eventoId(ev) == null) continue;
            try {
                tratarEvento(client, i, ev);
                paraConfirmar.add(ev);
            } catch (Exception e) {
                // NAO confirma: falha transitoria (banco fora, timeout) faria o pedido sumir para
                // sempre, porque o marketplace so reenvia o que nao foi confirmado.
                log.warn("[{}] loja {}: falha ao tratar o evento {} — mantido para a proxima tentativa: {}",
                        client.canal(), i.lojaId, client.eventoId(ev), e.getMessage());
            }
        }

        client.acknowledge(i, paraConfirmar);
    }

    /**
     * Um evento do marketplace, venha do polling ou do webhook. Lanca excecao quando a falha e
     * transitoria: quem chamou nao confirma, e o evento volta.
     */
    public void tratarEvento(MarketplaceClient client, IntegracaoCanal i, Map<String, Object> ev) {
        String tipo = client.tipoEvento(ev);
        String orderId = client.pedidoDoEvento(ev);
        if (tipo == null || orderId == null) return;

        if (client.ehPedidoNovo(tipo)) {
            criarPedido(client, i, orderId);
        } else if (client.ehCancelamento(tipo)) {
            // Cancelado do lado do marketplace — pelo cliente, ou confirmando um cancelamento nosso.
            // Antes este evento era confirmado e descartado, e o pedido seguia ativo no painel.
            if (pedidos.cancelarPorMarketplace(i.lojaId, i.canal, orderId, client.motivoDoCancelamento(ev))) {
                log.info("[{}] loja {}: pedido externo {} cancelado pelo marketplace", i.canal, i.lojaId, orderId);
            } else {
                // Cancelamento que a loja pediu aqui: o pedido já estava cancelado no Bora e este evento é a
                // confirmação final do marketplace. Registrar é o que prova que os dois lados fecharam.
                pedidos.buscarExterno(i.lojaId, i.canal, orderId)
                        .filter(p -> p.status == br.com.bora.entity.StatusPedido.CANCELADO)
                        .ifPresent(p -> log.info("[{}] loja {}: marketplace confirmou o cancelamento do pedido externo {} (#{})",
                                i.canal, i.lojaId, orderId, p.id));
            }
        } else if (client.ehPedidoDeCancelamento(tipo)) {
            responderPedidoDeCancelamento(client, i, orderId, ev);
        } else if (tipo != null && tipo.toUpperCase().contains("CANCELLATION_REQUEST_FAILED")) {
            // O marketplace aceitou o pedido de cancelamento (202) e depois recusou. O pedido segue ativo lá
            // e cancelado aqui: precisa de gente olhando.
            log.warn("[{}] loja {}: o marketplace RECUSOU o cancelamento do pedido externo {} depois de aceitar o pedido; "
                    + "confira no portal do marketplace", i.canal, i.lojaId, orderId);
        }
    }

    /**
     * O cliente pediu para cancelar. Aceita enquanto a cozinha ainda pode parar; depois de pronto
     * ou despachado, nega com o motivo da lista do canal.
     */
    private void responderPedidoDeCancelamento(MarketplaceClient client, IntegracaoCanal i, String orderId,
                                               Map<String, Object> ev) {
        String status = pedidos.buscarExterno(i.lojaId, i.canal, orderId)
                .map(p -> p.status == null ? null : p.status.name()).orElse(null);
        boolean aceitar = status == null || List.of("RECEBIDO", "CONFIRMADO", "EM_PREPARO", "CANCELADO").contains(status);
        client.responderPedidoDeCancelamento(i, orderId, aceitar, status);
        if (aceitar) pedidos.cancelarPorMarketplace(i.lojaId, i.canal, orderId, client.motivoDoCancelamento(ev));
        log.info("[{}] loja {}: pedido de cancelamento do cliente {} para o pedido {} (status {})",
                i.canal, i.lojaId, aceitar ? "ACEITO" : "NEGADO", orderId, status);
    }

    private void criarPedido(MarketplaceClient client, IntegracaoCanal i, String orderId) {
        // O mesmo pedido volta em mais de um evento (no iFood: PLACED e depois CONFIRMED). Já existindo,
        // não há nada a importar — e reenviar o aceite ao marketplace é o que não pode: era o "confirm"
        // em dobro visto no primeiro pedido real (19/09).
        var jaImportado = pedidos.buscarExterno(i.lojaId, i.canal, orderId);
        if (jaImportado.isPresent()) {
            // Ja importado: nada a criar. Mas pode haver ACEITE a reenviar — ver reenviarAceiteSeFaltou.
            log.debug("[{}] loja {}: pedido externo {} já importado; evento ignorado", i.canal, i.lojaId, orderId);
            reenviarAceiteSeFaltou(client, i, orderId, jaImportado.get());
            return;
        }
        Map<String, Object> detalhe = client.detalhePedido(i, orderId);
        // Detalhe vazio e falha (o cliente engole a excecao e devolve vazio). Antes isto era um
        // "return" silencioso: o evento era confirmado e o pedido se perdia para sempre.
        if (detalhe.isEmpty()) {
            throw new IllegalStateException("o detalhe do pedido " + orderId + " veio vazio");
        }

        InboundOrder normalizado = normalizer.normalizar(i.canal, detalhe);
        // Item 5.7: pedido de TESTE entra marcado, para nao virar venda indistinguivel.
        boolean deTeste = ehPedidoDeTeste(detalhe);
        if (deTeste) {
            normalizado = comAvisoDeTeste(normalizado, i.canal);
        }
        Pedido criado = pedidos.criarExterno(i.lojaId, i.canal, IntegracaoService.label(i.canal), normalizado);
        if (deTeste) {
            AUDITORIA.warn("AUDITORIA marketplace: o pedido {} do {} (loja {}) e PEDIDO DE TESTE e "
                            + "entrou como #{}. Ele esta marcado na observacao, mas AINDA CONTA no "
                            + "faturamento -- cancele depois de validar o fluxo. Ver varredura 5.7.",
                    orderId, i.canal, i.lojaId, criado.id);
        }
        i.pedidosRecebidos = (i.pedidosRecebidos == null ? 0 : i.pedidosRecebidos) + 1;
        i.ultimaSync = java.time.OffsetDateTime.now();
        repo.save(i);
        log.info("[{}] loja {}: pedido externo {} importado como #{}", i.canal, i.lojaId, orderId, criado.id);

        // Aceite automatico: confirma no marketplace assim que o pedido entra aqui.
        if (Boolean.TRUE.equals(i.autoAceitar)) {
            marcarAceite(client, i, orderId, criado);
        }
    }

    /**
     * O marketplace mandou isto como pedido de TESTE?
     *
     * <h2>Item 5.7 da varredura</h2>
     * <p>{@code isTest} nao aparecia em nenhum {@code .java} do projeto — conferido em 22/10/2026.
     * Pedido de teste entrava como venda de verdade: baixava estoque, contava no faturamento, somava
     * no CMV. E pedido de teste acontece justamente na <b>homologacao</b>, que e quando o lojista esta
     * comecando e olhando os numeros.</p>
     *
     * <p>Le as duas formas: o {@code isTest} do iFood e o {@code test}/{@code isTest} que aparece em
     * payloads de Open Delivery. Aceita booleano e texto, porque os dois aparecem.</p>
     */
    private static boolean ehPedidoDeTeste(Map<String, Object> detalhe) {
        for (String campo : new String[] {"isTest", "test", "testOrder"}) {
            Object v = detalhe.get(campo);
            if (v == null) continue;
            if (v instanceof Boolean b) return b;
            if ("true".equalsIgnoreCase(String.valueOf(v))) return true;
        }
        return false;
    }

    /**
     * O mesmo pedido, com o aviso de teste na frente da observacao.
     *
     * <h2>O que isto resolve, e o que NAO resolve</h2>
     * <p>Resolve a parte invisivel: o pedido passa a dizer o que e, na observacao que a cozinha e o
     * card mostram, e fica no log de auditoria. O lojista ve e cancela.</p>
     *
     * <p><b>Nao resolve o faturamento:</b> ele continua contando como venda, porque excluir exige uma
     * coluna nova em {@code pedido} e o faturamento nao olha a origem ({@code somaReceita} filtra por
     * status e por PIX pendente, nada mais). Marcar e reversivel; mexer na conta do dono sem ele saber
     * nao e. <b>Decisao dele</b>, com proposta na varredura.</p>
     *
     * <p>E <b>nao recusa o pedido</b>, de proposito: a homologacao do iFood exige que a integracao
     * aceite e faca o pedido de teste andar. Recusar passaria o defeito para outro lugar — a loja nao
     * conseguiria homologar.</p>
     */
    private static InboundOrder comAvisoDeTeste(InboundOrder o, String canal) {
        String aviso = "*** PEDIDO DE TESTE DO " + IntegracaoService.label(canal).toUpperCase()
                + " -- NAO PREPARE, E CANCELE DEPOIS DE VALIDAR ***";
        String obs = o.observacao() == null || o.observacao().isBlank()
                ? aviso : aviso + " | " + o.observacao();
        return new InboundOrder(o.externalId(), o.clienteNome(), o.clienteTelefone(), o.endereco(),
                o.bairro(), o.pagamento(), obs, o.total(), o.itens(), o.taxaEntrega(),
                o.numeroExibicao(), o.clienteIdExterno());
    }

    /**
     * Reenvia o aceite de um pedido que ja esta aqui e que o marketplace nunca aceitou.
     *
     * <p><b>O defeito que isto fecha:</b> o aceite automatico era enviado e, se falhasse, o cliente do
     * marketplace engolia a excecao com um {@code log.warn}. O evento era confirmado de qualquer jeito,
     * e este caminho — "ja importado" — saia sem tentar de novo. O pedido ficava no painel da loja e o
     * iFood o cancelava sozinho por falta de aceite, depois de a cozinha ter preparado.</p>
     *
     * <p>Nao da para deduzir isso do status: o pedido fica RECEBIDO aqui ate alguem clicar, aceito ou
     * nao. Quem diz e a coluna {@code aceite_enviado_em} (V48). Sem ela, retentar reconfirmaria — que
     * foi o "confirm em dobro" do primeiro pedido real de iFood, em 19/09.</p>
     */
    private void reenviarAceiteSeFaltou(MarketplaceClient client, IntegracaoCanal i, String orderId, Pedido p) {
        if (!Boolean.TRUE.equals(i.autoAceitar)) return;
        if (p.aceiteEnviadoEm != null) return;                 // o marketplace ja aceitou
        if (p.status != br.com.bora.entity.StatusPedido.RECEBIDO) return;          // alguem ja mexeu no pedido na mao
        log.warn("[{}] loja {}: pedido {} (#{}) esta sem aceite confirmado; reenviando",
                i.canal, i.lojaId, orderId, p.id);
        marcarAceite(client, i, orderId, p);
    }

    /** Envia o aceite e, so se o marketplace aceitar, grava a marca. */
    private void marcarAceite(MarketplaceClient client, IntegracaoCanal i, String orderId, Pedido p) {
        if (client.enviarStatus(i, orderId, MarketplaceClient.ACEITE_INICIAL)) {
            pedidos.marcarAceiteEnviado(p.lojaId, p.id);
        } else {
            // Fica sem a marca de proposito: o proximo evento do mesmo pedido tenta de novo. E o erro
            // vai para `ultimo_erro` da integracao, que a tela de Integracoes mostra.
            // O `orderId` vem do marketplace, entao e texto de fora e nao tem tamanho garantido.
            i.ultimoErro = br.com.bora.entity.LarguraDeColuna.encurtar(
                    "Aceite do pedido " + orderId + " recusado pelo " + IntegracaoService.label(i.canal)
                            + "; sera reenviado",
                    br.com.bora.entity.LarguraDeColuna.INTEGRACAO_ULTIMO_ERRO,
                    "integracao_canal.ultimo_erro da loja " + i.lojaId);
            repo.save(i);
        }
    }

    private static Object firstNonNull(Object... vs) {
        for (Object v : vs) if (v != null) return v;
        return null;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
