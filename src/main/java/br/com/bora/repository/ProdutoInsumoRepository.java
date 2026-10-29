package br.com.bora.repository;
import br.com.bora.entity.ProdutoInsumo;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ProdutoInsumoRepository extends JpaRepository<ProdutoInsumo, Long> {
    List<ProdutoInsumo> findByLojaIdAndProdutoId(Long lojaId, Long produtoId);

    /**
     * A ficha de VARIOS produtos numa consulta.
     *
     * <p>O cardapio publico marcava "esgotado" produto por produto, e cada chamada fazia uma
     * consulta de ficha mais uma por linha de ficha. Numa loja com 44 produtos eram 45+ idas ao
     * banco so para desenhar a tela -- e e a tela mais aberta do sistema.</p>
     */
    List<ProdutoInsumo> findByLojaIdAndProdutoIdIn(Long lojaId, java.util.Collection<Long> produtoIds);

    /** Em quais fichas este insumo esta. Para recusar a exclusao em vez de deixar ficha fantasma. */
    List<ProdutoInsumo> findByLojaIdAndInsumoId(Long lojaId, Long insumoId);
    void deleteByLojaIdAndProdutoId(Long lojaId, Long produtoId);
}
