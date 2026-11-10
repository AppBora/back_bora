package br.com.bora.service;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PIX no cardápio: cobranças criadas na conta Asaas DO LOJISTA (chave dele na integração PIX).
 * O dinheiro do pedido vai direto para o lojista — a plataforma nunca toca no valor.
 */
@Slf4j
@Service
public class PixService {

    /**
     * Fuso de Brasilia, explicito — o container roda em UTC.
     *
     * <p>O {@code dueDate} da cobranca PIX usava {@code LocalDate.now()} sem fuso. Das 21h a
     * meia-noite de Brasilia — <b>dentro do horario de jantar, que e o pico da loja</b> — isso ja e
     * "amanha" em UTC, e a cobranca nascia com o vencimento de um dia depois do pretendido.</p>
     *
     * <p>Aqui o efeito e brando (o cliente ganha mais prazo, nao menos), mas e data de cobranca
     * escrita errada no Asaas, e o mesmo descuido em outros lugares nao foi brando: o cupom com
     * validade "hoje" parava de valer as 21h ({@link br.com.bora.entity.Cupom}), e o teste do
     * liberarAcesso impedia QUALQUER deploy nessas tres horas.</p>
     */
    private static final java.time.ZoneId ZONE = java.time.ZoneId.of("America/Sao_Paulo");

    private final String baseUrl;
    private final String walletPlataforma;
    private final java.math.BigDecimal taxaPercentual;
    private final br.com.bora.repository.UsuarioRepository usuarios;

    public PixService(@Value("${asaas.base-url:https://sandbox.asaas.com/api/v3}") String baseUrl,
                      @Value("${asaas.platform-wallet-id:}") String walletPlataforma,
                      @Value("${asaas.taxa-percentual:0}") String taxaPercentual,
                      br.com.bora.repository.UsuarioRepository usuarios) {
        this.baseUrl = baseUrl;
        this.walletPlataforma = walletPlataforma;
        this.taxaPercentual = parse(taxaPercentual);
        this.usuarios = usuarios;
    }

    private static java.math.BigDecimal parse(String v) {
        try { return v == null || v.isBlank() ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(v.trim()); }
        catch (Exception e) { return java.math.BigDecimal.ZERO; }
    }

    private RestClient client(String apiKey) {
        return TempoLimite.cliente(baseUrl).defaultHeader("access_token", apiKey).build();
    }

