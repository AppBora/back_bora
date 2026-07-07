package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Vínculo usuário↔loja para donos de rede: permite trocar de loja com o mesmo login. */
@Entity
@Table(name = "usuario_loja")
@IdClass(UsuarioLoja.Pk.class)
@Getter
@Setter
public class UsuarioLoja {

    @Id
    @Column(name = "usuario_id")
    private Long usuarioId;

    @Id
    @Column(name = "loja_id")
    private Long lojaId;

    @Column(name = "criado_em")
    private OffsetDateTime criadoEm = OffsetDateTime.now();

    public static class Pk implements Serializable {
        private Long usuarioId;
        private Long lojaId;

        public Pk() {}
        public Pk(Long usuarioId, Long lojaId) { this.usuarioId = usuarioId; this.lojaId = lojaId; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(usuarioId, pk.usuarioId) && Objects.equals(lojaId, pk.lojaId);
        }
        @Override public int hashCode() { return Objects.hash(usuarioId, lojaId); }
    }
}
