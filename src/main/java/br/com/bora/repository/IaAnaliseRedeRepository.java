package br.com.bora.repository;

import br.com.bora.entity.IaAnaliseRede;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface IaAnaliseRedeRepository extends JpaRepository<IaAnaliseRede, Long> {
    Optional<IaAnaliseRede> findByUsuarioIdAndDia(Long usuarioId, LocalDate dia);
}
