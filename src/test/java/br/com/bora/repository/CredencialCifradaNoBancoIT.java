package br.com.bora.repository;

import br.com.bora.config.SegredoGuardado;
import br.com.bora.entity.IntegracaoCanal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * As credenciais de terceiros ficam cifradas no banco, e ainda cabem na coluna.
 *
 * <p><b>O que isto protege:</b> {@code loja.asaas_api_key} movimenta o dinheiro do lojista;
 * {@code client_secret}, {@code access_token} e {@code refresh_token} autorizam pedidos no iFood e na
 * 99 em nome dele. Elas estavam em texto puro e saem do banco em todo {@code pg_dump} — e os dumps
 * diários vão para uma pasta sincronizada pelo OneDrive.</p>
 *
 * <p><b>O que quase quebrou a produção calado:</b> valor cifrado é ~35% maior. Um
 * {@code access_token} de 2000 caracteres do iFood cifrado dá ~2711, e a coluna era
 * {@code varchar(2000)}. A gravação falharia na renovação do token — e o iFood invalida o refresh
 * token anterior quando renova, então a loja precisaria reautorizar do zero. A V49 passou essas
 * colunas para {@code text}; este teste é a prova, e por isso usa um token do tamanho real.</p>
 *
 * <p>Isto só pode ser provado contra um banco: o que importa é o que chegou NA COLUNA. O teste lê a
 * coluna por SQL cru, fora do Hibernate, para ver o valor como o {@code pg_dump} veria.</p>
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
class CredencialCifradaNoBancoIT {

    private static final Long LOJA = 9301L;
    /** Do tamanho de um token real do iFood — é ele que estourava a coluna. */
    private static final String TOKEN_LONGO = "ey" + "J".repeat(1998);

    @Autowired private IntegracaoCanalRepository integracoes;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private jakarta.persistence.EntityManager em;

    /**
     * Le do BANCO, nao do cache da sessao.
     *
     * <p>O {@code @DataJpaTest} roda tudo numa transacao, e o {@code findById} devolve a entidade que
     * ja esta no contexto de persistencia — com o valor que o Java gravou, nao o que a coluna tem. Sem
     * limpar, dois destes testes passavam por engano e dois falhavam por engano.</p>
     */
    private IntegracaoCanal relerDoBanco(Long id) {
        em.flush();
        em.clear();
        return integracoes.findById(id).orElseThrow();
    }

    @BeforeEach
    void montar() {
        SegredoGuardado.usarChave("chave-de-teste-nao-usar-em-producao");
        jdbc.update("delete from integracao_canal where loja_id = ?", LOJA);
    }

    @AfterAll
    static void desligar() {
        // Nao deixa a chave ligada para os outros ITs da mesma JVM.
        SegredoGuardado.usarChave(null);
    }

    private IntegracaoCanal salvar(String canal, String accessToken, String clientSecret) {
        IntegracaoCanal i = new IntegracaoCanal();
        i.lojaId = LOJA;
        i.canal = canal;
        i.ativo = true;
        i.accessToken = accessToken;
        i.clientSecret = clientSecret;
        i.webhookToken = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";
        return integracoes.save(i);
    }

    private String naColuna(Long id, String coluna) {
        return jdbc.queryForObject("select " + coluna + " from integracao_canal where id = ?",
                String.class, id);
    }

    @Test
    void oQueChegaNaColunaNaoEhOSegredo() {
        IntegracaoCanal i = salvar("IFOOD", "token-secreto-do-ifood", "secret-do-app");
        integracoes.flush();

        String bruto = naColuna(i.id, "access_token");

        assertTrue(bruto.startsWith("enc:v1:"), "deveria estar cifrado, veio: " + bruto);
        assertFalse(bruto.contains("token-secreto-do-ifood"),
                "o pg_dump levaria o segredo em claro");
        assertFalse(naColuna(i.id, "client_secret").contains("secret-do-app"));
        assertFalse(naColuna(i.id, "webhook_token").contains("a1b2c3d4"));
    }

