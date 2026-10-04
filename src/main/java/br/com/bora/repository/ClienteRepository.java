package br.com.bora.repository;

import br.com.bora.entity.Cliente;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ClienteRepository extends JpaRepository<Cliente, Long> {
    List<Cliente> findByLojaIdOrderByNomeAsc(Long lojaId);

    /**
     * Só os clientes pedidos, dentro da loja.
     *
     * <p>O quadro de pedidos carregava a lista INTEIRA de clientes da loja a cada atualização, só para
     * achar o nome e o telefone de alguns cards. Com o painel recarregando a cada 6 segundos e uma
     * base de milhares de clientes, era a consulta mais cara do sistema — e crescia com o sucesso da
     * loja.</p>
     */
    List<Cliente> findByLojaIdAndIdIn(Long lojaId, java.util.Collection<Long> ids);
    Optional<Cliente> findByIdAndLojaId(Long id, Long lojaId);
    Optional<Cliente> findFirstByLojaIdAndTelefone(Long lojaId, String telefone);
    Optional<Cliente> findFirstByLojaIdAndCanalExternoAndIdExterno(Long lojaId, String canalExterno, String idExterno);
}
