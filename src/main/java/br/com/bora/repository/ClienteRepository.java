package br.com.bora.repository;

import br.com.bora.entity.Cliente;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ClienteRepository extends JpaRepository<Cliente, Long> {
    List<Cliente> findByLojaIdOrderByNomeAsc(Long lojaId);
    Optional<Cliente> findByIdAndLojaId(Long id, Long lojaId);
    Optional<Cliente> findFirstByLojaIdAndTelefone(Long lojaId, String telefone);
}
