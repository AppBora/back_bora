package br.com.bora.repository;

import br.com.bora.entity.AcertoEntregador;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AcertoEntregadorRepository extends JpaRepository<AcertoEntregador, Long> {
    List<AcertoEntregador> findByLojaIdOrderByCriadoEmDesc(Long lojaId);

    long countByLojaId(Long lojaId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from AcertoEntregador a where a.lojaId = :lojaId")
    int deleteByLojaId(@org.springframework.data.repository.query.Param("lojaId") Long lojaId);
}
