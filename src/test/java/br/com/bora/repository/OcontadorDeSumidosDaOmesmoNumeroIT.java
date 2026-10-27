package br.com.bora.repository;

import br.com.bora.entity.Cliente;
import br.com.bora.entity.Pedido;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A consulta agregada conta os mesmos "sumidos" que o laço por cliente contava.
 *
 * <h2>Por que este teste existe, e nesta forma</h2>
 * <p>O {@code IaService.gerenteResumo} carregava todos os clientes da loja e fazia <b>uma consulta
 * por cliente</b> para saber a data do último pedido dele, só para produzir um número. Trocar isso
 * por um {@code group by ... having max(criadoEm)} é reescrever um cálculo — e em 21/10/2026, ao
 * fechar o bloco do banco (v131), eu deixei registrado o jeito certo de fazer isso:
 * <b>primeiro congelar o número de antes num teste, e só então reescrever.</b> É o que este arquivo
 * faz: ele calcula das <b>duas</b> formas, sobre os mesmos dados, e exige que o número seja igual.</p>
 *
 * <p>O laço ingênuo abaixo não é uma reimplementação minha do que eu achei que o código fazia: é a
 * regra que estava escrita — "último pedido antes de hoje−21 e depois de hoje−120" — rodando com uma
 * consulta por cliente, como antes.</p>
 *
 * <h2>Honestidade sobre o tamanho</h2>
 * <p>Na produção de 27/10/2026 há <b>7 clientes no total</b> e <b>1 loja</b> com o Módulo IA: hoje o
 * N+1 custava 7 consultas e nenhum milissegundo que se note. Os 2.232 ms citados na varredura vieram
 * de uma carga sintética de agente, não da produção. É armadilha de escala, não página lenta.</p>
 *
 * <p><b>Como rodar:</b></p>
 * <pre>
 * docker run -d --name bora-teste-pg -e POSTGRES_PASSWORD=teste -e POSTGRES_DB=bora -p 55432:5432 postgres:16
 * mvn -o test -Dtest='OcontadorDeSumidosDaOmesmoNumeroIT' -DfailIfNoTests=false
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
class OcontadorDeSumidosDaOmesmoNumeroIT {

    private static final Long LOJA = 90777L;
    private static final int DIAS_SUMIDO = 21;   // o mesmo do IaService

    @Autowired private EntityManager em;
    @Autowired private PedidoRepository pedidos;
    @Autowired private ClienteRepository clientes;

    private OffsetDateTime hoje0() {
        return OffsetDateTime.now(java.time.ZoneId.of("America/Sao_Paulo"))
                .withHour(0).withMinute(0).withSecond(0).withNano(0);
    }

    @AfterEach
    @Transactional
    void limpar() {
        em.createNativeQuery("delete from pedido where loja_id = ?1").setParameter(1, LOJA).executeUpdate();
        em.createNativeQuery("delete from cliente where loja_id = ?1").setParameter(1, LOJA).executeUpdate();
    }

    private Cliente cliente(String nome) {
        Cliente c = new Cliente();
        c.lojaId = LOJA;
        c.nome = nome;
        c.telefone = "1599" + (int) (Math.random() * 900000 + 100000);
        return clientes.saveAndFlush(c);
    }

    private void pedidoEm(Cliente c, int diasAtras) {
        Pedido p = new Pedido();
        p.lojaId = LOJA;
        p.clienteId = c.id;
        p.valorTotal = new BigDecimal("30.00");
        p.criadoEm = hoje0().minusDays(diasAtras);
        pedidos.saveAndFlush(p);
    }

