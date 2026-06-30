package br.com.bora.repository;
import br.com.bora.entity.TaxaEntrega;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.Optional;
public interface TaxaEntregaRepository extends JpaRepository<TaxaEntrega, Long> {
    List<TaxaEntrega> findByLojaIdOrderByBairroAsc(Long lojaId);
    Optional<TaxaEntrega> findByIdAndLojaId(Long id, Long lojaId);
    Optional<TaxaEntrega> findFirstByLojaIdAndBairroIgnoreCaseAndAtivoTrue(Long lojaId, String bairro);
}
