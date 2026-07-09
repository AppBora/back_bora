package br.com.bora.repository;

import br.com.bora.entity.AcertoEntregador;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AcertoEntregadorRepository extends JpaRepository<AcertoEntregador, Long> {
    List<AcertoEntregador> findByLojaIdOrderByCriadoEmDesc(Long lojaId);
}
