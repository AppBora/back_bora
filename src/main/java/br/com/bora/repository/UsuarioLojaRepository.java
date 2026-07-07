package br.com.bora.repository;

import br.com.bora.entity.UsuarioLoja;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UsuarioLojaRepository extends JpaRepository<UsuarioLoja, UsuarioLoja.Pk> {
    List<UsuarioLoja> findByUsuarioId(Long usuarioId);
    boolean existsByUsuarioIdAndLojaId(Long usuarioId, Long lojaId);
}
