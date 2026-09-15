package br.com.bora.service.marketplace;

import br.com.bora.entity.IntegracaoCanal;

import java.util.List;
import java.util.Map;

/**
 * Contrato comum aos marketplaces que a plataforma integra oficialmente.
 *
 * <p>iFood e Open Delivery (99Food) tem o mesmo ciclo — polling de eventos, acknowledgment,
 * detalhe do pedido, push de status —, mas NAO o mesmo contrato: rotas, nomes de campo,
 * autenticacao e corpo das acoes mudam. A primeira versao desta interface assumia que o Open
 * Delivery "espelhava" o iFood, e por isso a integracao do 99 lia campos que nao existem.
 * Cada leitura de evento agora e do proprio canal.</p>
 *
 * <p>A plataforma e a <b>integradora</b>: o aplicativo e nosso e vive em variavel de ambiente;
 * por loja guardamos o identificador da loja no canal e os tokens daquela loja.</p>
 */
public interface MarketplaceClient {

    /**
     * Status sintetico usado pelo poller no aceite automatico da importacao.
     *
     * <p>Precisa ser distinto de {@code CONFIRMADO}: aquele e o operador movendo o card depois,
     * quando o marketplace ja foi confirmado aqui — confirmar de novo e recusado pela API.</p>
     */
    String ACEITE_INICIAL = "ACEITE_INICIAL";

    /** Codigo do canal atendido por esta implementacao (IFOOD, NOVE_NOVE…). */
    String canal();

    /** Se o aplicativo da plataforma tem credenciais configuradas. Sem isso nada funciona. */
    boolean configurado();

    /**
     * Se ESTA loja tem como autenticar — pela credencial propria dela ou pela da plataforma.
     * Existe porque um canal pode aceitar que o lojista use o app dele (autoatendimento)
     * enquanto o credenciamento da plataforma como integradora ainda esta em analise.
     */
    default boolean configurado(IntegracaoCanal i) {
        return configurado();
    }

    /** Primeiro passo do vinculo de uma loja. Grava o estado na integracao. */
    Map<String, Object> iniciarVinculo(IntegracaoCanal i);

    /** Segundo passo (iFood): troca a autorizacao do lojista por tokens. */
    void concluirVinculo(IntegracaoCanal i, String autorizacao);

    /** Token de acesso valido, renovando quando estiver perto de vencer. */
    String tokenValido(IntegracaoCanal i);

    /** Eventos pendentes da loja. Roda a cada 30 segundos: e o que mantem a loja online. */
    List<Map<String, Object>> polling(IntegracaoCanal i);

    /**
     * Confirma o recebimento dos eventos. Recebe o evento INTEIRO, nao so o id: o Open Delivery
     * exige id, orderId e eventType no acknowledgment e recusa a lista so com ids.
     */
    void acknowledge(IntegracaoCanal i, List<Map<String, Object>> eventos);

    /** Detalhe completo do pedido — o evento traz so o id. */
    Map<String, Object> detalhePedido(IntegracaoCanal i, String orderId);

    /** Empurra a mudanca de status. Cada implementacao traduz o status interno para o verbo do canal. */
    void enviarStatus(IntegracaoCanal i, String orderId, String statusInterno);

    /**
     * Cancelamento carrega o MOTIVO, que o status sozinho nao transporta. iFood e Open Delivery
     * recusam cancelamento sem motivo, cada um com o seu formato.
     */
    default void enviarCancelamento(IntegracaoCanal i, String orderId, String motivo) {
        enviarStatus(i, orderId, "CANCELADO");
    }

    // ---------------------------------------------------------------- leitura dos eventos

    /** Identificador do evento. O iFood chama de "id"; o Open Delivery de "eventId". */
    default String eventoId(Map<String, Object> ev) {
        return texto(ev.get("id"));
    }

    default String tipoEvento(Map<String, Object> ev) {
        Object v = ev.get("code");
        if (v == null) v = ev.get("fullCode");
        if (v == null) v = ev.get("eventType");
        return texto(v);
    }

    default String pedidoDoEvento(Map<String, Object> ev) {
        Object v = ev.get("orderId");
        if (v == null) v = ev.get("correlationId");
        if (v == null) v = ev.get("resourceId");
        return texto(v);
    }

    /** Evento que traz um pedido novo para criar aqui dentro. */
    default boolean ehPedidoNovo(String tipo) {
        return tipo != null && List.of("PLACED", "CREATED", "ORDER_PLACED", "CONFIRMED").contains(tipo.toUpperCase());
    }

    /** Pedido cancelado do lado do marketplace — pelo cliente, pela plataforma ou confirmando o nosso. */
    default boolean ehCancelamento(String tipo) {
        return tipo != null && List.of("CANCELLED", "CAN").contains(tipo.toUpperCase());
    }

    /** O marketplace PERGUNTA se a loja aceita cancelar e espera resposta. Nem todo canal tem. */
    default boolean ehPedidoDeCancelamento(String tipo) {
        return false;
    }

    /** Motivo que o marketplace informou no cancelamento, pronto para o painel. */
    default String motivoDoCancelamento(Map<String, Object> ev) {
        return null;
    }

    /** Responde ao pedido de cancelamento feito pelo cliente. */
    default void responderPedidoDeCancelamento(IntegracaoCanal i, String orderId, boolean aceitar, String statusInterno) {
    }

    private static String texto(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
