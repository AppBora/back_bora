package br.com.bora.repository;

import br.com.bora.entity.PagamentoAssinatura;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface PagamentoAssinaturaRepository extends JpaRepository<PagamentoAssinatura, Long> {

    boolean existsByAsaasPaymentId(String asaasPaymentId);

    java.util.Optional<PagamentoAssinatura> findByAsaasPaymentId(String asaasPaymentId);

    /** Mensalidades recebidas dentro de um intervalo (usado pelo faturamento do mês). */
    List<PagamentoAssinatura> findByPagoEmGreaterThanEqualAndPagoEmLessThanOrderByPagoEmAsc(
            OffsetDateTime de, OffsetDateTime ate);
}
