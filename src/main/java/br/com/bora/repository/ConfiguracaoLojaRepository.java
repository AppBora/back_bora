package br.com.bora.repository;

import br.com.bora.entity.ConfiguracaoLoja;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ConfiguracaoLojaRepository extends JpaRepository<ConfiguracaoLoja, Long> {
    Optional<ConfiguracaoLoja> findByLojaId(Long lojaId);
}
