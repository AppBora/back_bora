package br.com.bora.repository;

import br.com.bora.entity.IaRecuperacao;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface IaRecuperacaoRepository extends JpaRepository<IaRecuperacao, Long> {
    List<IaRecuperacao> findByLojaIdAndEnviadoEmAfter(Long lojaId, OffsetDateTime corte);
    boolean existsByLojaIdAndClienteIdAndEnviadoEmAfter(Long lojaId, Long clienteId, OffsetDateTime corte);
}
