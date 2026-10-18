package br.com.bora.repository;

import br.com.bora.entity.PedidoDeSenha;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface PedidoDeSenhaRepository extends JpaRepository<PedidoDeSenha, Long> {

    /** O que o administrador daquela loja ainda nao atendeu, do mais novo para o mais velho. */
    List<PedidoDeSenha> findByLojaIdAndAtendidoEmIsNullOrderByCriadoEmDesc(Long lojaId);

    /** Pedido em aberto da MESMA pessoa: o segundo clique nao vira uma segunda linha. */
    java.util.Optional<PedidoDeSenha> findFirstByUsuarioIdAndAtendidoEmIsNull(Long usuarioId);

    /** Fecha tudo que estava em aberto para a pessoa cuja senha acabou de ser redefinida. */
    List<PedidoDeSenha> findByUsuarioIdAndAtendidoEmIsNull(Long usuarioId);

    /** Quantos pedidos essa pessoa abriu na janela — trava quem fica clicando. */
    long countByUsuarioIdAndCriadoEmAfter(Long usuarioId, OffsetDateTime desde);
}
