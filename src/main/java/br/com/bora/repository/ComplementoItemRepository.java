package br.com.bora.repository;

import br.com.bora.entity.ComplementoItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ComplementoItemRepository extends JpaRepository<ComplementoItem, Long> {
    List<ComplementoItem> findByLojaIdAndGrupoIdInOrderById(Long lojaId, Collection<Long> grupoIds);
    void deleteByLojaIdAndGrupoIdIn(Long lojaId, Collection<Long> grupoIds);
}
