package br.com.bora.repository;

import br.com.bora.entity.LogStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.OffsetDateTime;
import java.util.List;

public interface LogStatusRepository extends JpaRepository<LogStatus, Long> {
    List<LogStatus> findByLojaIdAndPedidoIdOrderByDataHoraAsc(Long lojaId, Long pedidoId);
    void deleteByLojaIdAndPedidoId(Long lojaId, Long pedidoId);

    /** Todos os logs de uma loja a partir de uma data — base do relatório de tempo por status. */
    List<LogStatus> findByLojaIdAndDataHoraGreaterThanEqualOrderByPedidoIdAscDataHoraAsc(
            Long lojaId, OffsetDateTime inicio);

    long countByLojaId(Long lojaId);

    /**
     * Quantas linhas de histórico foram gravadas por este usuário.
     *
     * <p>Existe para a prévia da exclusão de usuário: {@code log_status.usuario_id} <b>não tem FK</b>
     * (medido no V2), então apagar a conta não apaga nem bloqueia nada aqui — só deixa o id órfão, e
     * o histórico do pedido passa a mostrar quem mexeu sem nome. Contar antes é o que permite avisar
     * com número em vez de "tem certeza?".</p>
     */
    long countByUsuarioId(Long usuarioId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from LogStatus l where l.lojaId = :lojaId")
    int deleteByLojaId(@org.springframework.data.repository.query.Param("lojaId") Long lojaId);
}
