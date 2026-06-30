package br.com.bora.repository;
import br.com.bora.entity.FormaPagamento;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.Optional;
public interface FormaPagamentoRepository extends JpaRepository<FormaPagamento, Long> {
    List<FormaPagamento> findByLojaIdOrderByOrdemAscDescricaoAsc(Long lojaId);
    Optional<FormaPagamento> findByIdAndLojaId(Long id, Long lojaId);
    long countByLojaId(Long lojaId);
}
