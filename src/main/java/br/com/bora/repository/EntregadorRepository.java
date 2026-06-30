package br.com.bora.repository;

import br.com.bora.entity.Entregador;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface EntregadorRepository extends JpaRepository<Entregador, Long> {
    List<Entregador> findByLojaIdOrderByNomeAsc(Long lojaId);
    Optional<Entregador> findByIdAndLojaId(Long id, Long lojaId);
}
