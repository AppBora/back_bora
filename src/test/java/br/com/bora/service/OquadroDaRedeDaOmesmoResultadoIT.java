package br.com.bora.service;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.Produto;
import br.com.bora.entity.StatusPedido;
import br.com.bora.entity.UsuarioLoja;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * O quadro da rede dá o MESMO resultado depois de deixar de consultar loja por loja.
 *
 * <h2>Por que este arquivo existe, e nesta forma</h2>
 * <p>Em 21/10/2026, ao fechar o bloco do banco (v131), eu deixei este N+1 <b>registrado e não
 * feito</b>, com o motivo escrito: <i>"lá o laço não é só de consultas: ele carrega pedidos e itens
 * de cada loja para calcular ruptura de estoque e cancelamentos em memória. Virar consulta agregada é
 * reescrever o cálculo, e mexer em cálculo sem ter o número de antes para comparar é como eu mudaria
 * o painel sem perceber. Se o dono quiser, o jeito certo é primeiro congelar os números de hoje num
 * teste e só então reescrever."</i></p>
 *
 * <p>É isso que este arquivo faz. Os dois métodos abaixo — {@link #estoqueComOlacoAntigo} e
 * {@link #canceladosComOlacoAntigo} — são o código que estava no serviço, <b>linha por linha</b>,
 * rodando com uma consulta por loja. Cada teste compara a saída do serviço com a deles, sobre os
 * mesmos dados. Se o resultado divergir em qualquer campo, reprova.</p>
 *
 * <h2>O que mudou e o que NÃO mudou</h2>
 * <p>Mudou a quantidade de consultas: eram 3 por loja no estoque e 1 por loja nos cancelamentos, mais
 * um {@code findById} por vínculo no {@code minhasLojas}. Numa rede de 5 lojas, ~32 consultas por
 * dossiê.</p>
 *
 * <p><b>Não mudou nenhuma conta.</b> A divisão que dá a média por dia (2 casas, HALF_UP) e a que dá a
 * cobertura (1 casa, HALF_UP) continuam em Java, sobre os mesmos números. Empurrá-las para o SQL
 * mudaria valor na tela do dono, e o defeito era a quantidade de consultas — não o lugar da conta.</p>
 *
 * <p><b>Como rodar:</b></p>
 * <pre>
 * docker run -d --name bora-teste-pg -e POSTGRES_PASSWORD=teste -e POSTGRES_DB=bora -p 55432:5432 postgres:16
 * mvn -o test -Dtest='OquadroDaRedeDaOmesmoResultadoIT' -DfailIfNoTests=false
 * </pre>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:55432/bora",
        "spring.datasource.username=postgres",
        "spring.datasource.password=teste",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "marketplace.polling.habilitado=false"
})
class OquadroDaRedeDaOmesmoResultadoIT {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Long USUARIO = 987654L;
    private static final Long LOJA_A = 91001L;
    private static final Long LOJA_B = 91002L;

    @Autowired private AgenteRedeService servico;
    @Autowired private LojaRepository lojas;
    @Autowired private ProdutoRepository produtos;
    @Autowired private PedidoRepository pedidos;
    @Autowired private PedidoItemRepository itens;
    @Autowired private UsuarioLojaRepository vinculos;
    @Autowired private EntityManager em;

    /**
     * Usuário NÃO-administrador, com vínculo só às lojas deste teste.
     *
     * <p>De propósito: com {@code isAdminBora} o {@code minhasLojas} faz {@code lojas.findAll()} e
     * varreria o que outros ITs deixaram no banco de teste — o resultado dependeria da ordem em que
     * os testes rodaram.</p>
     */
    @MockBean private AuthContext ctx;

