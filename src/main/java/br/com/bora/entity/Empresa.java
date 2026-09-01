package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * A pessoa jurídica dona de uma ou mais lojas (ex.: um CNPJ com três unidades).
 *
 * Não confundir com {@link UsuarioLoja}: empresa é fato societário, o vínculo é controle de acesso.
 * A empresa é a fonte de verdade que permite recusar um vínculo para a loja de outro cliente.
 * Ela NÃO entra no token nem nas consultas de dados — o isolamento continua todo por lojaId.
 */
@Entity
@Getter
@Setter
public class Empresa {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "razao_social", nullable = false)
    public String razaoSocial;

    /** Só dígitos. Único quando informado; nulo quando a loja foi criada sem documento. */
    public String cnpj;

    @Column(name = "criado_em")
    public java.time.OffsetDateTime criadoEm = java.time.OffsetDateTime.now();
}
