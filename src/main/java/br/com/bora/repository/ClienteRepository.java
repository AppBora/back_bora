package br.com.bora.repository;

import br.com.bora.entity.Cliente;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ClienteRepository extends JpaRepository<Cliente, Long> {
    List<Cliente> findByLojaIdOrderByNomeAsc(Long lojaId);

    /**
     * Só os clientes pedidos, dentro da loja.
     *
     * <p>O quadro de pedidos carregava a lista INTEIRA de clientes da loja a cada atualização, só para
     * achar o nome e o telefone de alguns cards. Com o painel recarregando a cada 6 segundos e uma
     * base de milhares de clientes, era a consulta mais cara do sistema — e crescia com o sucesso da
     * loja.</p>
     */
    List<Cliente> findByLojaIdAndIdIn(Long lojaId, java.util.Collection<Long> ids);
    Optional<Cliente> findByIdAndLojaId(Long id, Long lojaId);
    Optional<Cliente> findFirstByLojaIdAndTelefone(Long lojaId, String telefone);
    Optional<Cliente> findFirstByLojaIdAndCanalExternoAndIdExterno(Long lojaId, String canalExterno, String idExterno);

    long countByLojaId(Long lojaId);

    long countByLojaIdAndCashbackGreaterThan(Long lojaId, java.math.BigDecimal valor);

    java.util.List<br.com.bora.entity.Cliente> findByLojaId(Long lojaId);

    // ------------------------------------------------------------------ o saldo de cashback
    //
    // TODA mexida no saldo e no BANCO, nao em Java. Era ler-somar-gravar
    // (`findByIdAndLojaId` + `save`), e dois checkouts do mesmo cliente ao mesmo tempo liam o mesmo
    // saldo antes de qualquer um gravar: o mesmo cashback valia nos dois pedidos. E o defeito que o
    // estoque ja teve (ver ProdutoRepository.baixarEstoque), agora no dinheiro do cliente.
    //
    // O caso realista nao e nem dois aparelhos: e o cliente clicando duas vezes em "confirmar".

    /**
     * Debita o resgate SO SE O SALDO COBRIR, e no mesmo movimento registra a venda.
     *
     * <p>Uma instrucao: debito do resgate, credito do cashback novo, total gasto e contagem de
     * pedidos. Em Java isso eram quatro leituras e uma gravacao, e qualquer coisa que acontecesse no
     * meio se perdia.</p>
     *
     * <p>A clausula {@code cashback >= :usado} e o que impede gastar o mesmo saldo duas vezes. Quem
     * chama tem que olhar o retorno: 0 significa que o saldo NAO cobria, e o pedido que contava com o
     * desconto nao pode ser gravado.</p>
     *
     * @return 1 se gravou, 0 se o saldo nao cobria o resgate (ou o cliente nao e desta loja)
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Cliente c
               set c.cashback = c.cashback - :usado + :credito,
                   c.totalGasto = coalesce(c.totalGasto, 0) + :pago,
                   c.qtdPedidos = coalesce(c.qtdPedidos, 0) + 1,
                   c.ultimoPedido = :agora
             where c.id = :id and c.lojaId = :lojaId
               and coalesce(c.cashback, 0) >= :usado""")
    int registrarVenda(@org.springframework.data.repository.query.Param("id") Long id,
                       @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                       @org.springframework.data.repository.query.Param("pago") java.math.BigDecimal pago,
                       @org.springframework.data.repository.query.Param("usado") java.math.BigDecimal usado,
                       @org.springframework.data.repository.query.Param("credito") java.math.BigDecimal credito,
                       @org.springframework.data.repository.query.Param("agora") java.time.OffsetDateTime agora);

    /**
     * Debita do saldo SO SE COBRIR. Usado pelo resgate do PIX pendente, que debita na hora.
     *
     * @return 1 se debitou, 0 se o saldo nao cobria
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Cliente c set c.cashback = c.cashback - :valor
             where c.id = :id and c.lojaId = :lojaId and coalesce(c.cashback, 0) >= :valor""")
    int debitarSeTiver(@org.springframework.data.repository.query.Param("id") Long id,
                       @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                       @org.springframework.data.repository.query.Param("valor") java.math.BigDecimal valor);

    /**
     * Credita no saldo. NUNCA e condicional: devolver cashback de venda cancelada nao pode ser
     * recusado -- foi a venda que nao aconteceu.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Cliente c set c.cashback = coalesce(c.cashback, 0) + :valor
             where c.id = :id and c.lojaId = :lojaId""")
    int creditar(@org.springframework.data.repository.query.Param("id") Long id,
                 @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                 @org.springframework.data.repository.query.Param("valor") java.math.BigDecimal valor);

    /**
     * Desfaz a venda: devolve o resgate, tira o cashback que ela gerou, e volta o historico.
     *
     * <p>O {@code greatest} impede saldo negativo quando o cashback creditado ja foi gasto em outro
     * pedido. Era um {@code .max(ZERO)} em Java, que so funcionava se mais ninguem mexesse no saldo
     * no meio.</p>
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = """
            update cliente
               set cashback = greatest(0, coalesce(cashback, 0) + :usado - :credito),
                   total_gasto = greatest(0, coalesce(total_gasto, 0) - :pago),
                   qtd_pedidos = greatest(0, coalesce(qtd_pedidos, 0) - :pedidos)
             where id = :id and loja_id = :lojaId""", nativeQuery = true)
    int desfazerVenda(@org.springframework.data.repository.query.Param("id") Long id,
                      @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                      @org.springframework.data.repository.query.Param("pago") java.math.BigDecimal pago,
                      @org.springframework.data.repository.query.Param("usado") java.math.BigDecimal usado,
                      @org.springframework.data.repository.query.Param("credito") java.math.BigDecimal credito,
                      @org.springframework.data.repository.query.Param("pedidos") int pedidos);
}
