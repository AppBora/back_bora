package br.com.bora.repository;

import br.com.bora.entity.Promocao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * As duas consultas de promocao, contra um Postgres de verdade.
 *
 * <p>Prova o que mock nao prova: que a busca ignora a caixa (trabalho do Spring Data, nao do nosso
 * codigo) e que o incremento do teto de usos acontece NO BANCO, condicionalmente.</p>
 *
 * <p>O teto de usos e dinheiro: era um contador lido, somado e gravado em Java. Dez pedidos ao mesmo
 * tempo liam o mesmo numero antes de qualquer um gravar, e o "ate 20 usos" que o botao do Dashboard
 * promete ao lojista nao seria 20.</p>
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
class DescontoNoCheckoutIT {

    private static final Long LOJA = 9002L;

    @Autowired
    private PromocaoRepository promocoes;

    @BeforeEach
    void limpar() {
        promocoes.deleteAll(promocoes.findByLojaIdOrderByInicioDesc(LOJA));
    }

    private Promocao relampago(Integer limiteUsos) {
        Promocao p = new Promocao();
        p.setLojaId(LOJA);
        p.setCodigo("BORA15");
        p.setTipo("RELAMPAGO");
        p.setPercentualDesconto(new BigDecimal("15"));
        p.setInicio(OffsetDateTime.now().minusMinutes(5));
        p.setFim(OffsetDateTime.now().plusHours(3));
        p.setLimiteUsos(limiteUsos);
        p.setUsos(0);
        p.setAtiva(true);
        p.setOrigem("MANUAL");
        return promocoes.save(p);
    }

    @Test
    void oClienteAchaOCodigoDigitandoEmQualquerCaixa() {
        relampago(20);

        assertTrue(promocoes.findByLojaIdAndCodigoIgnoreCase(LOJA, "bora15").isPresent());
        assertTrue(promocoes.findByLojaIdAndCodigoIgnoreCase(LOJA, "BoRa15").isPresent());
        assertTrue(promocoes.findByLojaIdAndCodigoIgnoreCase(LOJA, "BORA15").isPresent());
    }

    @Test
    void aPromocaoDeOutraLojaNaoApareceNesta() {
        relampago(20);

        assertTrue(promocoes.findByLojaIdAndCodigoIgnoreCase(LOJA + 1, "BORA15").isEmpty());
    }

    @Test
    void oTetoDeUsosEhRespeitadoPeloBanco() {
        Promocao p = relampago(3);

        assertEquals(1, promocoes.somarUmUso(p.getId()));
        assertEquals(1, promocoes.somarUmUso(p.getId()));
        assertEquals(1, promocoes.somarUmUso(p.getId()));
        // o quarto nao passa: o UPDATE nao casa mais a condicao
        assertEquals(0, promocoes.somarUmUso(p.getId()), "o teto de 3 usos tem que ser 3");

        assertEquals(3, promocoes.findByIdAndLojaId(p.getId(), LOJA).orElseThrow().getUsos());
    }

    @Test
    void promocaoSemTetoNaoTravaNunca() {
        Promocao p = relampago(null);

        for (int i = 0; i < 25; i++) assertEquals(1, promocoes.somarUmUso(p.getId()));

        assertEquals(25, promocoes.findByIdAndLojaId(p.getId(), LOJA).orElseThrow().getUsos());
    }

    // Tinha um caso aqui para "usos nulo". O banco recusou: `promocao.usos` e NOT NULL, entao essa
    // linha nao existe. Teste de cenario impossivel nao prova nada — o `coalesce(p.usos, 0)` da
    // consulta fica como defesa, sem teste que finja estar cobrindo algo.
}
