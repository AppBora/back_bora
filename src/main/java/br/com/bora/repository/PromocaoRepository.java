package br.com.bora.repository;

import br.com.bora.entity.Promocao;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface PromocaoRepository extends JpaRepository<Promocao, Long> {
    List<Promocao> findByLojaIdOrderByInicioDesc(Long lojaId);
    Optional<Promocao> findByIdAndLojaId(Long id, Long lojaId);
    boolean existsByLojaIdAndAtivaTrue(Long lojaId);
    long countByLojaIdAndOrigemAndInicioAfter(Long lojaId, String origem, OffsetDateTime depois);
    List<Promocao> findByAtivaTrueAndFimBefore(OffsetDateTime momento);

    /** O codigo que o cliente digita no checkout. A busca ignora caixa, como a do cupom. */
    Optional<Promocao> findByLojaIdAndCodigoIgnoreCase(Long lojaId, String codigo);

    /**
     * Soma um uso, mas so enquanto houver vaga no teto.
     *
     * <p>Era "le, soma e grava" em Java. Dez pedidos ao mesmo tempo liam o mesmo contador antes de
     * qualquer um gravar, e o teto de 20 usos que o botao do Dashboard promete ao lojista nao seria 20.
     * Aqui quem decide e o banco, e quem perder a corrida sabe pelo numero de linhas afetadas. Mesmo
     * padrao do {@code PedidoRepository.amarrarAoAcerto}.</p>
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update Promocao p set p.usos = coalesce(p.usos, 0) + 1 where p.id = :id "
            + "and (p.limiteUsos is null or p.limiteUsos <= 0 or coalesce(p.usos, 0) < p.limiteUsos)")
    int somarUmUso(@org.springframework.data.repository.query.Param("id") Long id);
}
