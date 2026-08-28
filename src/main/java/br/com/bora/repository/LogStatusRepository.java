package br.com.bora.repository;

import br.com.bora.entity.LogStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.OffsetDateTime;
import java.util.List;

public interface LogStatusRepository extends JpaRepository<LogStatus, Long> {
    List<LogStatus> findByLojaIdAndPedidoIdOrderByDataHoraAsc(Long lojaId, Long pedidoId);

    /** Todos os logs de uma loja a partir de uma data — base do relatório de tempo por status. */
    List<LogStatus> findByLojaIdAndDataHoraGreaterThanEqualOrderByPedidoIdAscDataHoraAsc(
            Long lojaId, OffsetDateTime inicio);
}
