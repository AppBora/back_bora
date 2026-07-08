package br.com.bora.repository;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {

    /** Receita (não cancelada) da loja numa janela — base do termômetro de vendas. */
    @Query("select coalesce(sum(p.valorTotal), 0) from Pedido p " +
           "where p.lojaId = :lojaId and p.status <> br.com.bora.entity.StatusPedido.CANCELADO " +
           "and p.criadoEm >= :inicio and p.criadoEm < :fim")
    BigDecimal somaReceita(@Param("lojaId") Long lojaId,
                           @Param("inicio") OffsetDateTime inicio,
                           @Param("fim") OffsetDateTime fim);

    /** Pedidos válidos (não cancelados) da loja numa janela — balancete da rede. */
    @Query("select count(p) from Pedido p " +
           "where p.lojaId = :lojaId and p.status <> br.com.bora.entity.StatusPedido.CANCELADO " +
           "and p.criadoEm >= :inicio and p.criadoEm < :fim")
    long contaPedidosValidos(@Param("lojaId") Long lojaId,
                             @Param("inicio") OffsetDateTime inicio,
                             @Param("fim") OffsetDateTime fim);

    /** Pedidos cancelados da loja numa janela — balancete da rede. */
    @Query("select count(p) from Pedido p " +
           "where p.lojaId = :lojaId and p.status = br.com.bora.entity.StatusPedido.CANCELADO " +
           "and p.criadoEm >= :inicio and p.criadoEm < :fim")
    long contaPedidosCancelados(@Param("lojaId") Long lojaId,
                                @Param("inicio") OffsetDateTime inicio,
                                @Param("fim") OffsetDateTime fim);

    Optional<Pedido> findFirstByLojaIdAndClienteIdOrderByCriadoEmDesc(Long lojaId, Long clienteId);
    List<Pedido> findByLojaIdAndClienteIdAndCriadoEmAfter(Long lojaId, Long clienteId, OffsetDateTime corte);
    long countByLojaIdAndStatus(Long lojaId, StatusPedido status);
    long countByLojaIdAndEntregueEmAfter(Long lojaId, OffsetDateTime dt);
    List<Pedido> findByLojaIdOrderByCriadoEmDesc(Long lojaId);
    List<Pedido> findByLojaIdAndCriadoEmAfterOrderByCriadoEmDesc(Long lojaId, OffsetDateTime corte);
    Optional<Pedido> findByIdAndLojaId(Long id, Long lojaId);
    long countByLojaIdAndCriadoEmAfter(Long lojaId, OffsetDateTime inicio); // RN09 — limite de pedidos/mês
    Optional<Pedido> findFirstByLojaIdAndCanalExternoAndIdExterno(Long lojaId, String canalExterno, String idExterno); // idempotência webhook
}