    @BeforeEach
    @Transactional
    void montar() {
        when(ctx.isAdminBora()).thenReturn(false);
        when(ctx.atual()).thenReturn(new BoraPrincipal(USUARIO, LOJA_A, "ADMINISTRADOR_LOJA", "rede@bora.com"));
        limpar();
        // `usuario_loja` tem chave estrangeira para `usuario`: sem esta linha o vínculo não entra, e
        // a primeira versão deste teste quebrou nos 10 casos por isso.
        em.createNativeQuery("insert into usuario (id, nome, email, senha_hash, papel, ativo, criado_em)"
                        + " values (?1, 'Dono da rede IT', ?2, 'x', 'ADMINISTRADOR_LOJA', true, now())"
                        + " on conflict (id) do nothing")
                .setParameter(1, USUARIO).setParameter(2, "rede-it-" + USUARIO + "@bora.test")
                .executeUpdate();
        criarLoja(LOJA_A, "Rede A");
        criarLoja(LOJA_B, "Rede B");
    }

    @AfterEach
    @Transactional
    void limpar() {
        for (Long id : List.of(LOJA_A, LOJA_B)) {
            em.createNativeQuery("delete from pedido_item where loja_id = ?1").setParameter(1, id).executeUpdate();
            em.createNativeQuery("delete from pedido where loja_id = ?1").setParameter(1, id).executeUpdate();
            em.createNativeQuery("delete from produto where loja_id = ?1").setParameter(1, id).executeUpdate();
            em.createNativeQuery("delete from usuario_loja where loja_id = ?1").setParameter(1, id).executeUpdate();
            em.createNativeQuery("delete from loja where id = ?1").setParameter(1, id).executeUpdate();
        }
        em.createNativeQuery("delete from usuario where id = ?1").setParameter(1, USUARIO).executeUpdate();
    }

    private void criarLoja(Long id, String nome) {
        em.createNativeQuery("insert into loja (id, nome, ativo) values (?1, ?2, true)")
                .setParameter(1, id).setParameter(2, nome).executeUpdate();
        UsuarioLoja v = new UsuarioLoja();
        v.setUsuarioId(USUARIO);
        v.setLojaId(id);
        vinculos.saveAndFlush(v);
    }

    private Produto produto(Long lojaId, String nome, Integer estoque, String preco) {
        Produto p = new Produto();
        p.lojaId = lojaId;
        p.nome = nome;
        p.preco = new BigDecimal(preco);
        p.estoque = estoque;
        p.estoqueMinimo = 2;
        p.ativo = true;
        return produtos.saveAndFlush(p);
    }

    private Pedido pedido(Long lojaId, StatusPedido status, int diasAtras, String valor) {
        Pedido p = new Pedido();
        p.lojaId = lojaId;
        p.status = status;
        p.valorTotal = new BigDecimal(valor);
        p.criadoEm = LocalDate.now(ZONE).minusDays(diasAtras).atTime(12, 0).atZone(ZONE).toOffsetDateTime();
        p.origem = "CARDAPIO";
        p.motivoCancelamento = status == StatusPedido.CANCELADO ? "cliente desistiu" : null;
        p.codigo = "P" + diasAtras + "-" + lojaId;
        return pedidos.saveAndFlush(p);
    }

    private void item(Pedido pedido, Produto produto, int qtd) {
        PedidoItem it = new PedidoItem();
        it.setLojaId(pedido.lojaId);
        it.setPedidoId(pedido.id);
        it.setProdutoId(produto.id);
        it.setDescricao(produto.nome);
        it.setQuantidade(qtd);
        it.setPrecoUnitario(produto.preco);
        itens.saveAndFlush(it);
    }

    // ==============================================================================================
    // O CÓDIGO ANTIGO, linha por linha, com uma consulta por loja. É o número que tem de ser mantido.
    // ==============================================================================================

