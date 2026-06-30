package br.com.bora.repository;
import br.com.bora.entity.MotivoCancelamento;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.Optional;
public interface MotivoCancelamentoRepository extends JpaRepository<MotivoCancelamento, Long> {
    List<MotivoCancelamento> findByLojaIdOrderByDescricaoAsc(Long lojaId);
    Optional<MotivoCancelamento> findByIdAndLojaId(Long id, Long lojaId);
}
