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

    /**
     * Troca o saldo do insumo pelo valor informado, e NADA MAIS. Irmao do
     * {@code ProdutoRepository.definirEstoque}, e pelo mesmo motivo: um {@code save()} da entidade
     * grava a linha inteira, inclusive o que quem pediu o ajuste nem sabe que existe.
     *
     * <p>{@code valor} nulo quer dizer <b>para de controlar estoque</b>.</p>
     *
     * @return 1 se gravou, 0 se o insumo nao e desta loja
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Insumo i set i.estoque = :valor
            where i.id = :id and i.lojaId = :lojaId""")
    int definirEstoque(@org.springframework.data.repository.query.Param("id") Long id,
                       @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                       @org.springframework.data.repository.query.Param("valor") java.math.BigDecimal valor);

    /**
     * Soma ao saldo do insumo no banco, recusando descer abaixo de zero.
     *
     * <p>Nao e o {@link #mexerNoEstoque} de proposito: aquele e o consumo e a devolucao da ficha
     * tecnica, onde a devolucao de um cancelamento NUNCA pode ser recusada -- nem por saldo baixo, nem
     * por ficar negativa. Este e o ajuste a mao do lojista, onde ficar negativo nao faz sentido.</p>
     *
     * @return 1 se ajustou, 0 se o insumo nao controla estoque, nao e desta loja, ou o ajuste
     *         deixaria o saldo negativo
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Insumo i set i.estoque = i.estoque + :delta
            where i.id = :id and i.lojaId = :lojaId
              and i.estoque is not null and i.estoque + :delta >= 0""")
    int somarAoEstoque(@org.springframework.data.repository.query.Param("id") Long id,
                       @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                       @org.springframework.data.repository.query.Param("delta") java.math.BigDecimal delta);

    /**
     * Consome o insumo SO SE TIVER. O irmao do {@code ProdutoRepository.baixarEstoqueSeTiver}, e pelo
     * mesmo motivo: no cardapio publico recusar e a resposta certa.
     *
     * <p>A {@code qtd} vem POSITIVA aqui, diferente do {@link #mexerNoEstoque} — quem devolve estoque
     * usa o outro metodo. Separar os dois e de proposito: uma devolucao nunca pode ser recusada por
     * falta de saldo, e com um unico metodo com sinal essa regra ficaria escondida num {@code if}.</p>
     *
     * <p>Insumo sem numero de estoque devolve 1, igual ao produto: nao controlar estoque nao pode
     * impedir venda. Entao 0 quer dizer uma coisa so: controla e nao tem o suficiente.</p>
     *
     * @return 1 quando pode seguir; 0 quando falta
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Insumo i set i.estoque = i.estoque - :qtd
            where i.id = :id and i.lojaId = :lojaId
              and (i.estoque is null or i.estoque >= :qtd)""")
    int consumirSeTiver(@org.springframework.data.repository.query.Param("id") Long id,
                        @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                        @org.springframework.data.repository.query.Param("qtd") java.math.BigDecimal qtd);
}
