package br.com.bora.repository;
import br.com.bora.entity.Insumo;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.Optional;
public interface InsumoRepository extends JpaRepository<Insumo, Long> {
    List<Insumo> findByLojaIdOrderByNomeAsc(Long lojaId);
    Optional<Insumo> findByIdAndLojaId(Long id, Long lojaId);

    /**
     * Mexe no estoque do insumo no banco, nao em Java.
     *
     * <p>Mesmo motivo do {@code ProdutoRepository.baixarEstoque}: era ler, subtrair e gravar, e duas
     * vendas simultaneas do mesmo produto com ficha tecnica perdiam uma das baixas. Aqui a conta e do
     * banco. A quantidade vem com sinal: negativa consome, positiva devolve.</p>
     *
     * @return 1 se mexeu, 0 se o insumo nao controla estoque ou nao e desta loja
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Insumo i set i.estoque = i.estoque + :delta
            where i.id = :id and i.lojaId = :lojaId and i.estoque is not null""")
    int mexerNoEstoque(@org.springframework.data.repository.query.Param("id") Long id,
                       @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                       @org.springframework.data.repository.query.Param("delta") java.math.BigDecimal delta);
}