    /**
     * O jeito ANTIGO: uma consulta por cliente, com a regra exatamente como estava escrita.
     *
     * <p>É este número que a consulta agregada tem de reproduzir.</p>
     */
    private long contarComOlacoAntigo() {
        OffsetDateTime hoje0 = hoje0();
        return clientes.findByLojaIdOrderByNomeAsc(LOJA).stream().filter(c -> {
            OffsetDateTime u = pedidos.findFirstByLojaIdAndClienteIdOrderByCriadoEmDesc(LOJA, c.id)
                    .map(p -> p.criadoEm).orElse(null);
            return u != null && u.isBefore(hoje0.minusDays(DIAS_SUMIDO)) && u.isAfter(hoje0.minusDays(120));
        }).count();
    }

    private long contarComAconsultaNova() {
        return pedidos.contaClientesComUltimoPedidoEntre(LOJA, hoje0().minusDays(120),
                hoje0().minusDays(DIAS_SUMIDO));
    }

    /** As duas contas, sobre os mesmos dados, têm de dar igual. */
    private void asDuasBatem(String cenario) {
        long antigo = contarComOlacoAntigo();
        long novo = contarComAconsultaNova();
        assertEquals(antigo, novo, "o número mudou em \"" + cenario + "\": o laço dava " + antigo
                + " e a consulta dá " + novo);
    }

    // ---------------------------------------------------------------- o caso de uso
    @Test
    @Transactional
    void oNUMEROnaoMUDAnoCASOcomum() {
        Cliente sumido = cliente("Sumido");      pedidoEm(sumido, 40);
        Cliente recente = cliente("Recente");    pedidoEm(recente, 3);
        Cliente antigao = cliente("Antigao");    pedidoEm(antigao, 200);
        cliente("NuncaPediu");

        asDuasBatem("1 sumido, 1 recente, 1 antigo demais, 1 sem pedido");
        assertEquals(1, contarComAconsultaNova(), "so o Sumido conta");
    }

    @Test
    @Transactional
    void oCLIENTEcontaUMAvezSOmesmoCOMvariosPEDIDOS() {
        // O `group by` podia contar linha de pedido em vez de cliente -- erro classico, e e por isso
        // que ele conta sobre uma subconsulta agrupada.
        Cliente c = cliente("Muitos");
        pedidoEm(c, 40);
        pedidoEm(c, 50);
        pedidoEm(c, 60);

        asDuasBatem("um cliente com tres pedidos antigos");
        assertEquals(1, contarComAconsultaNova());
    }

    @Test
    @Transactional
    void oQUEvaleEHoULTIMOpedido_eNAOqualquerUM() {
        // Cliente com pedido antigo E pedido de ontem NAO esta sumido. Se a consulta olhasse
        // "existe pedido na janela" em vez do MAXIMO, ele entraria errado.
        Cliente c = cliente("Voltou");
        pedidoEm(c, 40);
        pedidoEm(c, 1);

        asDuasBatem("cliente que voltou");
        assertEquals(0, contarComAconsultaNova(), "ele voltou: nao esta sumido");
    }

    // ---------------------------------------------------------------- as bordas da janela
    @Test
    @Transactional
    void asBORDASdaJANELAdaoOmesmoQUEoLACOdava() {
        // As bordas são onde um `>=` trocado por `>` muda o número sem ninguém ver. O laço usava
        // `isBefore` e `isAfter`, os dois exclusivos; a consulta usa `<` e `>`, iguais.
        for (int dias : new int[] { DIAS_SUMIDO - 1, DIAS_SUMIDO, DIAS_SUMIDO + 1, 119, 120, 121 }) {
            limpar();
            Cliente c = cliente("Borda" + dias);
            pedidoEm(c, dias);
            asDuasBatem("ultimo pedido ha " + dias + " dias");
        }
    }

    @Test
    @Transactional
    void lojaVAZIAdaZEROnasDUAS() {
        asDuasBatem("loja sem cliente nenhum");
        assertEquals(0, contarComAconsultaNova());
    }

