package br.com.bora.repository;

import br.com.bora.entity.PedidoItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PedidoItemRepository extends JpaRepository<PedidoItem, Long> {
    List<PedidoItem> findByLojaIdAndPedidoIdOrderById(Long lojaId, Long pedidoId);
    List<PedidoItem> findByLojaId(Long lojaId);
}
