package br.com.bora.repository;
import br.com.bora.entity.ProdutoInsumo;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ProdutoInsumoRepository extends JpaRepository<ProdutoInsumo, Long> {
    List<ProdutoInsumo> findByLojaIdAndProdutoId(Long lojaId, Long produtoId);

    /** Em quais fichas este insumo esta. Para recusar a exclusao em vez de deixar ficha fantasma. */
    List<ProdutoInsumo> findByLojaIdAndInsumoId(Long lojaId, Long insumoId);
    void deleteByLojaIdAndProdutoId(Long lojaId, Long produtoId);
}