    @Test
    @Transactional
    void pedidoSEMclienteNAOconta_eNAOexplode() {
        // Pedido de balcao sem cliente: `cliente_id` nulo. Sem o filtro, o group by criaria um grupo
        // do nulo e o numero sairia um a mais.
        Pedido p = new Pedido();
        p.lojaId = LOJA;
        p.clienteId = null;
        p.valorTotal = new BigDecimal("10.00");
        p.criadoEm = hoje0().minusDays(40);
        pedidos.saveAndFlush(p);

        asDuasBatem("pedido de balcao sem cliente");
        assertEquals(0, contarComAconsultaNova());
    }

    // ---------------------------------------------------------------- o mapa do outro laço
    @Test
    @Transactional
    void oMAPAdoOUTROlacoTRAZoMESMOultimoPEDIDO() {
        // O `recuperarClientes` trocou a consulta-por-cliente por este mapa. Ele tem de trazer a
        // mesma data que a consulta antiga trazia, senão a mensagem de recuperação vai para o cliente
        // errado.
        Cliente a = cliente("A"); pedidoEm(a, 40); pedidoEm(a, 10);
        Cliente b = cliente("B"); pedidoEm(b, 55);
        cliente("SemPedido");

        var mapa = new java.util.HashMap<Long, OffsetDateTime>();
        for (Object[] linha : pedidos.ultimoPedidoPorCliente(LOJA)) {
            mapa.put((Long) linha[0], (OffsetDateTime) linha[1]);
        }

        for (Cliente c : clientes.findByLojaIdOrderByNomeAsc(LOJA)) {
            OffsetDateTime antigo = pedidos.findFirstByLojaIdAndClienteIdOrderByCriadoEmDesc(LOJA, c.id)
                    .map(x -> x.criadoEm).orElse(null);
            OffsetDateTime doMapa = mapa.get(c.id);
            if (antigo == null) {
                assertNull(doMapa, "cliente sem pedido nao devia estar no mapa: " + c.nome);
            } else {
                assertNotNull(doMapa, "faltou no mapa: " + c.nome);
                assertEquals(0, antigo.toInstant().compareTo(doMapa.toInstant()),
                        "data diferente para " + c.nome + ": laco " + antigo + " mapa " + doMapa);
            }
        }
    }

    // ---------------------------------------------------------------- e não vaza entre lojas
    @Test
    @Transactional
    void naoCONTAclienteDEoutraLOJA() {
        Cliente meu = cliente("Meu"); pedidoEm(meu, 40);

        Cliente outro = new Cliente();
        outro.lojaId = LOJA + 1;
        outro.nome = "De outra loja";
        outro.telefone = "15999999999";
        outro = clientes.saveAndFlush(outro);
        Pedido p = new Pedido();
        p.lojaId = LOJA + 1;
        p.clienteId = outro.id;
        p.valorTotal = new BigDecimal("30.00");
        p.criadoEm = hoje0().minusDays(40);
        pedidos.saveAndFlush(p);

        assertEquals(1, contarComAconsultaNova(), "contou cliente de outra loja");

        em.createNativeQuery("delete from pedido where loja_id = ?1").setParameter(1, LOJA + 1).executeUpdate();
        em.createNativeQuery("delete from cliente where loja_id = ?1").setParameter(1, LOJA + 1).executeUpdate();
    }

    /** Uma consulta, e não uma por cliente — o ponto do conserto. */
    @Test
    void aFONTEnaoVOLTAaConsultarPORcliente() {
        String fonte = assertDoesNotThrow(() -> java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/br/com/bora/service/IaService.java"),
                java.nio.charset.StandardCharsets.UTF_8));
        List<String> codigo = new java.util.ArrayList<>();
        for (String linha : fonte.split("\n", -1)) {
            String t = linha.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            codigo.add(linha);
        }
        assertFalse(String.join("\n", codigo).contains("findFirstByLojaIdAndClienteIdOrderByCriadoEmDesc"),
                "voltou a consulta por cliente no Modulo IA: 1 + N consultas para um numero");
    }
}
