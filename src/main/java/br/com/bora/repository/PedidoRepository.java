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
    // PIX gerado e nao pago nao e faturamento: entrava na conta do dia e ficava la para sempre,
    // porque nada expirava o pedido abandonado no checkout.
    @Query("select coalesce(sum(p.valorTotal), 0) from Pedido p " +
           "where p.lojaId = :lojaId and p.status <> br.com.bora.entity.StatusPedido.CANCELADO " +
           "and (p.aguardandoPagamento is null or p.aguardandoPagamento = false) " +
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

    /** Acerto de entregadores: soma por entregador das entregas realizadas ainda não acertadas, na janela. */
    @Query("select p.entregador, count(p), coalesce(sum(p.taxaEntrega), 0), " +
           "coalesce(sum(case when upper(coalesce(p.formaPagamento, '')) like '%DINHEIRO%' then p.valorTotal else 0 end), 0), " +
           "coalesce(sum(p.valorTotal), 0) " +
           "from Pedido p where p.lojaId = :lojaId " +
           "and p.status = br.com.bora.entity.StatusPedido.ENTREGUE and p.acertoId is null " +
           "and p.entregador is not null and p.entregador <> '' " +
           "and p.entregueEm >= :inicio and p.entregueEm < :fim " +
           "group by p.entregador order by p.entregador")
    List<Object[]> previaAcerto(@Param("lojaId") Long lojaId,
                                @Param("inicio") OffsetDateTime inicio,
                                @Param("fim") OffsetDateTime fim);

    /** Entregas de um entregador (não canceladas/acertadas) na janela — base do "fazer acerto". */
    // O nome do entregador e texto livre digitado a cada pedido: "Joao", "joao " e "JOAO" eram tres
    // entregadores diferentes, e o acerto de um nao achava as entregas do outro.
    @Query("select p from Pedido p where p.lojaId = :lojaId " +
           "and p.status = br.com.bora.entity.StatusPedido.ENTREGUE and p.acertoId is null " +
           "and lower(trim(p.entregador)) = lower(trim(:entregador)) " +
           "and p.entregueEm >= :inicio and p.entregueEm < :fim")
    List<Pedido> entregasParaAcerto(@Param("lojaId") Long lojaId,
                                    @Param("entregador") String entregador,
                                    @Param("inicio") OffsetDateTime inicio,
                                    @Param("fim") OffsetDateTime fim);

    /**
     * Amarra as entregas ao acerto, mas so as que ainda estao livres.
     *
     * <p>Antes isto era um save() comum: dois acertos abertos ao mesmo tempo (duas abas, duas pessoas)
     * liam as mesmas entregas e os dois gravavam, entao o entregador podia ser pago duas vezes pelas
     * mesmas corridas. Aqui o banco decide quem chegou primeiro, e quem perder sabe disso pelo numero
     * de linhas afetadas.</p>
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Pedido p set p.acertoId = :acertoId where p.id in :ids and p.acertoId is null")
    int amarrarAoAcerto(@Param("acertoId") Long acertoId, @Param("ids") java.util.Collection<Long> ids);

    Optional<Pedido> findFirstByLojaIdAndClienteIdOrderByCriadoEmDesc(Long lojaId, Long clienteId);
    List<Pedido> findByLojaIdAndClienteIdAndCriadoEmAfter(Long lojaId, Long clienteId, OffsetDateTime corte);
    long countByLojaIdAndStatus(Long lojaId, StatusPedido status);
    long countByLojaIdAndEntregueEmAfter(Long lojaId, OffsetDateTime dt);
    List<Pedido> findByLojaIdOrderByCriadoEmDesc(Long lojaId);

    /** Pedidos de um dia só — o quadro carrega o dia corrente, não a loja inteira desde a abertura. */
    List<Pedido> findByLojaIdAndCriadoEmGreaterThanEqualAndCriadoEmLessThanOrderByCriadoEmDesc(
            Long lojaId, OffsetDateTime inicio, OffsetDateTime fim);

    /** Pedido que virou o dia sem terminar continua no quadro — senão some da vista da cozinha. */
    List<Pedido> findByLojaIdAndStatusNotInOrderByCriadoEmDesc(Long lojaId, java.util.Collection<StatusPedido> finais);
    List<Pedido> findByLojaIdAndCriadoEmAfterOrderByCriadoEmDesc(Long lojaId, OffsetDateTime corte);

    /** PIX gerado e nunca pago, mais velho que o corte — o cobrador cancela estes. */
    List<Pedido> findByAguardandoPagamentoTrueAndCriadoEmBefore(OffsetDateTime corte);
    Optional<Pedido> findByIdAndLojaId(Long id, Long lojaId);
    long countByLojaIdAndCriadoEmAfter(Long lojaId, OffsetDateTime inicio); // RN09 — limite de pedidos/mês
    Optional<Pedido> findFirstByLojaIdAndCanalExternoAndIdExterno(Long lojaId, String canalExterno, String idExterno); // idempotência webhook

    /** Último pedido de cada loja, para o painel de clientes da plataforma. */
    @Query("select p.lojaId, max(p.criadoEm) from Pedido p group by p.lojaId")
    java.util.List<Object[]> ultimoPedidoPorLoja();
}
