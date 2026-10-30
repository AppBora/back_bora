package br.com.bora.repository;

import br.com.bora.entity.Cliente;
import br.com.bora.entity.TaxaEntrega;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A V65 morde: o BANCO recusa o segundo bairro e o segundo cliente.
 *
 * <h2>Por que isto precisa de teste</h2>
 * <p>Migracao que cria indice e facil de escrever errado de um jeito que passa calado. Um
 * {@code UNIQUE} sobre a coluna crua de bairro deixaria "Centro", "centro" e " Centro " entrarem as
 * tres; um {@code UNIQUE} sem o {@code WHERE} no telefone faria todos os clientes de balcao SEM
 * telefone brigarem entre si e derrubaria o cadastro da loja inteira. Nos dois casos a migracao
 * "aplica com sucesso" e so se descobre em producao.</p>
 *
 * <p>O conserto em Java ({@code OperacaoService.salvarTaxa}, {@code upsertCliente}) e
 * read-then-insert: ele resolve o lojista clicando duas vezes, e <b>perde a corrida</b> de dois
 * pedidos simultaneos. Quem fecha a porta de verdade e o banco. Este arquivo prova que ela fechou.</p>
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
class OBANCOrecusaOsegundoBairroEoSegundoClienteIT {

    private static final Long LOJA = 9301L;
    private static final Long OUTRA_LOJA = 9302L;

    @Autowired private TaxaEntregaRepository taxas;
    @Autowired private ClienteRepository clientes;
    @Autowired private EntityManager em;

    @BeforeEach
    void limpar() {
        em.createNativeQuery("delete from taxa_entrega where loja_id in (?1, ?2)")
                .setParameter(1, LOJA).setParameter(2, OUTRA_LOJA).executeUpdate();
        em.createNativeQuery("delete from cliente where loja_id in (?1, ?2)")
                .setParameter(1, LOJA).setParameter(2, OUTRA_LOJA).executeUpdate();
        em.flush();
    }

    private void gravarBairro(Long loja, String bairro) {
        TaxaEntrega t = new TaxaEntrega();
        t.lojaId = loja;
        t.bairro = bairro;
        t.taxa = new BigDecimal("5.00");
        t.ativo = true;
        taxas.saveAndFlush(t);
    }

    private void gravarCliente(Long loja, String telefone, String canal, String idExterno) {
        Cliente c = new Cliente();
        c.lojaId = loja;
        c.nome = "Fulano";
        c.telefone = telefone;
        c.canalExterno = canal;
        c.idExterno = idExterno;
        clientes.saveAndFlush(c);
    }

    // =========================================================== taxa_entrega

    @Test
    void oSEGUNDObairroIGUALnaMESMAlojaEHrecusadoPELObanco() {
        gravarBairro(LOJA, "Centro");

        assertThrows(Exception.class, () -> gravarBairro(LOJA, "Centro"),
                "o banco aceitou dois fretes para o mesmo bairro: o cardapio volta a mostrar o "
                        + "bairro duas vezes com precos diferentes");
    }

    @Test
    void aMESMAgrafiaEMoutraCAIXAtambemEHrecusada() {
        // "Centro", "centro" e " Centro " sao o mesmo bairro para quem digita e para quem escolhe no
        // cardapio. Com UNIQUE sobre a coluna crua, as tres entrariam -- e o indice existiria sem
        // impedir nada.
        gravarBairro(LOJA, "Centro");

        assertThrows(Exception.class, () -> gravarBairro(LOJA, "  cEnTrO "),
                "o indice esta sobre a coluna crua, nao sobre lower(trim(bairro))");
    }

    @Test
    void bairrosDIFERENTEScontinuamENTRANDO() {
        gravarBairro(LOJA, "Centro");

        assertDoesNotThrow(() -> gravarBairro(LOJA, "Vila Nova"));
        assertEquals(2, taxas.findByLojaIdOrderByBairroAsc(LOJA).size());
    }

    @Test
    void oMESMObairroEMoutraLOJAcontinuaENTRANDO() {
        // O indice e por (loja, bairro). Esquecer a loja faria uma loja bloquear o cadastro da outra.
        gravarBairro(LOJA, "Centro");

        assertDoesNotThrow(() -> gravarBairro(OUTRA_LOJA, "Centro"));
    }

    // =========================================================== cliente

    @Test
    void oSEGUNDOclienteCOMoMESMOtelefoneEHrecusado() {
        gravarCliente(LOJA, "15999990001", null, null);

        assertThrows(Exception.class, () -> gravarCliente(LOJA, "15999990001", null, null),
                "dois pedidos do mesmo telefone ao mesmo tempo voltam a criar dois clientes, e o "
                        + "cashback fica repartido entre os dois");
    }

    @Test
    void clientesSEMtelefoneNAObrigamENTREsi() {
        // ISTO E O QUE O `WHERE` DO INDICE PROTEGE. Cliente de balcao sem telefone e normal, e sao
        // muitos: sem o WHERE, o segundo deles seria recusado e o cadastro da loja parava.
        gravarCliente(LOJA, null, null, null);

        assertDoesNotThrow(() -> gravarCliente(LOJA, null, null, null));
        assertDoesNotThrow(() -> gravarCliente(LOJA, "", null, null));
        assertDoesNotThrow(() -> gravarCliente(LOJA, "", null, null));
    }

    @Test
    void oMESMOtelefoneEMoutraLOJAcontinuaENTRANDO() {
        // Cliente e por loja: a mesma pessoa pode comprar em duas lojas da rede.
        gravarCliente(LOJA, "15999990001", null, null);

        assertDoesNotThrow(() -> gravarCliente(OUTRA_LOJA, "15999990001", null, null));
    }

    @Test
    void oSEGUNDOclienteCOMoMESMOidEXTERNOehRECUSADO() {
        gravarCliente(LOJA, null, "IFOOD", "cli-ext-77");

        assertThrows(Exception.class, () -> gravarCliente(LOJA, null, "IFOOD", "cli-ext-77"),
                "o evento repetido do marketplace volta a criar um segundo cliente");
    }

    @Test
    void oMESMOidEXTERNOemOUTROcanalCONTINUAentrando() {
        // iFood e 99 numeram os clientes deles por conta propria: o mesmo texto pode existir nos dois.
        gravarCliente(LOJA, null, "IFOOD", "cli-ext-77");

        assertDoesNotThrow(() -> gravarCliente(LOJA, null, "NOVE_NOVE", "cli-ext-77"));
    }

    @Test
    void clientesSEMidEXTERNOnaoBRIGAMentreSI() {
        // Todo cliente do canal proprio tem id externo nulo. Sem o WHERE, o segundo seria recusado.
        gravarCliente(LOJA, "15999990001", null, null);

        assertDoesNotThrow(() -> gravarCliente(LOJA, "15999990002", null, null));
        assertDoesNotThrow(() -> gravarCliente(LOJA, "15999990003", null, null));
    }
}
