package br.com.bora.repository;

import br.com.bora.entity.LogStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LogStatusRepository extends JpaRepository<LogStatus, Long> {
    List<LogStatus> findByLojaIdAndPedidoIdOrderByDataHoraAsc(Long lojaId, Long pedidoId);
}
