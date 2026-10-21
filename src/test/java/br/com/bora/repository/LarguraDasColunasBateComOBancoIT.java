package br.com.bora.repository;

import br.com.bora.entity.LarguraDeColuna;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Os números que o código usa para cortar são os mesmos do banco.
 *
 * <h2>O buraco que isto tapa</h2>
 * <p>O {@code ddl-auto: validate} do Hibernate confere se a coluna existe e qual o tipo.
 * <b>Não confere a largura.</b> Uma coluna de 255 recebendo texto de 300 passa pela subida da
 * aplicação, por todo teste com mock, e só falha no Postgres — na hora em que um pedido de verdade
 * chega. E não falha truncando: o Postgres <b>recusa o INSERT</b>, o pedido inteiro se perde, e o
 * entregador do iFood está a caminho de uma venda que o painel nunca viu.</p>
 *
 * <p>Foi exatamente o caso de {@code pedido.endereco_entrega}: o mesmo endereço do marketplace ia
 * cortado em 500 para {@code cliente.endereco} (varchar(500) desde a V37, largada justamente por
 * isso) e <b>sem corte</b> para uma coluna de 255. Dois lugares no mesmo método, dois tamanhos.</p>
 *
 * <h2>O que este teste trava</h2>
 * <p>Cada constante de {@link LarguraDeColuna} contra a largura <b>real</b> da coluna. Uma migração
 * que mude a largura sem atualizar a constante reprova; uma constante trocada sem migração reprova
 * também. É a única forma de o descasamento aparecer antes da produção, porque nem o Hibernate nem
 * teste com mock enxergam largura.</p>
 *
 * <p><b>Como rodar:</b> precisa de um Postgres. Com Docker:</p>
 * <pre>
 * docker run -d --name bora-teste-pg -e POSTGRES_PASSWORD=teste -e POSTGRES_DB=bora -p 55432:5432 postgres:16
 * mvn -o test -Dtest='*IT' -DfailIfNoTests=false
 * </pre>
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
class LarguraDasColunasBateComOBancoIT {

    @Autowired private EntityManager em;

