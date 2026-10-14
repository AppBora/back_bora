package br.com.bora.repository;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Papel;
import br.com.bora.entity.Usuario;
import br.com.bora.entity.UsuarioLoja;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O dono de uma rede aparece na tela de Usuários das lojas em que ele entra por vínculo.
 *
 * <p><b>O defeito que isto fecha, e o erro de diagnostico que ele causou:</b> a tela de Usuarios e o
 * limite de 15 do plano liam so {@code usuario.loja_id} — a loja onde o usuario nasceu. A dona da
 * Zira esta ligada as lojas 19 e 20 por {@code usuario_loja}, e o login usa vinculo, entao ela ENTRA
 * nas duas normalmente. Mas a tela de Usuarios dessas lojas aparecia vazia e o plano contava zero
 * usuario.</p>
 *
 * <p>Isso virou a anotacao "Zira Centro e Zona Norte nao tem NENHUM usuario, ninguem consegue entrar
 * nelas", que era <b>falsa</b> e mandou a investigacao para o lado errado por dias. O padrao certo ja
 * existia no {@code donosDaLoja}, com um comentario descrevendo o mesmo defeito; faltou aplicar aqui.</p>
 *
 * <p>Como rodar: ver o javadoc do {@link PreviaDoAcertoBateComOPagamentoIT}.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:55432/bora",
        "spring.datasource.username=postgres",
        "spring.datasource.password=teste",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class UsuarioDeRedeApareceNaLojaIT {

    @Autowired private UsuarioRepository usuarios;
    @Autowired private UsuarioLojaRepository vinculos;
    @Autowired private LojaRepository lojas;

    // `usuario.loja_id` tem chave estrangeira para `loja` (uma das poucas tabelas que tem), entao as
    // lojas sao criadas de verdade em vez de eu inventar ids — e o banco me ensinou isso.
    private Long MATRIZ, FILIAL, OUTRO_CLIENTE;
    private Usuario dona;

    @BeforeEach
    void montar() {
        MATRIZ = criarLoja("Zira Matriz (teste)");
        FILIAL = criarLoja("Zira Filial (teste)");
        OUTRO_CLIENTE = criarLoja("Outro cliente (teste)");
        dona = novo("dona-teste-" + MATRIZ + "@zira.com", MATRIZ, Papel.ADMINISTRADOR_LOJA, true);
        vincular(dona, FILIAL);
    }

    private Long criarLoja(String nome) {
        Loja l = new Loja();
        l.setNome(nome);
        l.setAtivo(true);
        return lojas.save(l).getId();
    }

    private Usuario novo(String email, Long lojaId, Papel papel, boolean ativo) {
        Usuario u = new Usuario();
        u.setNome("Maria Eduarda");
        u.setEmail(email);
        u.setSenhaHash("$2a$10$naoimporta");
        u.setLojaId(lojaId);
        u.setPapel(papel);
        u.setAtivo(ativo);
        return usuarios.save(u);
    }

    private void vincular(Usuario u, Long lojaId) {
        UsuarioLoja v = new UsuarioLoja();
        v.setUsuarioId(u.getId());
        v.setLojaId(lojaId);
        vinculos.save(v);
    }

    @Test
    void naFilialOndeEntraPorVinculoElaAparece() {
        List<Usuario> naFilial = usuarios.daLoja(FILIAL);

        assertEquals(1, naFilial.size(), "a tela de Usuarios da filial nao pode ficar vazia");
        assertEquals(dona.getId(), naFilial.get(0).getId(), "e a dona, que entra nesta loja por vinculo");
    }

    @Test
    void naMatrizOndeEstaAColunaLojaIdElaTambemAparece() {
        assertEquals(1, usuarios.daLoja(MATRIZ).size());
    }

    @Test
    void oLimiteDoPlanoPassaAContarElaNaFilial() {
        assertEquals(1, usuarios.contarAtivosDaLoja(FILIAL),
                "contando zero, o limite de 15 usuarios nao valia nesta loja");
    }

    @Test
    void usuarioDesativadoNaoContaNoLimiteMasAparaceNaLista() {
        Usuario operador = novo("balcao@zira.com", FILIAL, Papel.OPERADOR, false);
        assertNotNull(operador.getId());

        assertEquals(1, usuarios.contarAtivosDaLoja(FILIAL), "desativado nao ocupa vaga do plano");
        assertEquals(2, usuarios.daLoja(FILIAL).size(), "mas o gerente precisa ve-lo para reativar");
    }

    @Test
    void aLojaDeOutroClienteNaoVeNinguemDaRede() {
        assertTrue(usuarios.daLoja(OUTRO_CLIENTE).isEmpty());
        assertEquals(0, usuarios.contarAtivosDaLoja(OUTRO_CLIENTE));
    }

    @Test
    void naoDuplicaQuemTemColunaEVinculoNaMesmaLoja() {
        vincular(dona, MATRIZ); // a coluna loja_id e o vinculo apontam para a mesma loja

        assertEquals(1, usuarios.daLoja(MATRIZ).size(), "a pessoa e uma, nao duas");
        assertEquals(1, usuarios.contarAtivosDaLoja(MATRIZ));
    }
}
