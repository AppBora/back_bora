package br.com.bora.repository;

import br.com.bora.entity.Produto;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ProdutoRepository extends JpaRepository<Produto, Long> {
    List<Produto> findByLojaIdOrderByNomeAsc(Long lojaId);
    List<Produto> findByLojaIdAndAtivoTrueOrderByCategoriaAscNomeAsc(Long lojaId);
    Optional<Produto> findByIdAndLojaId(Long id, Long lojaId);

    /** Varios produtos de uma vez, para quem monta carrinho e nao quer uma consulta por linha. */
    java.util.List<Produto> findByLojaIdAndIdIn(Long lojaId, java.util.Collection<Long> ids);
    long countByLojaId(Long lojaId);

    /**
     * Baixa o estoque no banco, nao em Java.
     *
     * <p><b>O defeito que isto fecha:</b> a baixa era {@code prod.estoque = prod.estoque - qtd} seguido
     * de {@code save}. Duas vendas do mesmo item ao mesmo tempo — o balcao e o cardapio, ou dois
     * pedidos do cardapio no pico — leem o MESMO estoque antes de qualquer uma gravar, e as duas
     * gravam o mesmo numero: uma das baixas desaparece. O lojista vende o que nao tem.</p>
     *
     * <p>Aqui quem decide e o banco. So mexe em produto que controla estoque
     * ({@code estoque is not null}), igual a baixa antiga — quem nao controla continua sem numero em
     * vez de nascer com saldo inventado.</p>
     *
     * @return 1 se baixou, 0 se o produto nao controla estoque ou nao e desta loja
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Produto p set p.estoque = p.estoque - :qtd
            where p.id = :id and p.lojaId = :lojaId and p.estoque is not null""")
    int baixarEstoque(@org.springframework.data.repository.query.Param("id") Long id,
                      @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                      @org.springframework.data.repository.query.Param("qtd") int qtd);

    /** Devolve o estoque no banco, pelo mesmo motivo da baixa. Usada pelo EstornoDaVendaService. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Produto p set p.estoque = p.estoque + :qtd
            where p.id = :id and p.lojaId = :lojaId and p.estoque is not null""")
    int devolverEstoque(@org.springframework.data.repository.query.Param("id") Long id,
                        @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                        @org.springframework.data.repository.query.Param("qtd") int qtd);
}
