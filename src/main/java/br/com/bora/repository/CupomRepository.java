package br.com.bora.repository;

import br.com.bora.entity.Cupom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CupomRepository extends JpaRepository<Cupom, Long> {
    List<Cupom> findByLojaIdOrderByCodigoAsc(Long lojaId);
    Optional<Cupom> findByLojaIdAndCodigoIgnoreCase(Long lojaId, String codigo);
    Optional<Cupom> findByIdAndLojaId(Long id, Long lojaId);
}