    /** O que o código acredita: tabela.coluna -> largura usada para cortar. */
    private static Map<String, Integer> oQueOcodigoAcredita() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("pedido.endereco_entrega", LarguraDeColuna.PEDIDO_ENDERECO);
        m.put("pedido.bairro_entrega", LarguraDeColuna.PEDIDO_BAIRRO);
        m.put("pedido.id_externo", LarguraDeColuna.PEDIDO_ID_EXTERNO);
        m.put("pedido.forma_pagamento", LarguraDeColuna.PEDIDO_FORMA_PAGAMENTO);
        m.put("pedido.cliente_telefone", LarguraDeColuna.PEDIDO_TELEFONE);
        m.put("pedido.codigo", LarguraDeColuna.PEDIDO_CODIGO);
        m.put("cliente.endereco", LarguraDeColuna.CLIENTE_ENDERECO);
        m.put("cliente.bairro", LarguraDeColuna.CLIENTE_BAIRRO);
        m.put("cliente.nome", LarguraDeColuna.CLIENTE_NOME);
        m.put("cliente.telefone", LarguraDeColuna.CLIENTE_TELEFONE);
        m.put("cliente.id_externo", LarguraDeColuna.CLIENTE_ID_EXTERNO);
        // Itens 4.4 e 4.5 da varredura (21/10/2026): as duas colunas em que o estouro nao cortava
        // texto, desfazia a gravacao -- uma o registro de um pagamento, a outra o registro de uma
        // falha, escrito de dentro do tratamento de erro.
        m.put("pagamento_assinatura.descricao", LarguraDeColuna.PAGAMENTO_DESCRICAO);
        m.put("integracao_canal.ultimo_erro", LarguraDeColuna.INTEGRACAO_ULTIMO_ERRO);
        return m;
    }

    /**
     * A largura da coluna, ou {@code null} quando ela nao tem limite (TEXT) ou nao existe.
     *
     * <p>Com {@code getResultList}, nao {@code getResultStream().findFirst()}: coluna TEXT existe e
     * devolve {@code character_maximum_length} NULO, e {@code Stream.findFirst()} estoura com
     * NullPointerException quando o primeiro elemento e nulo. A primeira versao deste metodo caiu
     * exatamente nisso -- no caso que o teste existe para cobrir.</p>
     */
    private Integer larguraNoBanco(String tabela, String coluna) {
        java.util.List<?> linhas = em.createNativeQuery("""
                        select character_maximum_length from information_schema.columns
                         where table_name = ?1 and column_name = ?2""")
                .setParameter(1, tabela).setParameter(2, coluna)
                .getResultList();
        if (linhas.isEmpty()) return null;              // a coluna nao existe
        Object r = linhas.get(0);
        return r == null ? null : ((Number) r).intValue();   // nulo = TEXT, sem limite
    }

    @Test
    void cadaLarguraDoCodigoEhAlarguraRealDaColuna() {
        List<String> erradas = new ArrayList<>();
        oQueOcodigoAcredita().forEach((caminho, esperada) -> {
            String[] partes = caminho.split("\\.");
            Integer real = larguraNoBanco(partes[0], partes[1]);
            if (real == null) {
                erradas.add(caminho + ": a coluna nao existe, ou e TEXT (sem limite) e nao precisa "
                        + "de constante nenhuma");
            } else if (!real.equals(esperada)) {
                erradas.add(caminho + ": o codigo corta em " + esperada + " e o banco aceita " + real);
            }
        });
        assertTrue(erradas.isEmpty(),
                "O codigo e o banco discordam sobre o tamanho destas colunas. Cortar MENOS que a "
                        + "coluna aceita perde texto de graca; cortar MAIS faz o Postgres recusar o "
                        + "INSERT e o pedido inteiro se perde. Atualize LarguraDeColuna ou escreva a "
                        + "migracao:\\n  " + String.join("\\n  ", erradas));
    }

    @Test
    void oENDERECOdoPedidoCabeOmesmoQueOdoCliente() {
        // A assimetria que causou o defeito: o MESMO valor do marketplace vai para os dois.
        assertEquals(larguraNoBanco("cliente", "endereco"), larguraNoBanco("pedido", "endereco_entrega"),
                "o endereco do marketplace e gravado nos dois; tamanhos diferentes perdem o pedido");
    }

    @Test
    void oBAIRROdoPedidoCabeOmesmoQueOdoCliente() {
        assertEquals(larguraNoBanco("cliente", "bairro"), larguraNoBanco("pedido", "bairro_entrega"));
    }

    @Test
    void aDESCRICAOdoItemNaoTemLIMITE() {
        // Ela leva nome + complementos + observacao desde a v119 ("Acai 500ml (+ Granola) -- obs:
        // ..."). Medido na producao: a maior ja tem 327 caracteres, acima de qualquer varchar(255).
        // Se alguem trocar para varchar, todo item com adicional passa a derrubar o pedido.
        assertNull(larguraNoBanco("pedido_item", "descricao"),
                "pedido_item.descricao tem que continuar TEXT: ela cresce com complementos e observacao");
        assertNull(larguraNoBanco("pedido", "observacao"),
                "pedido.observacao tem que continuar TEXT: o marketplace escreve bastante nela");
    }

    @Test
    void oTESTEestaREALMENTEolhandoObanco() {
        // Sem isto, um erro no SQL faria larguraNoBanco devolver null para tudo e o teste principal
        // falharia por outro motivo -- ou, pior, uma mudanca no mapa faria ele medir nada.
        assertEquals(500, larguraNoBanco("cliente", "endereco"), "a V37 largou esta coluna para 500");
        assertNull(larguraNoBanco("pedido", "valor_total"), "coluna numerica nao tem limite de texto");
        assertTrue(oQueOcodigoAcredita().size() >= 13,
                "o mapa encolheu: alguem tirou coluna da conferencia");
    }
}
