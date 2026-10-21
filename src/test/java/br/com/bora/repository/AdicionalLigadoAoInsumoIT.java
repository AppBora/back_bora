package br.com.bora.repository;

import br.com.bora.entity.ComplementoItem;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O vínculo do adicional com o insumo existe no BANCO, e o banco se recusa a criar fantasma.
 *
 * <h2>Por que contra Postgres de verdade</h2>
 * <p>Três coisas desta mudança só existem no banco e nenhum teste de unidade as alcança: as duas
 * colunas da V55, a <b>chave estrangeira</b> para {@code insumo}, e a largura de
 * {@code numeric(14,4)} — que decide se "0,0300 kg" cabe. O {@code ddl-auto: validate} confere nome e
 * tipo de coluna, mas <b>não confere largura nem chave estrangeira</b>, e os {@code *IT} são
 * excluídos do {@code mvn test} e do build do deploy.</p>
 *
 * <p>A FK é a diferença deliberada em relação a {@code produto_insumo}, que não tem nenhuma — e foi
 * exatamente isso que produziu a ficha fantasma do item 2.5: apagar o insumo deixava a linha
 * apontando para o nada e o estoque vazava calado. Aqui o serviço recusa primeiro, com mensagem boa;
 * esta classe prova que o banco também recusa, para o dia em que alguém apagar insumo por outro
 * caminho.</p>
 */
@SpringBootTest
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:55432/bora",
        "spring.datasource.username=postgres",
        "spring.datasource.password=teste",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class AdicionalLigadoAoInsumoIT {

    @Autowired
    private ComplementoItemRepository adicionais;

    @Autowired
    private EntityManager em;

    private Long loja;
    private Long grupo;

    @BeforeEach
    @Transactional
    void semear() {
        loja = (Long) em.createNativeQuery(
                        "insert into loja (nome, ativo) values ('Loja do adicional', true) returning id")
                .getSingleResult();
        Long produto = (Long) em.createNativeQuery(
                        "insert into produto (loja_id, nome, preco, ativo) "
                                + "values (?1, 'Acai 500ml', 20.00, true) returning id")
                .setParameter(1, loja).getSingleResult();
        grupo = (Long) em.createNativeQuery(
                        "insert into complemento_grupo (loja_id, produto_id, nome, minimo, maximo) "
                                + "values (?1, ?2, 'Adicionais', 0, 5) returning id")
                .setParameter(1, loja).setParameter(2, produto).getSingleResult();
    }

    private Long insumo(String nome, String estoque) {
        return (Long) em.createNativeQuery(
                        "insert into insumo (loja_id, nome, unidade, custo, estoque) "
                                + "values (?1, ?2, 'kg', 20.00, ?3) returning id")
                .setParameter(1, loja).setParameter(2, nome)
                .setParameter(3, new BigDecimal(estoque)).getSingleResult();
    }

    private ComplementoItem adicional(Long insumoId, String quantidade) {
        ComplementoItem ci = new ComplementoItem();
        ci.lojaId = loja;
        ci.grupoId = grupo;
        ci.nome = "Granola";
        ci.preco = new BigDecimal("3.00");
        ci.insumoId = insumoId;
        ci.quantidade = quantidade == null ? null : new BigDecimal(quantidade);
        return adicionais.save(ci);
    }

    // =============================================================== as colunas existem e cabem

    @Test
    @Transactional
    void oADICIONALguardaOINSUMOeAQUANTIDADE() {
        Long ins = insumo("Granola", "5.000");

        ComplementoItem salvo = adicional(ins, "0.0300");
        em.flush();
        em.clear();

        ComplementoItem lido = adicionais.findById(salvo.id).orElseThrow();
        assertEquals(ins, lido.insumoId);
        assertEquals(0, lido.quantidade.compareTo(new BigDecimal("0.0300")));
    }

    @Test
    @Transactional
    void aQUANTIDADEguardaQUATROcasas() {
        // numeric(14,4). Com duas casas, "0,0300 kg" viraria 0,03 e o consumo sairia 0% errado por
        // sorte; mas "0,0025" (uma pitada de canela) viraria ZERO e o adicional pararia de baixar
        // calado. E o mesmo tipo de corte silencioso dos itens 4.2 e 4.5 da varredura.
        Long ins = insumo("Canela", "1.000");

        ComplementoItem salvo = adicional(ins, "0.0025");
        em.flush();
        em.clear();

        assertEquals(0, adicionais.findById(salvo.id).orElseThrow().quantidade
                        .compareTo(new BigDecimal("0.0025")),
                "quatro casas tem que sobreviver, senao pitada de tempero vira zero");
    }

    @Test
    @Transactional
    void adicionalSEMinsumoCONTINUAvalido() {
        // E o padrao, e e todo adicional que ja esta no banco. Se a coluna fosse NOT NULL, a V55
        // derrubaria a subida da aplicacao.
        ComplementoItem salvo = adicional(null, null);
        em.flush();
        em.clear();

        ComplementoItem lido = adicionais.findById(salvo.id).orElseThrow();
        assertNull(lido.insumoId);
        assertNull(lido.quantidade);
    }

    // =============================================================== o banco recusa fantasma

    @Test
    @Transactional
    void oBANCOrecusaAPAGARinsumoQUEumADICIONALusa() {
        // A rede embaixo da recusa do servico. Sem ela, apagar o insumo por qualquer outro caminho
        // criaria a ficha fantasma do item 2.5 dentro do adicional.
        Long ins = insumo("Granola", "5.000");
        adicional(ins, "0.0300");
        em.flush();

        assertThrows(Exception.class, () -> {
            em.createNativeQuery("delete from insumo where id = ?1").setParameter(1, ins)
                    .executeUpdate();
            em.flush();
        }, "o banco tem que recusar: adicional apontando para insumo que nao existe e estoque "
                + "vazando calado");
    }

    @Test
    @Transactional
    void oBANCOrecusaADICIONALapontandoParaINSUMOinexistente() {
        assertThrows(Exception.class, () -> {
            adicional(999_999_999L, "0.0300");
            em.flush();
        });
    }

    // =============================================================== a consulta da recusa

    @Test
    @Transactional
    void aCONSULTAdaRECUSAacha_eNAOvazaEntreLOJAS() {
        Long ins = insumo("Granola", "5.000");
        adicional(ins, "0.0300");
        em.flush();

        List<ComplementoItem> achados = adicionais.findByLojaIdAndInsumoId(loja, ins);
        assertEquals(1, achados.size());
        assertEquals("Granola", achados.get(0).nome);

        // A mesma consulta com OUTRA loja nao pode achar nada -- senao a recusa do excluir trancaria
        // o insumo de um lojista por causa do adicional de outro.
        assertTrue(adicionais.findByLojaIdAndInsumoId(loja + 100_000L, ins).isEmpty());
    }
}
