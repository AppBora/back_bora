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
}
