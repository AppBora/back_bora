package br.com.bora.entity;

/** Situação da assinatura da loja no gateway de cobrança. */
public enum StatusAssinatura {
    PENDENTE,       // criada, aguardando 1º pagamento
    ATIVA,          // em dia
    INADIMPLENTE,   // pagamento em atraso
    CANCELADA       // encerrada
}
