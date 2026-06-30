package br.com.bora.repository;
import br.com.bora.entity.Insumo;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.Optional;
public interface InsumoRepository extends JpaRepository<Insumo, Long> {
    List<Insumo> findByLojaIdOrderByNomeAsc(Long lojaId);
    Optional<Insumo> findByIdAndLojaId(Long id, Long lojaId);
}
