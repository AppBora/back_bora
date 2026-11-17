package br.com.bora.repository;

import br.com.bora.entity.LicencaModulo;
import br.com.bora.entity.Modulo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LicencaModuloRepository extends JpaRepository<LicencaModulo, Long> {

    Optional<LicencaModulo> findByLojaIdAndModulo(Long lojaId, Modulo modulo);

}