    @Test
    void aAplicacaoContinuaLendoOValorCERTO() {
        IntegracaoCanal i = salvar("IFOOD", "token-secreto-do-ifood", "secret-do-app");
        IntegracaoCanal lido = relerDoBanco(i.id);

        assertEquals("token-secreto-do-ifood", lido.accessToken);
        assertEquals("secret-do-app", lido.clientSecret);
    }

    @Test
    void tokenDoTamanhoREALDoIfoodCabeCifrado() {
        // Era isto que estourava: 2000 caracteres cifrados dao ~2711, e a coluna era varchar(2000).
        IntegracaoCanal i = salvar("IFOOD", TOKEN_LONGO, null);
        integracoes.flush();

        String bruto = naColuna(i.id, "access_token");
        assertTrue(bruto.length() > 2000,
                "o valor cifrado tem " + bruto.length() + " caracteres, e a coluna aguentou");
        assertEquals(TOKEN_LONGO, relerDoBanco(i.id).accessToken);
    }

    @Test
    void valorAntigoEmTextoPuroContinuaSendoLido() {
        // Ligar a chave nao pode quebrar o que ja esta gravado em claro no banco de producao.
        IntegracaoCanal i = salvar("IFOOD", null, null);
        integracoes.flush();
        jdbc.update("update integracao_canal set access_token = ? where id = ?", "em-texto-puro", i.id);

        assertEquals("em-texto-puro", relerDoBanco(i.id).accessToken,
                "sem o prefixo enc:v1: o valor volta como esta");
    }

    @Test
    void nuloEVazioAtravessamSemCifrar() {
        IntegracaoCanal i = salvar("PIX", null, "");
        integracoes.flush();

        assertNull(naColuna(i.id, "access_token"));
        assertEquals("", naColuna(i.id, "client_secret"));
    }

    @Test
    void semChaveConfigurada_gravaEmTextoPuroComoAntes() {
        SegredoGuardado.usarChave(null);
        IntegracaoCanal i = salvar("IFOOD", "sem-chave-fica-em-claro", null);
        integracoes.flush();

        assertEquals("sem-chave-fica-em-claro", naColuna(i.id, "access_token"),
                "sem BORA_SEGREDO_COLUNAS o comportamento tem que ser o de antes");
    }

    @Test
    void chaveTrocada_naoAbreOQueFoiCifradoComAOutra() {
        IntegracaoCanal i = salvar("IFOOD", "token-secreto-do-ifood", null);
        integracoes.flush();

        SegredoGuardado.usarChave("outra-chave-completamente-diferente");

        // O GCM autentica: nao devolve lixo silenciosamente, estoura.
        // "perder a chave tem que doer AGORA, nao virar 'credencial invalida' no Asaas depois"
        assertThrows(Exception.class, () -> {
            String naoDeveriaAbrir = relerDoBanco(i.id).accessToken;
            fail("abriu com a chave errada: " + naoDeveriaAbrir);
        });
    }

    @Test
    void oMesmoSegredoNaoGeraDuasVezesOMesmoTextoCifrado() {
        // Nonce aleatorio por gravacao: dois registros com a mesma credencial nao podem ter o mesmo
        // valor na coluna, senao o dump revela quais lojas usam a mesma chave.
        IntegracaoCanal a = salvar("IFOOD", "mesmo-token", null);
        IntegracaoCanal b = salvar("NOVE_NOVE", "mesmo-token", null);
        integracoes.flush();

        assertNotEquals(naColuna(a.id, "access_token"), naColuna(b.id, "access_token"));
    }

    @Test
    void cifrarDuasVezesNaoAcontece() {
        // Regravar a entidade nao pode cifrar o valor ja cifrado: o prefixo protege.
        IntegracaoCanal i = salvar("IFOOD", "token-secreto-do-ifood", null);
        integracoes.flush();
        String primeira = naColuna(i.id, "access_token");

        IntegracaoCanal lido = relerDoBanco(i.id);
        lido.ultimoErro = "mexeu em outro campo";
        integracoes.saveAndFlush(lido);

        assertEquals("token-secreto-do-ifood", relerDoBanco(i.id).accessToken);
        assertTrue(naColuna(i.id, "access_token").startsWith("enc:v1:"));
        assertFalse(naColuna(i.id, "access_token").contains("enc:v1:enc:v1:"), primeira);
    }
}
