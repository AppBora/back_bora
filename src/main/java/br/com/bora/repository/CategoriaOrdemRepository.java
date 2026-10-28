package br.com.bora.repository;
import br.com.bora.entity.CategoriaOrdem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface CategoriaOrdemRepository extends JpaRepository<CategoriaOrdem, Long> {
    List<CategoriaOrdem> findByLojaIdOrderByPosicaoAsc(Long lojaId);
    void deleteByLojaId(Long lojaId);
}
