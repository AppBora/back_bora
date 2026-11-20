package br.com.bora.repository;

import br.com.bora.entity.Pedido;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ITEM 3 DA SECAO 4: a conta do motoboy, contra um PostgreSQL de verdade. (20/11/2026)
 *
 * <h2>O que SO um banco prova</h2>
 * <p>O conserto do item 3 passou a agrupar o acerto pelo CADASTRO do motoboy
 * ({@code pedido.entregador_id}, V80) em vez do texto digitado. Isso criou um risco novo, e ele e de
 * dinheiro: se a entrega com cadastro aparecer <b>tambem</b> na consulta legada (a que agrupa por
 * texto), o mesmo valor sai em duas linhas da tela de acerto -- e fechar as duas <b>paga o motoboy
 * em dobro</b>. O que impede isso e uma condicao dentro do JPQL ({@code entregadorId is null}), e
 * nenhum mock responde por ela: duble de repositorio devolve o que o teste mandar.</p>
 *
 * <p>Tambem se prova aqui o que a chave estrangeira faz no dia em que o lojista APAGA um motoboy que
 * ja tem entrega. A V80 a declarou {@code ON DELETE SET NULL} justamente porque a tela apaga de
 * verdade; sem isso, a exclusao estouraria violacao de FK e -- por ser Postgres -- derrubaria a
 * transacao inteira, devolvendo 500 sem mensagem.</p>
 *
 * <p><b>Como rodar:</b> {@code bash testes-de-integracao.sh --so-uma OacertoNAOcontaDUASvezesOmesmoMOTOBOYIT}</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:55432/bora",
        "spring.datasource.username=postgres",
        "spring.datasource.password=teste",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class OacertoNAOcontaDUASvezesOmesmoMOTOBOYIT {

    @Autowired private EntityManager em;
    @Autowired private PedidoRepository pedidos;

    private Long loja;
    private Long motoboy;

    private static final OffsetDateTime INICIO = OffsetDateTime.now().minusDays(10);
    private static final OffsetDateTime FIM = OffsetDateTime.now().plusDays(1);

    @BeforeEach
    @Transactional
    void semear() {
        // Loja de verdade na tabela `loja`: o motoboy e o pedido apontam para ela.
        loja = (Long) em.createNativeQuery(
                        "insert into loja (nome, ativo) values ('Loja do item 3', true) returning id")
                .getSingleResult();
        motoboy = ((Number) em.createNativeQuery(
                        "insert into entregador (loja_id, nome, ativo) values (?1, 'Jose Carlos', true) returning id")
                .setParameter(1, loja).getSingleResult()).longValue();

        // A: entrega COM cadastro, gravada com o nome VELHO do motoboy (foi renomeado depois).
        gravar(motoboy, "Jose", "5.00", "50.00", "DINHEIRO");
        // B: entrega COM cadastro, gravada com o nome de agora.
        gravar(motoboy, "Jose Carlos", "7.00", "70.00", "PIX");
        // C: entrega LEGADA, sem cadastro nenhum (pedido de antes da V80).
        gravar(null, "Antigo", "3.00", "30.00", "DINHEIRO");
    }

    private void gravar(Long entregadorId, String nome, String taxa, String total, String forma) {
        em.createNativeQuery("""
                insert into pedido (loja_id, status, valor_total, taxa_entrega, forma_pagamento,
                                    entregador, entregador_id, acerto_id, criado_em, atualizado_em,
                                    entregue_em, aguardando_pagamento)
                values (?1, 'ENTREGUE', ?2, ?3, ?4, ?5, ?6, null, now(), now(), now(), false)""")
                .setParameter(1, loja)
                .setParameter(2, new BigDecimal(total))
                .setParameter(3, new BigDecimal(taxa))
                .setParameter(4, forma)
                .setParameter(5, nome)
                .setParameter(6, entregadorId)
                .executeUpdate();
    }

    // ===================================================================== a premissa

    // SEM ISTO, os testes de "nao conta duas vezes" passariam com consultas que nao devolvem nada --
    // e a tela de acerto ficaria vazia, sem ninguem para pagar, com a suite verde.
    @Test
    @Transactional
    void premissa_asTRESentregasESTAOlaEcontamUMAvezCADA() {
        List<Object[]> porCadastro = pedidos.previaAcertoPorCadastro(loja, INICIO, FIM);
        List<Object[]> porTexto = pedidos.previaAcerto(loja, INICIO, FIM);

        assertEquals(1, porCadastro.size(), "as duas entregas do motoboy tem de virar UMA linha");
        assertEquals(2L, ((Number) porCadastro.get(0)[2]).longValue());
        assertEquals(0, new BigDecimal("12.00").compareTo((BigDecimal) porCadastro.get(0)[3]),
                "5 + 7 de taxa: e o que o motoboy recebe");

        assertEquals(1, porTexto.size(), "a entrega legada continua aparecendo");
        assertEquals("Antigo", porTexto.get(0)[0]);
    }

    // ===================================================================== o dinheiro

    @Test
    @Transactional
    void aENTREGAcomCADASTROnaoAPARECEnaLINHAdeTEXTO() {
        // ESTE E O TESTE DO DINHEIRO. Sem o filtro `entregadorId is null` na consulta legada, as
        // entregas A e B sairiam tambem numa linha "Jose" e numa linha "Jose Carlos" -- tres linhas
        // na tela para duas corridas, e fechar todas pagaria 12 + 5 + 7 = 24 reais de taxa onde se
        // deve 12.
        List<Object[]> porTexto = pedidos.previaAcerto(loja, INICIO, FIM);

        assertEquals(1, porTexto.size(),
                "a consulta por texto trouxe entrega que JA esta na linha por cadastro -- a tela "
                + "mostraria o mesmo dinheiro duas vezes");
        assertEquals("Antigo", porTexto.get(0)[0]);
        BigDecimal taxaLegada = (BigDecimal) porTexto.get(0)[2];
        assertEquals(0, new BigDecimal("3.00").compareTo(taxaLegada));
    }

    @Test
    @Transactional
    void fecharPELOtextoNAOlevaAentregaQUEtemCADASTRO() {
        // A outra metade do mesmo risco: a entrega A tem o texto "Jose". Se `entregasParaAcerto`
        // ainda a pegasse pelo texto, fechar uma linha legada chamada "Jose" amarraria uma corrida
        // que pertence a linha por cadastro -- e o acerto por cadastro, feito depois, nao a acharia
        // mais. O motoboy receberia menos do que a previa mostrou.
        assertTrue(pedidos.entregasParaAcerto(loja, "Jose", INICIO, FIM).isEmpty(),
                "o texto levou uma entrega que tem cadastro");
        assertTrue(pedidos.entregasParaAcerto(loja, "Jose Carlos", INICIO, FIM).isEmpty());
        assertEquals(1, pedidos.entregasParaAcerto(loja, "Antigo", INICIO, FIM).size(),
                "a entrega legada continua fechando pelo texto");
    }

    @Test
    @Transactional
    void fecharPELOcadastroLEvaASduasEntregasDOmotoboy() {
        List<Pedido> peds = pedidos.entregasParaAcertoPorCadastro(loja, motoboy, INICIO, FIM);

        assertEquals(2, peds.size(), "as corridas do motoboy renomeado tem de vir juntas");
        assertEquals(0, peds.stream()
                        .map(p -> p.taxaEntrega == null ? BigDecimal.ZERO : p.taxaEntrega)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .compareTo(new BigDecimal("12.00")));
    }

    @Test
    @Transactional
    void aENTREGAjaACERTADAnaoVOLTAemNENHUMAdasDUASconsultas() {
        // `acerto_id is null` e a condicao mais importante das duas consultas: entrega ja paga nao
        // pode aparecer de novo. Vale para o caminho novo igual ao antigo.
        em.createNativeQuery("update pedido set acerto_id = 1 where loja_id = ?1")
                .setParameter(1, loja).executeUpdate();
        em.flush();
        em.clear();

        assertTrue(pedidos.previaAcertoPorCadastro(loja, INICIO, FIM).isEmpty());
        assertTrue(pedidos.previaAcerto(loja, INICIO, FIM).isEmpty());
        assertTrue(pedidos.entregasParaAcertoPorCadastro(loja, motoboy, INICIO, FIM).isEmpty());
    }

    // ===================================================================== apagar o motoboy

    @Test
    @Transactional
    void apagarOmotoboyNAOderrubaAexclusao_eAentregaVIRAlegada() {
        // A tela de entregadores apaga de verdade (DELETE /api/entregadores/{id}). Sem o
        // ON DELETE SET NULL da V80, isto estouraria violacao de FK e devolveria 500 -- e no
        // Postgres a transacao inteira morre, entao nem o resto da requisicao se salvaria.
        assertDoesNotThrow(() -> {
            em.createNativeQuery("delete from entregador where id = ?1")
                    .setParameter(1, motoboy).executeUpdate();
            em.flush();
        }, "apagar um motoboy com entrega gravada derrubou a exclusao");
        em.clear();

        Number comCadastro = (Number) em.createNativeQuery(
                        "select count(*) from pedido where loja_id = ?1 and entregador_id is not null")
                .setParameter(1, loja).getSingleResult();
        assertEquals(0L, comCadastro.longValue(), "o id tinha de ter virado nulo");

        // E as entregas nao se perdem: elas voltam a ser agrupadas pelo TEXTO, que e o
        // comportamento de antes da V80. O nome gravado no pedido e o que salva o dinheiro aqui.
        List<Object[]> porTexto = pedidos.previaAcerto(loja, INICIO, FIM);
        assertEquals(3, porTexto.size(),
                "as entregas do motoboy apagado desapareceram do acerto: 'Jose', 'Jose Carlos' e "
                + "'Antigo' tem de estar todas la, cada uma como sua propria linha de texto");
    }

    // ===================================================================== o indice

    @Test
    @Transactional
    void oINDICEdaCONSULTAnovaEXISTE() {
        // A consulta por cadastro roda na tela de acerto, que o lojista abre no fim do periodo com a
        // tabela de pedidos inteira atras. O indice e o irmao do `idx_pedido_acerto_entregador`
        // (V56), com o mesmo filtro parcial.
        String def = (String) em.createNativeQuery(
                        "select indexdef from pg_indexes where indexname = 'idx_pedido_acerto_entregador_id'")
                .getSingleResult();

        assertTrue(def.contains("entregador_id"), def);
        assertTrue(def.toLowerCase().contains("where") && def.contains("acerto_id IS NULL"),
                "o indice perdeu o filtro parcial e vai crescer com o historico inteiro: " + def);
    }

    @Test
    @Transactional
    void aCHAVEestrangeiraEXISTEeEHonDELETEsetNULL() {
        String def = (String) em.createNativeQuery("""
                select pg_get_constraintdef(oid) from pg_constraint
                 where conname = 'fk_pedido_entregador'""").getSingleResult();

        assertTrue(def.contains("REFERENCES entregador(id)"), def);
        assertTrue(def.contains("ON DELETE SET NULL"),
                "sem o ON DELETE, apagar um motoboy com entrega devolve 500: " + def);
    }
}
