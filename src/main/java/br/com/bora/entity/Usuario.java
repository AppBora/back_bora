package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

@Entity
@Table(name = "usuario")
@Getter
@Setter
public class Usuario {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Loja (tenant) dona do usuário. Nulo apenas para ADMINISTRADOR_BORA (global). */
    @Column(name = "loja_id")
    private Long lojaId;

    private String nome;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "senha_hash", nullable = false)
    private String senhaHash;

    @Enumerated(EnumType.STRING)
    private Papel papel = Papel.OPERADOR;

    private Boolean ativo = true;

    @Column(name = "criado_em")
    private OffsetDateTime criadoEm = OffsetDateTime.now();

    /**
     * Versao da senha. O token nasce com ela e o {@code JwtAuthFilter} a confere em cada
     * requisicao: se o numero do token nao bate com o do banco, o token esta morto.
     *
     * <p>Comeca em 1 e sobe de 1 a cada troca de senha. Ver {@code V78}.</p>
     */
    @Column(name = "versao_senha", nullable = false)
    private Integer versaoSenha = 1;

    /**
     * ESTE SETTER E ESCRITO A MAO DE PROPOSITO, e e o coracao do conserto.
     *
     * <p>Sao <b>nove</b> lugares no codigo que escrevem senha (dois no BootstrapAdmin, dois no
     * PlataformaController, um no AuthController, um no SignupController, um no
     * UsuariosDaPlataformaService e dois no UsuarioService). Somar 1 "em cada lugar que troca
     * senha" seria uma lista para alguem esquecer -- hoje ou no proximo lugar que aparecer. E
     * esquecer um unico deles deixa o buraco aberto por aquele caminho, calado.</p>
     *
     * <p>Aqui nao ha como esquecer: quem escreve a senha e obrigado a passar por este metodo. O
     * Lombok nao gera setter quando ja existe um com o mesmo nome, entao este substitui o dele e
     * todas as nove chamadas -- e qualquer decima -- passam a somar sozinhas.</p>
     *
     * <p><b>Criar usuario NAO soma.</b> Na criacao {@code this.senhaHash} e nulo, e o usuario novo
     * fica na versao 1. Somar na criacao nao quebraria nada (nao existe token dele ainda), mas
     * deixaria a versao sem significado: 2 para quem nunca trocou senha.</p>
     *
     * <p><b>Regravar o MESMO hash nao soma.</b> O BCrypt gera hash diferente a cada chamada para a
     * mesma senha, entao na pratica trocar a senha sempre muda o hash; a comparacao esta aqui para
     * o caso de alguem reatribuir o valor que ja estava, o que nao e troca de senha nenhuma e nao
     * deve derrubar a sessao de ninguem.</p>
     */
    public void setSenhaHash(String senhaHash) {
        if (this.senhaHash != null && !this.senhaHash.equals(senhaHash)) {
            this.versaoSenha = (this.versaoSenha == null ? 1 : this.versaoSenha) + 1;
        }
        this.senhaHash = senhaHash;
    }
}
