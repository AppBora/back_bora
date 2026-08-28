package br.com.bora.service.marketplace;

import br.com.bora.entity.IntegracaoCanal;

import java.util.List;
import java.util.Map;

/**
 * Contrato comum aos marketplaces que a plataforma integra oficialmente.
 *
 * <p>O iFood e o padrão Open Delivery (99Food, Keeta) têm o mesmo desenho — o Open Delivery
 * foi especificado espelhando a API do iFood —, então o motor de recebimento é um só e o que
 * muda é a autenticação e o nome dos campos. Cada implementação resolve essas diferenças.</p>
 *
 * <p>Em todos eles a plataforma é a <b>integradora</b>: o aplicativo (clientId/clientSecret)
 * é nosso e vive em variável de ambiente; por loja guardamos apenas o merchantId e os tokens
 * que aquele lojista autorizou.</p>
 */
public interface MarketplaceClient {

    /**
     * Status sintético usado pelo poller no aceite automático da importação.
     *
     * <p>Precisa ser distinto de {@code CONFIRMADO}: aquele é o operador movendo o card depois,
     * quando o marketplace já foi confirmado aqui — confirmar de novo é recusado pela API.</p>
     */
    String ACEITE_INICIAL = "ACEITE_INICIAL";

    /** Código do canal atendido por esta implementação (IFOOD, NOVE_NOVE…). */
    String canal();

    /** Se o aplicativo da plataforma tem credenciais configuradas. Sem isso nada funciona. */
    boolean configurado();

    /**
     * Primeiro passo do vínculo de uma loja. Devolve ao painel o que o lojista precisa fazer
     * (no iFood, o código que ele digita no Portal do Parceiro). Grava o estado na integração.
     */
    Map<String, Object> iniciarVinculo(IntegracaoCanal i);

    /** Segundo passo: troca a autorização do lojista por accessToken + refreshToken. */
    void concluirVinculo(IntegracaoCanal i, String autorizacao);

    /** Token de acesso válido, renovando pelo refreshToken quando estiver perto de vencer. */
    String tokenValido(IntegracaoCanal i);

    /**
     * Consulta os eventos pendentes da loja. Precisa rodar a cada 30 segundos: no iFood a loja
     * só aparece como <b>online</b> enquanto o polling acontece.
     */
    List<Map<String, Object>> polling(IntegracaoCanal i);

    /** Confirma o recebimento dos eventos. Sem isso o marketplace reenvia tudo indefinidamente. */
    void acknowledge(IntegracaoCanal i, List<String> eventIds);

    /** Detalhe completo do pedido — o evento traz só o id. */
    Map<String, Object> detalhePedido(IntegracaoCanal i, String orderId);

    /**
     * Empurra a mudança de status para o marketplace.
     * Recebe o status interno do BoraHapp; cada implementação traduz para o verbo do canal.
     */
    void enviarStatus(IntegracaoCanal i, String orderId, String statusInterno);
}
