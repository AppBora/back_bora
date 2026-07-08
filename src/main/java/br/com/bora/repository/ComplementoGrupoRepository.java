package br.com.bora.repository;

import br.com.bora.entity.ComplementoGrupo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ComplementoGrupoRepository extends JpaRepository<ComplementoGrupo, Long> {
    List<ComplementoGrupo> findByLojaIdAndProdutoIdOrderById(Long lojaId, Long produtoId);
    List<ComplementoGrupo> findByLojaIdAndProdutoIdInOrderById(Long lojaId, Collection<Long> produtoIds);
    void deleteByLojaIdAndProdutoId(Long lojaId, Long produtoId);
}
