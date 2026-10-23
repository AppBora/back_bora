package br.com.bora.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Um cliente da plataforma como o ADMINISTRADOR_BORA precisa vê-lo para cobrar e dar suporte.
 * Só campos que existem de verdade — nada estimado.
 *
 * @param situacao ARQUIVADA · SUSPENSA (decisão da plataforma) · SEM_PAGAMENTO (o Asaas derrubou,
 *                 volta sozinha quando o cliente paga) · ATIVA
 */
public record ClienteView(
        Long id,
        String nome,
        String documento,
        String plano,
        BigDecimal precoMensal,
        boolean precoNegociado,
        Boolean moduloIa,
        BigDecimal splitPercentual,
        String situacao,
        String motivo,
        String assinatura,
        long usuariosAtivos,
        int maxUsuarios,
        OffsetDateTime criadoEm,
        OffsetDateTime ultimoPedidoEm,
        /**
         * Ate quando esta loja pode usar o sistema. Nulo = sem data de fim (quem esta pagando).
         *
         * <p>Faltava na tela, e e o numero que decide se a loja sai do ar. Sem ele, "liberar acesso"
         * seria um botao as cegas: nao da para escolher uma data nova sem saber qual e a atual.</p>
         */
        OffsetDateTime acessoAte,
        Long empresaId,
        String empresaNome) {
}