    private List<Map<String, Object>> estoqueComOlacoAntigo(LocalDate inicio, LocalDate fim) {
        LocalDate ini = inicio == null ? LocalDate.now(ZONE).withDayOfMonth(1) : inicio;
        LocalDate f = fim == null ? LocalDate.now(ZONE) : fim;
        long dias = Math.max(1, ChronoUnit.DAYS.between(ini, f) + 1);
        OffsetDateTime corte = ini.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime fimExc = f.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Loja l : servico.minhasLojas()) {
            List<Pedido> vendas = pedidos.findByLojaIdAndCriadoEmAfterOrderByCriadoEmDesc(l.getId(), corte).stream()
                    .filter(p -> p.criadoEm != null && p.criadoEm.isBefore(fimExc) && p.status != StatusPedido.CANCELADO)
                    .toList();
            if (vendas.isEmpty()) continue;
            Map<Long, Integer> vendidoPorProduto = new HashMap<>();
            for (PedidoItem it : itens.findByLojaIdAndPedidoIdIn(l.getId(), vendas.stream().map(p -> p.id).toList())) {
                if (it.getProdutoId() == null) continue;
                vendidoPorProduto.merge(it.getProdutoId(), it.getQuantidade() == null ? 1 : it.getQuantidade(), Integer::sum);
            }
            for (Produto p : produtos.findByLojaIdOrderByNomeAsc(l.getId())) {
                if (p.estoque == null || !Boolean.TRUE.equals(p.ativo)) continue;
                int vendido = vendidoPorProduto.getOrDefault(p.id, 0);
                if (vendido == 0) continue;
                BigDecimal porDia = BigDecimal.valueOf(vendido).divide(BigDecimal.valueOf(dias), 2, RoundingMode.HALF_UP);
                if (porDia.signum() == 0) continue;
                BigDecimal cobertura = BigDecimal.valueOf(p.estoque).divide(porDia, 1, RoundingMode.HALF_UP);
                if (cobertura.compareTo(BigDecimal.valueOf(7)) > 0) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("loja", l.getNome());
                m.put("lojaId", l.getId());
                m.put("produto", p.nome);
                m.put("precoUnitario", p.preco);
                m.put("estoque", p.estoque);
                m.put("estoqueMinimo", p.estoqueMinimo);
                m.put("vendidoNoPeriodo", vendido);
                m.put("mediaPorDia", porDia);
                m.put("diasDeCobertura", cobertura);
                out.add(m);
            }
        }
        out.sort(Comparator.comparing(m -> (BigDecimal) m.get("diasDeCobertura")));
        return out.size() > 15 ? out.subList(0, 15) : out;
    }

    private List<Map<String, Object>> canceladosComOlacoAntigo(LocalDate inicio, LocalDate fim) {
        LocalDate ini = inicio == null ? LocalDate.now(ZONE).withDayOfMonth(1) : inicio;
        LocalDate f = fim == null ? LocalDate.now(ZONE) : fim;
        OffsetDateTime corte = ini.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime fimExc = f.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Loja l : servico.minhasLojas()) {
            for (Pedido p : pedidos.findByLojaIdAndCriadoEmAfterOrderByCriadoEmDesc(l.getId(), corte)) {
                if (p.criadoEm == null || !p.criadoEm.isBefore(fimExc) || p.status != StatusPedido.CANCELADO) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pedidoId", p.id);
                m.put("loja", l.getNome());
                m.put("lojaId", l.getId());
                m.put("codigo", p.codigo);
                m.put("valor", p.valorTotal);
                m.put("canal", p.origem);
                m.put("motivo", p.motivoCancelamento);
                m.put("clienteTelefone", p.clienteTelefone);
                m.put("quando", p.canceladoEm == null ? p.criadoEm : p.canceladoEm);
                out.add(m);
                if (out.size() >= 30) return out;
            }
        }
        return out;
    }

    /** Compara campo por campo, dizendo exatamente onde divergiu. */
    private void iguais(String cenario, List<Map<String, Object>> antigo, List<Map<String, Object>> novo) {
        assertEquals(antigo.size(), novo.size(),
                "quantidade de linhas mudou em \"" + cenario + "\": antigo=" + antigo.size()
                        + " novo=" + novo.size() + "\nantigo: " + antigo + "\nnovo: " + novo);
        for (int i = 0; i < antigo.size(); i++) {
            Map<String, Object> a = antigo.get(i);
            Map<String, Object> n = novo.get(i);
            assertEquals(a.keySet(), n.keySet(), "os campos mudaram na linha " + i + " de \"" + cenario + "\"");
            for (String k : a.keySet()) {
                Object va = a.get(k), vn = n.get(k);
                if (va instanceof BigDecimal ba && vn instanceof BigDecimal bn) {
                    assertEquals(0, ba.compareTo(bn),
                            "\"" + cenario + "\" linha " + i + " campo " + k + ": " + ba + " -> " + bn);
                } else {
                    assertEquals(va, vn, "\"" + cenario + "\" linha " + i + " campo " + k);
                }
            }
        }
    }

    // ---------------------------------------------------------------- estoque em risco
    @Test
    @Transactional
    void oESTOQUEemRISCOdaOmesmoQUADRO() {
        LocalDate hoje = LocalDate.now(ZONE);
        LocalDate ini = hoje.minusDays(9);

        Produto acai = produto(LOJA_A, "Acai 500ml", 10, "25.00");     // 20 vendidos em 10 dias -> 2/dia -> 5 dias
        Produto copo = produto(LOJA_A, "Copo 300ml", 100, "17.00");    // 10 vendidos -> 1/dia -> 100 dias (fora)
        Produto pizza = produto(LOJA_B, "Pizza G", 3, "60.00");        // 10 vendidos -> 1/dia -> 3 dias

        Pedido p1 = pedido(LOJA_A, StatusPedido.ENTREGUE, 5, "100.00");
        item(p1, acai, 20);
        item(p1, copo, 10);
        Pedido p2 = pedido(LOJA_B, StatusPedido.ENTREGUE, 3, "120.00");
        item(p2, pizza, 10);

        iguais("duas lojas, tres produtos", estoqueComOlacoAntigo(ini, hoje), servico.estoqueEmRisco(ini, hoje));
        assertEquals(2, servico.estoqueEmRisco(ini, hoje).size(), "so os dois com cobertura <= 7 dias");
    }

    @Test
    @Transactional
    void oCANCELADOnaoCONTAcomoVENDAnoEstoque() {
        // O laço antigo filtrava CANCELADO antes de somar. Se a consulta nova não filtrasse, o
        // produto apareceria com venda que não houve e a cobertura sairia menor — alarme falso de
        // ruptura.
        LocalDate hoje = LocalDate.now(ZONE);
        LocalDate ini = hoje.minusDays(9);
        Produto acai = produto(LOJA_A, "Acai 500ml", 10, "25.00");

        Pedido bom = pedido(LOJA_A, StatusPedido.ENTREGUE, 4, "50.00");
        item(bom, acai, 2);
        Pedido ruim = pedido(LOJA_A, StatusPedido.CANCELADO, 4, "500.00");
        item(ruim, acai, 100);

        iguais("venda e cancelado no mesmo produto", estoqueComOlacoAntigo(ini, hoje),
                servico.estoqueEmRisco(ini, hoje));
    }

    @Test
    @Transactional
    void aMEDIAeACOBERTURAmantemOarredondamento() {
        // 7 vendidos em 3 dias = 2,333... -> 2,33 por dia (2 casas). 5 de estoque / 2,33 = 2,145...
        // -> 2,1 dias (1 casa). Mover a divisão para o SQL mudaria estes dois números.
        LocalDate hoje = LocalDate.now(ZONE);
        LocalDate ini = hoje.minusDays(2);
        Produto p = produto(LOJA_A, "Acai 500ml", 5, "25.00");
        Pedido ped = pedido(LOJA_A, StatusPedido.ENTREGUE, 1, "70.00");
        item(ped, p, 7);

        List<Map<String, Object>> novo = servico.estoqueEmRisco(ini, hoje);
        iguais("arredondamento de 7/3", estoqueComOlacoAntigo(ini, hoje), novo);
        assertEquals(0, new BigDecimal("2.33").compareTo((BigDecimal) novo.get(0).get("mediaPorDia")));
        assertEquals(0, new BigDecimal("2.1").compareTo((BigDecimal) novo.get(0).get("diasDeCobertura")));
    }

    @Test
    @Transactional
    void produtoSEMcontroleDEestoqueFICAfora() {
        LocalDate hoje = LocalDate.now(ZONE);
        LocalDate ini = hoje.minusDays(9);
        Produto semControle = produto(LOJA_A, "Agua", null, "5.00");
        Pedido ped = pedido(LOJA_A, StatusPedido.ENTREGUE, 2, "50.00");
        item(ped, semControle, 50);

        iguais("produto sem estoque cadastrado", estoqueComOlacoAntigo(ini, hoje),
                servico.estoqueEmRisco(ini, hoje));
        assertTrue(servico.estoqueEmRisco(ini, hoje).isEmpty());
    }

    @Test
    @Transactional
    void lojaSEMvendaNOperiodoFICAfora() {
        LocalDate hoje = LocalDate.now(ZONE);
        LocalDate ini = hoje.minusDays(9);
        produto(LOJA_B, "Pizza G", 1, "60.00");          // estoque baixo, mas sem venda
        Produto acai = produto(LOJA_A, "Acai 500ml", 10, "25.00");
        Pedido ped = pedido(LOJA_A, StatusPedido.ENTREGUE, 2, "50.00");
        item(ped, acai, 20);

        iguais("loja B sem venda", estoqueComOlacoAntigo(ini, hoje), servico.estoqueEmRisco(ini, hoje));
    }

    @Test
    @Transactional
    void aORDEMehPORcoberturaCRESCENTE() {
        LocalDate hoje = LocalDate.now(ZONE);
        LocalDate ini = hoje.minusDays(9);
        Produto folgado = produto(LOJA_A, "A folgado", 60, "10.00");   // 10 vendidos -> 1/dia -> 60d (fora)
        Produto aperta = produto(LOJA_A, "B aperta", 6, "10.00");      // 10 vendidos -> 1/dia -> 6d
        Produto critico = produto(LOJA_A, "C critico", 1, "10.00");    // 10 vendidos -> 1/dia -> 1d
        Pedido ped = pedido(LOJA_A, StatusPedido.ENTREGUE, 2, "300.00");
        item(ped, folgado, 10);
        item(ped, aperta, 10);
        item(ped, critico, 10);

        List<Map<String, Object>> novo = servico.estoqueEmRisco(ini, hoje);
        iguais("tres coberturas diferentes", estoqueComOlacoAntigo(ini, hoje), novo);
        assertEquals("C critico", novo.get(0).get("produto"), "o mais urgente vem primeiro");
        assertEquals("B aperta", novo.get(1).get("produto"));
    }

    // ---------------------------------------------------------------- cancelamentos
    @Test
    @Transactional
    void osCANCELAMENTOSsaoOSmesmos_naMESMAordem() {
        LocalDate hoje = LocalDate.now(ZONE);
        LocalDate ini = hoje.minusDays(9);
        pedido(LOJA_A, StatusPedido.CANCELADO, 1, "30.00");
        pedido(LOJA_A, StatusPedido.CANCELADO, 5, "40.00");
        pedido(LOJA_B, StatusPedido.CANCELADO, 2, "50.00");
        pedido(LOJA_A, StatusPedido.ENTREGUE, 3, "60.00");

        iguais("tres cancelados em duas lojas", canceladosComOlacoAntigo(ini, hoje),
                servico.cancelamentosRecentes(ini, hoje));
        assertEquals(3, servico.cancelamentosRecentes(ini, hoje).size());
    }

    @Test
    @Transactional
    void oTETOde30respeitaATRAVESSIAporLOJA() {
        // O ponto mais fácil de errar: o laço antigo enchia com os da PRIMEIRA loja antes de olhar a
        // segunda, e parava em 30. Um `limit 30` no SQL devolveria os 30 mais recentes da REDE, que é
        // outro conjunto. 25 na loja A e 10 na B: os 5 últimos têm de vir da B, e não os mais novos.
        LocalDate hoje = LocalDate.now(ZONE);
        LocalDate ini = hoje.minusDays(40);
        for (int i = 1; i <= 25; i++) pedido(LOJA_A, StatusPedido.CANCELADO, i, "10.00");
        for (int i = 1; i <= 10; i++) pedido(LOJA_B, StatusPedido.CANCELADO, i, "20.00");

        List<Map<String, Object>> antigo = canceladosComOlacoAntigo(ini, hoje);
        List<Map<String, Object>> novo = servico.cancelamentosRecentes(ini, hoje);

        assertEquals(30, antigo.size(), "o laco antigo para em 30");
        iguais("25 na loja A e 10 na B, teto de 30", antigo, novo);
        long daA = novo.stream().filter(m -> LOJA_A.equals(m.get("lojaId"))).count();
        assertEquals(25, daA, "os 25 da primeira loja tem de entrar todos antes da segunda");
    }

    @Test
    @Transactional
    void foraDOperiodoNAOentra() {
        LocalDate hoje = LocalDate.now(ZONE);
        LocalDate ini = hoje.minusDays(5);
        pedido(LOJA_A, StatusPedido.CANCELADO, 2, "30.00");    // dentro
        pedido(LOJA_A, StatusPedido.CANCELADO, 40, "30.00");   // antes do inicio
        pedido(LOJA_A, StatusPedido.CANCELADO, 0, "30.00");    // hoje: dentro

        iguais("um fora da janela", canceladosComOlacoAntigo(ini, hoje),
                servico.cancelamentosRecentes(ini, hoje));
        assertEquals(2, servico.cancelamentosRecentes(ini, hoje).size());
    }

    /** A trava contra o N+1 voltar: nenhuma consulta por loja dentro dos dois métodos. */
    // @Transactional mesmo so lendo arquivo: num @SpringBootTest a transacao vem do metodo de TESTE,
    // e sem ela o `limpar()` do @BeforeEach/@AfterEach estoura com TransactionRequiredException. Ja
    // caiu nisso o IT do contador de sumidos, ontem.
    @Test
    @Transactional
    void oSERVICOnaoVOLTAaConsultarLOJAporLOJA() {
        String fonte = assertDoesNotThrow(() -> java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/br/com/bora/service/AgenteRedeService.java"),
                java.nio.charset.StandardCharsets.UTF_8));
        List<String> codigo = new ArrayList<>();
        for (String linha : fonte.split("\n", -1)) {
            String t = linha.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            codigo.add(linha);
        }
        String limpo = String.join("\n", codigo);
        for (String porLoja : List.of(
                "findByLojaIdAndCriadoEmAfterOrderByCriadoEmDesc",  // pedidos, 1 por loja
                "findByLojaIdAndPedidoIdIn",                        // itens, 1 por loja
                "findByLojaIdOrderByNomeAsc",                       // produtos, 1 por loja
                "lojas.findById(")) {                               // 1 por vinculo
            assertFalse(limpo.contains(porLoja),
                    "voltou consulta por loja no quadro da rede (" + porLoja + "): numa rede de 5 "
                            + "lojas isso sao ~32 consultas por dossie");
        }
    }

    @Test
    @Transactional
    void semCANCELAMENTOdaLISTAvazia() {
        LocalDate hoje = LocalDate.now(ZONE);
        pedido(LOJA_A, StatusPedido.ENTREGUE, 1, "30.00");

        iguais("nenhum cancelado", canceladosComOlacoAntigo(hoje.minusDays(9), hoje),
                servico.cancelamentosRecentes(hoje.minusDays(9), hoje));
        assertTrue(servico.cancelamentosRecentes(hoje.minusDays(9), hoje).isEmpty());
    }
}
