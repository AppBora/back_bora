package br.com.bora.entity;

/**
 * Por que esta loja está com o acesso aberto.
 *
 * <p><b>Por que isto existe.</b> {@code acessoAte} nulo quer dizer "sem data de fim", e sozinho ele
 * não distingue dois casos que pedem tratamentos opostos quando o Asaas avisa que a assinatura
 * acabou:</p>
 *
 * <ul>
 *   <li>{@link #ASSINATURA} — quem paga pelo Asaas. Cancelou, <b>ganha prazo</b> e depois para. Sem
 *       isso a loja usaria o sistema de graça para sempre, porque o bloqueio só olha a data.</li>
 *   <li>{@link #PAGAMENTO_MANUAL} — pagou fora do Asaas (PIX na conta, dinheiro). O cancelamento da
 *       assinatura lá <b>não pode</b> encurtar nada: o dinheiro entrou.</li>
 * </ul>
 *
 * <p>Foi exatamente essa confusão que tirou a Zirá do ar em 15/10/2026: ela pagou R$ 398, o registro
 * manual deixou o acesso sem data de fim, e o {@code SUBSCRIPTION_DELETED} que chegou depois gravou
 * "hoje + carência" por cima. A guarda posta na v139 compara datas, e nulo não entra em comparação
 * nenhuma — então o acidente continuava possível.</p>
 *
 * <p>O segundo serviço desta coluna é contar a história: antes dela, quem abrisse o banco via uma
 * loja que pagou vencendo em 26/10 e não tinha como saber o porquê.</p>
 */
public enum MotivoDoAcesso {

    /** Assinatura viva no Asaas. É o Asaas quem manda no prazo. */
    ASSINATURA,

    /** Pagamento recebido fora do Asaas e registrado pela plataforma. Não é encurtado por webhook. */
    PAGAMENTO_MANUAL,

    /** Liberação feita à mão pelo administrador da plataforma, com motivo e teto de dias. */
    LIBERACAO,

    /** Cortesia de loja nova (os primeiros dias) ou carência depois de reativar. */
    CORTESIA,

    /** Prazo dado porque a cobrança atrasou ou foi devolvida. Vence e corta. */
    INADIMPLENCIA
}
