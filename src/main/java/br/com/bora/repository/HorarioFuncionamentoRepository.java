package br.com.bora.repository;
import br.com.bora.entity.HorarioFuncionamento;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface HorarioFuncionamentoRepository extends JpaRepository<HorarioFuncionamento, Long> {
    List<HorarioFuncionamento> findByLojaIdOrderByDiaAsc(Long lojaId);
}
