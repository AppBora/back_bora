package br.com.bora.repository;

import br.com.bora.entity.PagamentoAssinatura;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface PagamentoAssinaturaRepository extends JpaRepository<PagamentoAssinatura, Long> {

    boolean existsByAsaasPaymentId(String asaasPaymentId);

    /** Para acrescentar a observacao do registro manual ao que acabou de ser gravado. */
    java.util.Optional<br.com.bora.entity.PagamentoAssinatura> findFirstByAsaasPaymentId(String asaasPaymentId);

    java.util.Optional<PagamentoAssinatura> findByAsaasPaymentId(String asaasPaymentId);

    /**
     * Mensalidades ja registradas para esta loja NESTE DIA.
     *
     * <p>Serve para distinguir os dois erros opostos do registro a mao: perder um pagamento de
     * verdade e contar um clique duplo duas vezes. Ver
     * {@code AssinaturaService.registrarPagamentoRecebidoForaDoAsaas}.</p>
     *
     * <p>Por intervalo, nao por igualdade de instante: a hora gravada depende de quem gravou -- o
     * registro manual usa o inicio do dia em Brasilia, e o webhook usa o {@code paymentDate} que o
     * Asaas mandou. Comparar instantes faria dois pagamentos do mesmo dia parecerem de dias
     * diferentes.</p>
     */
    List<PagamentoAssinatura> findByLojaIdAndPagoEmGreaterThanEqualAndPagoEmLessThan(
            Long lojaId, OffsetDateTime de, OffsetDateTime ate);

    /** Mensalidades recebidas dentro de um intervalo (usado pelo faturamento do mês). */
    List<PagamentoAssinatura> findByPagoEmGreaterThanEqualAndPagoEmLessThanOrderByPagoEmAsc(
            OffsetDateTime de, OffsetDateTime ate);
}
