package br.com.bora.repository;

import br.com.bora.entity.Promocao;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface PromocaoRepository extends JpaRepository<Promocao, Long> {
    List<Promocao> findByLojaIdOrderByInicioDesc(Long lojaId);
    boolean existsByLojaIdAndAtivaTrue(Long lojaId);
    long countByLojaIdAndOrigemAndInicioAfter(Long lojaId, String origem, OffsetDateTime depois);
    List<Promocao> findByAtivaTrueAndFimBefore(OffsetDateTime momento);
}
