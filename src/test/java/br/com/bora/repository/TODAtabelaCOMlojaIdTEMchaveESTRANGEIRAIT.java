package br.com.bora.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ITEM 6 DA SECAO 4: toda tabela com {@code loja_id} aponta para {@code loja} de verdade. (20/11/2026)
 *
 * <h2>O defeito, medido</h2>
 * <p>31 tabelas tem a coluna {@code loja_id} e apenas <b>8</b> declaravam chave estrangeira. Nas
 * outras 23 o banco aceitava, calado, produto/pedido/cliente apontando para uma loja que nao existe:
 * "multi-tenant" era convencao de codigo, garantida pelo {@code ctx.lojaId()} de cada consulta e por
 * nada abaixo dele. Um {@code loja_id} errado num INSERT gravava dado de um cliente dentro de outro
 * <b>em silencio</b>. A V82 fechou isso.</p>
 *
 * <h2>Por que este teste e o que faz o conserto DURAR</h2>
 * <p>A migracao conserta as tabelas de hoje. Este teste cuida de amanha: a proxima tabela que nascer
 * com {@code loja_id} e sem chave estrangeira <b>derruba a suite</b>, com o nome dela na mensagem.
 * Sem isto, o item 6 voltaria sozinho -- foi assim que ele chegou a 23 tabelas.</p>
 *
 * <p>As excecoes estao listadas <b>aqui, com nome e motivo</b>, e nao numa regra generica. Acrescentar
 * uma tabela a lista e uma decisao que aparece no diff e pede explicacao -- que e o oposto de uma
 * tabela que nasce sem FK porque ninguem olhou.</p>
 *
 * <p><b>Como rodar:</b> {@code bash testes-de-integracao.sh --so-uma TODAtabelaCOMlojaIdTEMchaveESTRANGEIRAIT}</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:55432/bora",
        "spring.datasource.username=postgres",
        "spring.datasource.password=teste",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class TODAtabelaCOMlojaIdTEMchaveESTRANGEIRAIT {

    /**
     * As tabelas que PODEM ter {@code loja_id} sem chave estrangeira, e o porque de cada uma.
     *
     * <p>{@code evento_outbox} -- decisao da V74, reafirmada na V82: o INSERT da fila mora DENTRO da
     * transacao da venda, e no PostgreSQL uma instrucao que falha envenena a transacao inteira. Uma
     * FK ali transforma "loja fora do cadastro" em VENDA PERDIDA. Fila orfa e historico sem dono;
     * venda perdida e dinheiro.</p>
     */
    private static final Set<String> SEM_FK_DE_PROPOSITO = Set.of("evento_outbox");

    @Autowired private JdbcTemplate jdbc;

    private List<String> tabelasComLojaId() {
        return jdbc.queryForList("""
                select c.table_name
                  from information_schema.columns c
                  join information_schema.tables t
                    on t.table_name = c.table_name and t.table_schema = 'public'
                 where c.table_schema = 'public' and c.column_name = 'loja_id'
                   and t.table_type = 'BASE TABLE'
                 order by c.table_name""", String.class);
    }

    private List<String> tabelasComFkParaLoja() {
        return jdbc.queryForList("""
                select distinct tc.table_name
                  from information_schema.table_constraints tc
                  join information_schema.key_column_usage kcu
                    on kcu.constraint_name = tc.constraint_name
                  join information_schema.constraint_column_usage ccu
                    on ccu.constraint_name = tc.constraint_name
                 where tc.constraint_type = 'FOREIGN KEY'
                   and kcu.column_name = 'loja_id'
                   and ccu.table_name = 'loja'""", String.class);
    }

    // ===================================================================== a premissa

    // SEM ISTO, o teste principal passaria com uma consulta que nao acha tabela nenhuma -- "zero
    // tabelas sem FK" seria verdade por vacuidade, e a suite ficaria verde medindo nada.
    @Test
    void premissa_asDUASconsultasACHAMcoisa() {
        assertTrue(tabelasComLojaId().size() >= 30,
                "a consulta de colunas nao achou as tabelas com loja_id: ela parou de medir");
        assertTrue(tabelasComFkParaLoja().size() >= 20,
                "a consulta de chaves estrangeiras nao achou as FKs: ela parou de medir");
    }

    // ===================================================================== o que importa

    @Test
    void NENHUMAtabelaCOMlojaIdFICAsemCHAVEestrangeira() {
        List<String> comFk = tabelasComFkParaLoja();
        List<String> faltando = tabelasComLojaId().stream()
                .filter(t -> !comFk.contains(t))
                .filter(t -> !SEM_FK_DE_PROPOSITO.contains(t))
                .toList();

        assertTrue(faltando.isEmpty(),
                "Tabela com `loja_id` e SEM chave estrangeira para `loja`: " + faltando
                + "\nO banco vai aceitar, calado, linha dessa tabela apontando para uma loja que nao "
                + "existe -- e um loja_id errado num INSERT grava dado de um cliente dentro de outro. "
                + "Acrescente a FK numa migracao nova (ver V82), ou, se houver motivo de verdade para "
                + "ela nao existir, documente a tabela em SEM_FK_DE_PROPOSITO neste arquivo.");
    }

    @Test
    void aFILAdeEVENTOScontinuaSEMfk_eISSOehPROPOSITAL() {
        // A outra ponta da regra: se alguem "consertar" a fila acrescentando a FK, a venda passa a
        // poder ser recusada por integridade referencial. Este teste existe para essa mudanca ser
        // uma decisao, e nao um descuido -- ela tem de derrubar a suite primeiro.
        assertFalse(tabelasComFkParaLoja().contains("evento_outbox"),
                "A fila de eventos ganhou chave estrangeira para `loja`. Isso coloca a venda nas maos "
                + "da integridade referencial: o INSERT da fila mora na transacao da venda, e no "
                + "PostgreSQL uma instrucao que falha envenena a transacao inteira. Se a decisao "
                + "mudou de verdade, tire 'evento_outbox' de SEM_FK_DE_PROPOSITO e escreva o porque.");
    }

    @Test
    void aTRILHAdeAUDITORIAtemFKqueNAOimpedeEXCLUSAO() {
        // auditoria entrou na V82, mas com ON DELETE SET NULL: a trilha nao pode impedir a exclusao
        // de uma loja nem desaparecer com ela. E a mesma regra que a V74 aplicou ao usuario_id.
        String def = jdbc.queryForObject("""
                select pg_get_constraintdef(oid) from pg_constraint
                 where conname = 'fk_auditoria_loja'""", String.class);

        assertNotNull(def, "a FK da auditoria desapareceu");
        assertTrue(def.contains("ON DELETE SET NULL"),
                "a trilha de auditoria passou a IMPEDIR a exclusao da loja: " + def);
    }

    @Test
    void asTABELASdeDINHEIROestaoCOBERTAS() {
        // Nominal de proposito. A lista acima e generica e pega tudo; esta diz, com nome, que as
        // tabelas onde um loja_id errado custa dinheiro estao entre as cobertas.
        List<String> comFk = tabelasComFkParaLoja();
        for (String t : List.of("pedido", "pedido_item", "cliente", "produto", "insumo",
                                "assinatura", "pagamento_assinatura", "acerto_entregador",
                                "promocao", "taxa_entrega")) {
            assertTrue(comFk.contains(t), "`" + t + "` ficou sem chave estrangeira para loja");
        }
    }
}
