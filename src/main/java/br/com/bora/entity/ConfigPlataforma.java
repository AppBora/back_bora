package br.com.bora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** Configuração global da plataforma (chave/valor) — só o ADMINISTRADOR_BORA lê e altera. */
@Entity
@Table(name = "config_plataforma")
@Getter
@Setter
public class ConfigPlataforma {

    @Id
    private String chave;

    /**
     * O valor da configuração — cifrado em repouso, como as outras credenciais de terceiros.
     *
     * <h2>Item 8.4 da varredura</h2>
     * <p>Esta tabela guarda o {@code client_secret} do iFood e da 99 <b>da plataforma</b> — as
     * credenciais que autorizam pedidos em nome de <i>todas</i> as lojas. Elas estavam em texto puro
     * no banco, e saem em todo {@code pg_dump}. O cifrador ({@link br.com.bora.config.SegredoGuardado})
     * já existia desde a v114 e cobria a chave Asaas da loja e os tokens de marketplace;
     * <b>esta tabela ficou de fora</b>, e era a que tem o segredo de maior alcance.</p>
     *
     * <p>Cifrar tudo nesta tabela, e não só o que parece segredo, é de propósito: ela é chave/valor e
     * amanhã alguém guarda outra credencial aqui. O cifrador devolve texto puro quando a chave não
     * está ligada e decide pelo prefixo na leitura, então valor antigo continua legível e valor
     * não-secreto (como {@code fiscal.habilitado}) só fica ilegível a olho nu dentro do banco — o que
     * não atrapalha ninguém, porque tudo passa por JPA.</p>
     */
    @Column(name = "valor", nullable = false)
    @jakarta.persistence.Convert(converter = br.com.bora.config.SegredoGuardado.class)
    private String valor;

    @Column(name = "atualizado_em")
    private OffsetDateTime atualizadoEm = OffsetDateTime.now();
}
