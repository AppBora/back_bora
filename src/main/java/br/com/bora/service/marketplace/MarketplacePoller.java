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
            if (!canal.equalsIgnoreCase(i.canal)) continue;
            if (!(i.prontaParaSincronizar() || i.mereceNovaTentativa())) {
                // NAO PULA MAIS EM SILENCIO. Era `continue` seco aqui, e isso criava um estado que
                // nao tem como ser descoberto: integracao ATIVA e marcada CONECTADO, que nunca busca
                // um pedido, sem log e sem nada na tela. Medido na producao em 25/10/2026: a loja 17
                // estava assim, com merchant_id vazio e ultimo_polling_em NULO -- nunca buscou nada
                // desde que nasceu -- e o painel dizia conectado. O caso vizinho ("a loja nao pode
                // operar") ja logava; este nao.
                anotarImpedimento(i);
                continue;
            }
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

    /**
     * Diz, onde o lojista ve, por que esta integracao nao consegue buscar pedido.
     *
     * <p>Escreve no {@code ultimo_erro} — que e saude de integracao, e nao recado sobre um pedido
     * (ver V61). O ciclo de vida fecha certo: quando o lojista preenche o que falta, o polling passa
     * a rodar, da certo, e o {@code marcarPollingOk} limpa a mensagem sozinho.</p>
     *
     * <p><b>Grava so quando o texto MUDA.</b> O poller roda a cada 30 segundos; gravar a cada ciclo
     * seria uma escrita por integracao a cada meio minuto, para sempre, dizendo a mesma coisa.</p>
     *
     * <p>NAO mexe no status: {@code ERRO} jogaria a loja na fila de 10 minutos do item 5.1, e isto
     * nao e falha — e configuracao incompleta.</p>
     */
    private void anotarImpedimento(IntegracaoCanal i) {
        // SO o caso que fica preso em silencio: sem o ID da loja, NADA pode funcionar nunca -- nem o
        // polling, nem o `mereceNovaTentativa` (que exige o ID). E o estado da loja 17.
        //
        // O QUE EU TIREI DAQUI, E POR QUE (25/10/2026, minutos depois de subir a v147)
        //
        // A primeira versao tambem escrevia quando o status nao era CONECTADO. Fui conferir em
        // producao e ela havia passado por cima do `ultimo_erro` do 99Food da loja 1, que estava em
        // ERRO: trocou o motivo REAL que o `marcarErro` havia gravado por "a conexao esta em ERRO"
        // -- uma tautologia -- e mandou "refaca o vinculo", conselho ERRADO, porque integracao em
        // ERRO com o ID preenchido tenta de novo sozinha a cada 10 minutos
        // (`mereceNovaTentativa`) e pode se resolver sem ninguem fazer nada.
        //
        // Ou seja: eu apaguei o diagnostico e dei trabalho inutil ao lojista, para dizer o que o
        // proprio campo `status` ja dizia. Os outros estados (DESCONECTADO, PRONTO,
        // AGUARDANDO_AUTORIZACAO) tambem nao precisam: o status e honesto e a tela tem caixa propria
        // para cada um ("Passo 1 -- conectar a loja", "Passo 2 -- autorize no portal").
        if (i.merchantId != null && !i.merchantId.isBlank()) return;

        // O texto nomeia o campo como ele aparece na tela de cada canal.
        String motivo = "Nenhum pedido do " + IntegracaoService.label(i.canal) + " vai entrar: falta o "
                + ("IFOOD".equalsIgnoreCase(i.canal) ? "Merchant ID" : "ID da loja")
                + " para casar os pedidos com esta loja. Informe-o em Integracoes e salve.";
        if (motivo.equals(i.ultimoErro)) return;          // ja esta dito; nao reescreve a cada 30s
        i.ultimoErro = br.com.bora.entity.LarguraDeColuna.encurtar(motivo,
                br.com.bora.entity.LarguraDeColuna.INTEGRACAO_ULTIMO_ERRO,
                "integracao_canal.ultimo_erro da loja " + i.lojaId);
        repo.marcarImpedimento(i.id, i.lojaId, i.ultimoErro);
        log.warn("AUDITORIA marketplace: loja {} tem o {} ATIVO e nao consegue buscar pedido. {}",
                i.lojaId, i.canal, i.ultimoErro);
    }

    /**
     * Quantos minutos vale retentar um aceite.
     *
     * <p>O aceite importa em MINUTOS: passado o prazo do canal, o pedido ja foi cancelado do lado
     * dele e mandar "confirmo" nao traz a venda de volta -- atrapalha. Duas horas e folga larga para
     * cobrir API fora do ar e reinicio da nossa aplicacao, e curta o bastante para nunca tocar em
     * pedido antigo.</p>
     */
    private static final int ACEITE_RETENTA_ATE_MIN = 120;

    /** Teto por ciclo: fila grande nao pode virar rajada de chamadas ao canal. */
    private static final int ACEITE_RETENTA_POR_CICLO = 10;

    /**
     * Retenta o aceite dos pedidos que entraram e nao foram confirmados no canal.
     *
     * <h2>O defeito (achado pelo QA em 26/10/2026, remedido em 27/10)</h2>
     * <p>O {@code reenviarAceiteSeFaltou} era chamado de <b>um unico lugar</b>: de dentro do
     * {@code criarPedido}, ou seja, so quando chegava outro evento daquele mesmo pedido. O proprio
     * comentario dizia "o proximo evento do mesmo pedido tenta de novo" -- e se ele nao vier, nao
     * tenta nunca. No iFood o segundo evento (CONFIRMED) so chega <b>depois</b> que o aceite da
     * certo, entao o caso que precisava de retentativa era exatamente o que nao recebia nenhuma.</p>
     *
     * <p>O resultado: pedido RECEBIDO no painel, nao aceito no canal, e o cliente do marketplace
     * esperando uma comida que a loja acha que aceitou.</p>
     *
     * <p><b>Nao esta acontecendo hoje:</b> medido na producao em 27/10/2026, os 8 pedidos de
     * marketplace sao de julho e setembro, anteriores a V48 (14/10) que criou a coluna -- os nulos
     * sao heranca, nao pedido preso. E a armadilha, nao o estrago.</p>
     */
    private void retentarAceites(MarketplaceClient client, IntegracaoCanal i) {
        if (!Boolean.TRUE.equals(i.autoAceitar)) return;
        var desde = java.time.OffsetDateTime.now().minusMinutes(ACEITE_RETENTA_ATE_MIN);
        var presos = pedidos.semAceiteConfirmado(i.lojaId, i.canal, desde);
        if (presos.isEmpty()) return;
        int feitos = 0;
        for (var p : presos) {
            if (feitos++ >= ACEITE_RETENTA_POR_CICLO) {
                log.warn("[{}] loja {}: {} pedido(s) ainda sem aceite; o resto vai no proximo ciclo",
                        i.canal, i.lojaId, presos.size() - ACEITE_RETENTA_POR_CICLO);
                break;
            }
            log.warn("[{}] loja {}: pedido {} (#{}) entrou sem aceite confirmado; retentando",
                    i.canal, i.lojaId, p.idExterno, p.id);
            marcarAceite(client, i, p.idExterno, p);
        }
    }

    private void processar(MarketplaceClient client, IntegracaoCanal i) {
        // ANTES do polling: um aceite preso nao depende de haver evento novo, e era justamente disso
        // que ele dependia.
        retentarAceites(client, i);

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
            // O marketplace aceitou o pedido de cancelamento (202) e depois recusou. O pedido segue
            // ativo lá e cancelado aqui.
            //
            // ISTO SÓ IA PARA O LOG, e era o defeito (achado pelo QA em 26/10/2026, remedido em
            // 27/10). O comentário antigo dizia "precisa de gente olhando" -- e ninguém olha log de
            // container. O pedido fica cancelado no painel, o cliente do marketplace continua
            // esperando a comida, e a loja só descobre quando ele reclama.
            //
            // Agora vai para a coluna PRÓPRIA de recado (V61), que a tela de Integrações mostra. Não
            // reabre o pedido aqui de propósito: quem decide se vai preparar é a loja, e reabrir
            // sozinho um pedido que o lojista cancelou seria pior que avisar.
            log.warn("[{}] loja {}: o marketplace RECUSOU o cancelamento do pedido externo {} depois de aceitar o pedido; "
                    + "confira no portal do marketplace", i.canal, i.lojaId, orderId);
            avisarCancelamentoRecusado(i, orderId);
        } else if (client.statusQueOcanalAvancou(tipo) != null) {
            // ITEM 5.6 DA VARREDURA: o marketplace andou com o pedido e isso nunca chegava aqui.
            // Quando a entrega e do canal, quem sabe onde o motoboy esta e ele -- e o pedido ficava
            // parado no painel no status que a loja clicou por ultimo, para sempre.
            //
            // `avancarPorMarketplace` so avanca (nunca recua, nunca reabre encerrado) e NAO devolve o
            // status para o canal: contar de volta a novidade que ele acabou de dar seria eco.
            br.com.bora.entity.StatusPedido novo = client.statusQueOcanalAvancou(tipo);
            if (pedidos.avancarPorMarketplace(i.lojaId, i.canal, orderId, novo)) {
                log.info("[{}] loja {}: o canal avancou o pedido externo {} para {}",
                        i.canal, i.lojaId, orderId, novo);
            } else {
                // Normal: aviso repetido, fora de ordem, ou pedido que a loja ja tinha fechado na mao.
                log.debug("[{}] loja {}: aviso de {} para o pedido {} nao mudou nada",
                        i.canal, i.lojaId, novo, orderId);
            }
        } else {
            // Nao reconhecido. Fica no log de proposito: e assim que a producao vai me ensinar os
            // nomes de evento que eu nao conheco, em vez de eu adivinhar no codigo.
            log.info("[{}] loja {}: evento \"{}\" do pedido {} nao e tratado por aqui. Se este nome "
                            + "aparecer com frequencia, ele pode ser um avanco de status que falta "
                            + "mapear (ver varredura 5.6).",
                    i.canal, i.lojaId, tipo, orderId);
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
        // Item 5.5: a soma e do BANCO. Era leitura-soma-escrita em cima de um valor carregado no
        // comeco do ciclo: dois pedidos no mesmo ciclo contavam um, e a linha inteira era regravada
        // por cima do que o lojista tinha salvo.
        i.pedidosRecebidos = (i.pedidosRecebidos == null ? 0 : i.pedidosRecebidos) + 1;
        i.ultimaSync = java.time.OffsetDateTime.now();
        repo.contarPedidoRecebido(i.id, i.lojaId, i.ultimaSync);
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
                o.numeroExibicao(), o.clienteIdExterno(), o.entregaPeloCanal());
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

    /**
     * O marketplace recusou um cancelamento que ele havia aceitado como PEDIDO. O lojista tem de ver.
     *
     * <p>Mesma coluna e mesmo caminho do aceite recusado ({@code ultimo_aviso}, V61), de proposito: e
     * recado, nao erro de conexao. Escrever em {@code ultimo_erro} faria o {@code marcarPollingOk}
     * apaga-lo no ciclo seguinte, que foi o defeito da v146.</p>
     */
    private void avisarCancelamentoRecusado(IntegracaoCanal i, String orderId) {
        i.ultimoAvisoEm = java.time.OffsetDateTime.now();
        i.ultimoAviso = br.com.bora.entity.LarguraDeColuna.encurtar(
                "O " + IntegracaoService.label(i.canal) + " RECUSOU o cancelamento do pedido "
                        + orderId + ". Ele esta cancelado aqui e ATIVO la: confira no portal do canal.",
                br.com.bora.entity.LarguraDeColuna.INTEGRACAO_ULTIMO_ERRO,
                "integracao_canal.ultimo_aviso da loja " + i.lojaId);
        repo.marcarAviso(i.id, i.lojaId, i.ultimoAviso, i.ultimoAvisoEm);
    }

    /** Envia o aceite e, so se o marketplace aceitar, grava a marca. */
    private void marcarAceite(MarketplaceClient client, IntegracaoCanal i, String orderId, Pedido p) {
        if (client.enviarStatus(i, orderId, MarketplaceClient.ACEITE_INICIAL)) {
            pedidos.marcarAceiteEnviado(p.lojaId, p.id);
        } else {
            // Fica sem a marca de proposito: o proximo evento do mesmo pedido tenta de novo. E o
            // recado vai para a coluna PROPRIA de recado (V61), que a tela de Integracoes mostra.
            //
            // Antes ia para `ultimo_erro`, de onde o marcarPollingOk apagava a cada 30 segundos. Aqui
            // doia menos que no status recusado, porque este caso se resolve sozinho no reenvio — mas
            // enquanto nao se resolve, o lojista tem um pedido do marketplace que nao entrou e nada
            // na tela dizendo isso.
            //
            // O `orderId` vem do marketplace, entao e texto de fora e nao tem tamanho garantido.
            i.ultimoAvisoEm = java.time.OffsetDateTime.now();
            i.ultimoAviso = br.com.bora.entity.LarguraDeColuna.encurtar(
                    "Aceite do pedido " + orderId + " recusado pelo " + IntegracaoService.label(i.canal)
                            + "; sera reenviado",
                    br.com.bora.entity.LarguraDeColuna.INTEGRACAO_ULTIMO_ERRO,
                    "integracao_canal.ultimo_aviso da loja " + i.lojaId);
            repo.marcarAviso(i.id, i.lojaId, i.ultimoAviso, i.ultimoAvisoEm);   // item 5.5: sem ERRO
        }
    }

    private static Object firstNonNull(Object... vs) {
        for (Object v : vs) if (v != null) return v;
        return null;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
