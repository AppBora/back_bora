package br.com.bora.repository;

import br.com.bora.entity.PedidoItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PedidoItemRepository extends JpaRepository<PedidoItem, Long> {
    List<PedidoItem> findByLojaIdAndPedidoIdOrderById(Long lojaId, Long pedidoId);
    List<PedidoItem> findByLojaId(Long lojaId);
    List<PedidoItem> findByLojaIdAndPedidoIdIn(Long lojaId, java.util.Collection<Long> pedidoIds);
    void deleteByLojaIdAndPedidoId(Long lojaId, Long pedidoId);

    long countByLojaId(Long lojaId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from PedidoItem i where i.lojaId = :lojaId")
    int deleteByLojaId(@org.springframework.data.repository.query.Param("lojaId") Long lojaId);
}