    /** Ao conectar a integração PIX: gera token e cria o webhook na conta Asaas do lojista. */
    public void provisionarWebhook(IntegracaoCanal i, String urlPublica) {
        if (i.clientSecret == null || i.clientSecret.isBlank()) return;
        try {
            if (i.webhookToken == null || i.webhookToken.isBlank()) {
                i.webhookToken = UUID.randomUUID().toString().replace("-", "");
            }
            // Pelos vinculos, nao pela coluna loja_id: numa rede o dono tem uma conta so, e da 2a loja
            // em diante ele nao aparecia aqui. O aviso de falha do webhook ia para o endereco generico
            // da Bora -- que nem caixa de e-mail e -- em vez de ir para o lojista.
            String email = usuarios.donosDaLoja(i.lojaId).stream()
                    .findFirst().map(br.com.bora.entity.Usuario::getEmail).orElse("contato@borahapp.com.br");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "BoraHapp PIX - loja " + i.lojaId);
            body.put("email", email);
            body.put("url", urlPublica + "/public/pix-webhook/" + i.lojaId);
            body.put("enabled", true);
            body.put("interrupted", false);
            body.put("apiVersion", 3);
            body.put("sendType", "SEQUENTIALLY");
            body.put("authToken", i.webhookToken);
            body.put("events", List.of("PAYMENT_RECEIVED", "PAYMENT_CONFIRMED"));
            client(i.clientSecret).post().uri("/webhooks").body(body).retrieve().body(Map.class);
            i.status = "CONECTADO";
        } catch (Exception e) {
            log.warn("PIX loja {}: falha ao criar webhook no Asaas do lojista: {}", i.lojaId, e.getMessage());
            i.status = "ERRO";
        }
    }

    /**
     * Cria a cobrança PIX do pedido na conta do lojista e devolve o QR Code.
     * Retorno: paymentId, payload (copia-e-cola) e encodedImage (PNG base64).
     */
    @SuppressWarnings("unchecked")
    /**
     * Cancela a cobrança no Asaas. Usado quando o pedido expira sem pagamento.
     *
     * <p>Sem isto o QR continuava valendo o dia inteiro (ele nasce com vencimento "hoje"), enquanto o
     * pedido já tinha sido cancelado aos 30 minutos. Quem pagasse depois mandava dinheiro para uma
     * venda que não existia mais.</p>
     *
     * @return true se o Asaas confirmou o cancelamento. Nunca lança: falhar aqui não pode derrubar a
     *         rotina que cancela os pedidos.
     */
    public boolean cancelarCobranca(Loja loja, IntegracaoCanal i, String paymentId) {
        if (paymentId == null || paymentId.isBlank()) return false;
        String apiKey = loja != null && loja.asaasApiKey != null && !loja.asaasApiKey.isBlank()
                ? loja.asaasApiKey : (i == null ? null : i.clientSecret);
        if (apiKey == null || apiKey.isBlank()) return false;
        try {
            Map<String, Object> r = client(apiKey).delete().uri("/payments/{id}", paymentId)
                    .retrieve().body(Map.class);
            return r != null && Boolean.TRUE.equals(r.get("deleted"));
        } catch (Exception e) {
            log.warn("Nao consegui cancelar a cobranca {} no Asaas: {}", paymentId, e.getMessage());
            return false;
        }
    }

    /**
     * O dinheiro desta cobranca ENTROU? Pergunta ao Asaas.
     *
     * <p>Existe porque quem confirma pagamento aqui e o webhook, e webhook falha: o Asaas interrompe
     * o envio depois de erros seguidos, o lojista pode apagar o webhook no painel dele, e ja houve
     * loja em producao com a conta viva e ZERO webhooks registrados. Sem esta pergunta, a rotina que
     * expira o checkout abandonado cancelava a venda PAGA: devolvia estoque e cashback, o cliente
     * ficava sem a comida e com o dinheiro fora, e a cozinha nunca via o pedido.</p>
     *
     * <p>{@code RECEIVED_IN_CASH} entra na lista porque e como o Asaas marca a baixa manual — se o
     * lojista deu baixa na mao, o dinheiro existe do mesmo jeito.</p>
     *
     * @return true so quando o Asaas AFIRMA que recebeu. Nunca lanca, e qualquer duvida (erro de
     *         rede, cobranca sumida) devolve false: na duvida o pedido segue o caminho de sempre.
     */
    @SuppressWarnings("unchecked")
    /**
     * O que o Asaas respondeu sobre uma cobranca. <b>"Nao consegui perguntar" NAO e "nao pagou".</b>
     *
     * <p>Este enum existe porque o metodo abaixo devolvia {@code boolean}, e um booleano nao tem como
     * dizer "nao sei". Todo erro -- timeout, 429, 5xx, chave revogada, loja sem chave -- caia no
     * mesmo {@code false} de "o cliente nao pagou", e quem chama cancelava o pedido. Ver
     * {@link #conferirPagamento}.</p>
     */
    public enum PagamentoNoAsaas {
        /** O Asaas respondeu que a cobranca esta paga. */
        PAGO,
        /** O Asaas respondeu, e a cobranca NAO esta paga. */
        NAO_PAGO,
        /** Nao houve resposta utilizavel. Nao da para concluir nada: tentar de novo depois. */
        NAO_CONSEGUI_PERGUNTAR
    }

    /**
     * Pergunta ao Asaas se a cobranca foi paga, distinguindo os TRES estados possiveis.
     *
     * <h2>O defeito que isto fecha (confirmado em 10/11/2026)</h2>
     * <p>Este metodo era {@code boolean cobrancaFoiPaga(...)} e devolvia {@code false} dentro do
     * {@code catch (Exception e)}. Entao <b>timeout, 429, 5xx e chave revogada diziam exatamente a
     * mesma coisa que "o cliente nao pagou"</b> -- e o {@code CobradorDePixService} seguia para o
     * cancelamento, que devolve estoque, devolve cashback e avisa o cliente de que o pedido foi
     * cancelado. Com o Asaas fora do ar por meia hora, <b>pedido pago virava pedido cancelado</b>:
     * dinheiro na conta do lojista, comida nao entregue, cliente avisado do contrario.</p>
     *
     * <h2>Por que "pular a rodada" e a escolha certa, e nao um risco novo</h2>
     * <p>Havia um teste trancando o comportamento antigo
     * ({@code asaasForaDoAr_naoInventaPagamento}), com esta justificativa: "melhor cancelar um
     * checkout abandonado do que liberar comida de graca". <b>A escolha nunca foi essa.</b> Nao
     * cancelar nao libera nada: o pedido continua com {@code aguardandoPagamento = true}, continua
     * fora do faturamento e continua sem ir para a cozinha. Ele so nao e cancelado AGORA -- e a
     * rotina roda de novo em 5 minutos, quando o Asaas provavelmente responde.</p>
     *
     * <p>A assimetria e o contrario do que aquele comentario supunha: adiar o cancelamento custa uma
     * rodada; cancelar venda paga e irreversivel -- o dinheiro ja entrou, o estoque ja voltou e o
     * cliente ja foi avisado de que nao vai receber.</p>
     *
     * @return {@link PagamentoNoAsaas#NAO_PAGO} tambem quando nao existe cobranca ({@code paymentId}
     *         nulo ou vazio): sem cobranca criada, ninguem pagou. Mas {@code NAO_CONSEGUI_PERGUNTAR}
     *         quando falta a chave da loja -- ai o fato existe e e a nossa vista que nao alcanca.
     */
    public PagamentoNoAsaas conferirPagamento(Loja loja, IntegracaoCanal i, String paymentId) {
        if (paymentId == null || paymentId.isBlank()) return PagamentoNoAsaas.NAO_PAGO;
        String apiKey = loja != null && loja.asaasApiKey != null && !loja.asaasApiKey.isBlank()
                ? loja.asaasApiKey : (i == null ? null : i.clientSecret);
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Nao tenho chave do Asaas para conferir a cobranca {} (loja {}). NAO vou concluir "
                            + "que o cliente nao pagou: o pedido fica como esta.",
                    paymentId, loja == null ? null : loja.id);
            return PagamentoNoAsaas.NAO_CONSEGUI_PERGUNTAR;
        }
        try {
            Map<String, Object> r = client(apiKey).get().uri("/payments/{id}", paymentId)
                    .retrieve().body(Map.class);
            if (r == null || r.get("status") == null) {
                // Resposta sem status e resposta que nao da para interpretar -- nao e "nao pagou".
                log.warn("O Asaas respondeu sobre a cobranca {} sem o campo status. NAO da para "
                        + "concluir se foi paga.", paymentId);
                return PagamentoNoAsaas.NAO_CONSEGUI_PERGUNTAR;
            }
            String status = String.valueOf(r.get("status"));
            boolean pago = "RECEIVED".equals(status) || "CONFIRMED".equals(status)
                    || "RECEIVED_IN_CASH".equals(status);
            return pago ? PagamentoNoAsaas.PAGO : PagamentoNoAsaas.NAO_PAGO;
        } catch (Exception e) {
            log.warn("Nao consegui conferir a cobranca {} no Asaas: {}. O pedido NAO sera cancelado "
                    + "nesta rodada.", paymentId, e.getMessage());
            return PagamentoNoAsaas.NAO_CONSEGUI_PERGUNTAR;
        }
    }

    public Map<String, Object> criarCobranca(IntegracaoCanal i, Loja loja, Pedido pedido,
                                             String nomeCliente, String cpfCnpj) {
        // Preferir a subconta do lojista (recebimento white-label); cair para a chave legada se não houver.
        boolean viaSubconta = loja.asaasApiKey != null && !loja.asaasApiKey.isBlank();
        String apiKey = viaSubconta ? loja.asaasApiKey : (i == null ? null : i.clientSecret);
        if (apiKey == null || apiKey.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Recebimento PIX não ativado para esta loja");
        }
        RestClient c = client(apiKey);
        Map<String, Object> cliente = c.post().uri("/customers").body(Map.of(
                "name", nomeCliente == null || nomeCliente.isBlank() ? "Cliente Cardápio" : nomeCliente,
                "cpfCnpj", cpfCnpj)).retrieve().body(Map.class);
        String customerId = cliente == null ? null : (String) cliente.get("id");

        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("customer", customerId);
        corpo.put("billingType", "PIX");
        corpo.put("value", pedido.valorTotal);
        corpo.put("dueDate", LocalDate.now(ZONE).toString());
        corpo.put("description", "Pedido " + (pedido.codigo == null ? pedido.id : pedido.codigo) + " - " + loja.nome);
        corpo.put("externalReference", String.valueOf(pedido.id));
        // Split: retém a taxa da plataforma quando o recebimento é pela subconta do lojista.
        // A loja pode ter taxa própria (fundador = 0); sem valor definido, vale o padrão global.
        java.math.BigDecimal taxa = loja.splitPercentual != null ? loja.splitPercentual : taxaPercentual;
        if (viaSubconta && walletPlataforma != null && !walletPlataforma.isBlank()
                && taxa.signum() > 0) {
            corpo.put("split", List.of(Map.of(
                    "walletId", walletPlataforma,
                    "percentualValue", taxa)));
        }
        Map<String, Object> pagamento = c.post().uri("/payments").body(corpo).retrieve().body(Map.class);
        String paymentId = pagamento == null ? null : (String) pagamento.get("id");

        Map<String, Object> qr = c.get().uri("/payments/{id}/pixQrCode", paymentId).retrieve().body(Map.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paymentId", paymentId);
        out.put("payload", qr == null ? null : qr.get("payload"));
        out.put("encodedImage", qr == null ? null : qr.get("encodedImage"));
        return out;
    }
}
