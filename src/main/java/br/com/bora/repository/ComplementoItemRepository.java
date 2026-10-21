package br.com.bora.repository;

import br.com.bora.entity.ComplementoItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ComplementoItemRepository extends JpaRepository<ComplementoItem, Long> {
    List<ComplementoItem> findByLojaIdAndGrupoIdInOrderById(Long lojaId, Collection<Long> grupoIds);
    void deleteByLojaIdAndGrupoIdIn(Long lojaId, Collection<Long> grupoIds);

    /**
     * Os adicionais que consomem este insumo. Serve para a recusa do {@code InsumoService.excluir}:
     * apagar insumo em uso deixava a ficha apontando para o nada (item 2.5), e agora o adicional e
     * um segundo lugar onde isso poderia acontecer.
     */
    List<ComplementoItem> findByLojaIdAndInsumoId(Long lojaId, Long insumoId);
}
