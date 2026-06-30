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
    boolean existsByPapel(Papel papel);
}
