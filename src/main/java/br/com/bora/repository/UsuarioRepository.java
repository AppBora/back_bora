package br.com.bora.repository;

import br.com.bora.entity.Papel;
import br.com.bora.entity.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {
    Optional<Usuario> findByEmail(String email);
    List<Usuario> findByLojaId(Long lojaId);
    long countByLojaId(Long lojaId);
    long countByLojaIdAndAtivoTrue(Long lojaId);
    boolean existsByPapel(Papel papel);

    /** Usuários ativos por loja, para o painel de clientes (x/15 do plano). */
    @org.springframework.data.jpa.repository.Query("select u.lojaId, count(u) from Usuario u where u.ativo = true and u.lojaId is not null group by u.lojaId")
    List<Object[]> ativosPorLoja();
}
