package br.com.bora.dto;

import br.com.bora.entity.Assinatura;
import br.com.bora.service.AsaasClient;

import java.math.BigDecimal;

/**
 * Assinatura exposta na API — sem os IDs internos do gateway (Asaas).
 *
 * <p>Leva o LINK da fatura em aberto quando ha uma. Sem isso, a unica acao que a tela de Planos
 * oferecia ao lojista inadimplente era "Reativar assinatura" — e era justamente ela que apagava a
 * fatura atrasada e abria outra cortesia de 7 dias. Ver {@code AssinaturaService.assinar}.</p>
 *
 * <p>O {@code asaasSubscriptionId} continua fora daqui de proposito: e id de gateway, nao serve a
 * nenhuma tela, e o {@code faturaLink} ja da ao lojista tudo que ele precisa para pagar.</p>
 */
public record AssinaturaView(Long id, String plano, String status, BigDecimal valor,
                             /** Link de pagamento da fatura em aberto, ou null quando nao ha. */
                             String faturaLink,
                             /** Vencimento da fatura em aberto (aaaa-mm-dd), ou null. */
                             String faturaVencimento,
                             /** Valor da fatura em aberto, ou null. */
                             Double faturaValor,
                             /**
                              * Ha cobranca em duplicidade no Asaas por um cancelamento que falhou?
                              * A tela avisa o lojista em vez de ele descobrir no extrato.
                              */
                             boolean cobrancaDuplicada) {

    public static AssinaturaView de(Assinatura a) {
        return de(a, null);
    }

    public static AssinaturaView de(Assinatura a, AsaasClient.FaturaAberta fatura) {
        if (a == null) return null;
        return new AssinaturaView(
                a.getId(),
                a.getPlano() == null ? null : a.getPlano().name(),
                a.getStatus() == null ? null : a.getStatus().name(),
                a.getValor(),
                fatura == null ? null : fatura.link(),
                fatura == null ? null : fatura.vencimento(),
                fatura == null ? null : fatura.valor(),
                a.getAsaasSubscriptionAnterior() != null);
    }
}
