package br.com.bora.repository;

import br.com.bora.entity.IntegracaoCanal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface IntegracaoCanalRepository extends JpaRepository<IntegracaoCanal, Long> {
    List<IntegracaoCanal> findByLojaIdOrderByCanalAsc(Long lojaId);
    Optional<IntegracaoCanal> findByLojaIdAndCanal(Long lojaId, String canal);
    Optional<IntegracaoCanal> findByIdAndLojaId(Long id, Long lojaId);
    List<IntegracaoCanal> findByAtivoTrue();
}
