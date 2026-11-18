package br.com.bora.service;

import br.com.bora.entity.ConfigPlataforma;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.ConfigPlataformaRepository;
import br.com.bora.repository.LojaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A fila de eventos grava a EMPRESA da loja — contra um Postgres de verdade.
 *
 * <h2>Por que isto precisa de banco, e o unitário não basta</h2>
 * <p>Porque a empresa não é calculada em Java: ela sai de uma <b>subconsulta dentro do próprio
 * {@code INSERT}</b>. Em Java não existe valor nenhum para afirmar nada sobre — quem resolve
 * {@code (select empresa_id from loja where id = ?)} é o PostgreSQL. O teste unitário
 * ({@code OoutboxNaoAtrapalhaAvendaTest}) trava a <b>forma</b>: que o SQL lê da loja e que
 * {@code lojaId} vai nas duas posições. Este trava o <b>resultado</b>.</p>
 *
 * <h2>O defeito que isto fecha</h2>
 * <p>Até 18/11/2026 o INSERT gravava {@code null} literal em {@code empresa_id}. O caso que mostra
 * o preço disso está em produção: as lojas 18, 19, 20, 21 e 22 são a rede da Zirá e pertencem a
 * <b>uma empresa só</b> (id 3, medido no dump das 18:26 UTC). Sem a coluna, quem consome a fila
 * veria cinco clientes diferentes.</p>
 *
 * <h2>A garantia que este arquivo também mede, e que é mais importante que a coluna</h2>
 * <p>Que preencher a empresa <b>não deu à fila o poder de recusar uma venda</b>. A V74 deixou a
 * coluna sem {@code NOT NULL} e sem chave estrangeira de propósito, porque este INSERT mora na
 * transação da venda e no PostgreSQL uma instrução que falha envenena a transação inteira. Os dois
 * testes de lacuna abaixo — loja sem empresa e loja que não existe — são o que prova que a
 * subconsulta devolve NULL em vez de estourar.</p>
 *
 * <p><b>Como rodar:</b> precisa do Postgres de teste.</p>
 * <pre>
 * bash testes-de-integracao.sh
 * </pre>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:55432/bora",
        "spring.datasource.username=postgres",
        "spring.datasource.password=teste",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class AfilaDEeventosGRAVAaEMPRESAdaLOJAIT {

    /** Tudo que este arquivo cria carrega esta marca, para a limpeza não encostar em nada real. */
    private static final String MARCA = "zz-teste-empresa";

    @Autowired private PublicadorDeEventos publicador;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LojaRepository lojas;
    @Autowired private EmpresaService empresas;
    @Autowired private ConfigPlataformaRepository configs;

    /** O valor da chave antes de eu mexer, para devolver exatamente como estava. */
    private String chaveComoEstava;
    private boolean chaveExistia;

    @BeforeEach
    void ligarAfila() {
        configs.findById(PublicadorDeEventos.CHAVE_LIGA).ifPresentOrElse(c -> {
            chaveExistia = true;
            chaveComoEstava = c.getValor();
        }, () -> chaveExistia = false);

        // Pela MESMA porta que o painel usa (o repositório, com o conversor de cifragem no meio),
        // não por SQL cru: escrever a coluna à mão passaria por fora do conversor e o teste
        // mediria um caminho que a aplicação não usa.
        ConfigPlataforma c = configs.findById(PublicadorDeEventos.CHAVE_LIGA)
                .orElseGet(() -> {
                    ConfigPlataforma nova = new ConfigPlataforma();
                    nova.setChave(PublicadorDeEventos.CHAVE_LIGA);
                    return nova;
                });
        c.setValor("true");
        c.setAtualizadoEm(OffsetDateTime.now());
        configs.save(c);
        publicador.forcarReleituraDaChave();
    }

    @AfterEach
    void limpar() {
        jdbc.update("delete from evento_outbox where tipo like ?", MARCA + "%");
        jdbc.update("delete from loja where nome like ?", MARCA + "%");
        jdbc.update("delete from empresa where razao_social like ?", MARCA + "%");

        if (chaveExistia) {
            ConfigPlataforma c = configs.findById(PublicadorDeEventos.CHAVE_LIGA).orElseThrow();
            c.setValor(chaveComoEstava);
            configs.save(c);
        } else {
            configs.deleteById(PublicadorDeEventos.CHAVE_LIGA);
        }
        publicador.forcarReleituraDaChave();
    }

    // ----------------------------------------------------------------- utilidades

    private Loja lojaCom(Long empresaId, String sufixo) {
        Loja l = new Loja();
        l.setNome(MARCA + " " + sufixo);
        l.empresaId = empresaId;
        return lojas.save(l);
    }

    private Pedido pedidoDa(Long lojaId) {
        Pedido p = new Pedido();
        // O pedido NÃO precisa existir na tabela `pedido`: a V74 deixou a fila sem chave estrangeira
        // justamente para ela não poder recusar a gravação da venda. Se algum dia alguém puser uma
        // FK aqui, este teste passa a falhar — e é bom que falhe.
        p.id = 900_000L + (long) (Math.random() * 90_000);
        p.lojaId = lojaId;
        p.codigo = "#" + p.id;
        p.status = StatusPedido.RECEBIDO;
        p.valorTotal = new BigDecimal("24.90");
        p.criadoEm = OffsetDateTime.now();
        return p;
    }

    /** A empresa gravada no evento daquele pedido, ou null. */
    private Long empresaGravadaDoPedido(Long pedidoId) {
        return jdbc.queryForObject(
                "select empresa_id from evento_outbox where agregado_id = ? and tipo like ?",
                Long.class, pedidoId, MARCA + "%");
    }

    private long eventosDoPedido(Long pedidoId) {
        return jdbc.queryForObject(
                "select count(*) from evento_outbox where agregado_id = ? and tipo like ?",
                Long.class, pedidoId, MARCA + "%");
    }

    // ----------------------------------------------------------------- a premissa

    /**
     * A PREMISSA, antes de qualquer afirmação: este arquivo consegue distinguir uma empresa gravada
     * de um NULL.
     *
     * <p>Sem esta medição, um teste que lesse sempre {@code null} por algum defeito da própria
     * leitura passaria nos casos de lacuna e falharia no caso bom — e eu poderia "consertar" o lado
     * errado. Aqui eu gravo as duas coisas <b>à mão</b>, pelo caminho que não passa pelo publicador,
     * e confiro que a leitura responde diferente.</p>
     */
    @Test
    void aLeituraDESTEarquivoSABEdistinguirEmpresaGRAVADAdeNULA() {
        Long empresa = empresas.paraDocumento(null, MARCA + " premissa").getId();
        Loja l = lojaCom(empresa, "premissa");

        jdbc.update("""
                insert into evento_outbox
                  (tipo, versao_schema, empresa_id, loja_id, agregado_tipo, agregado_id,
                   payload, ocorrido_em, data_operacao)
                values (?, '0.0.0', ?, ?, 'pedido', 111111, '{}'::jsonb, now(), current_date)
                """, MARCA + "-com", empresa, l.getId());
        jdbc.update("""
                insert into evento_outbox
                  (tipo, versao_schema, empresa_id, loja_id, agregado_tipo, agregado_id,
                   payload, ocorrido_em, data_operacao)
                values (?, '0.0.0', null, ?, 'pedido', 222222, '{}'::jsonb, now(), current_date)
                """, MARCA + "-sem", l.getId());

        assertEquals(empresa, empresaGravadaDoPedido(111111L), "gravei a empresa e tenho de ler ela");
        assertNull(empresaGravadaDoPedido(222222L), "gravei null e tenho de ler null");
    }

    // ----------------------------------------------------------------- o caso que importa

    /**
     * O CASO BOM: a venda de uma loja com empresa grava a empresa. É este teste que mata o defeito
     * — com o {@code null} literal de volta no INSERT, ele falha.
     */
    @Test
    void aVendaDEumaLOJAcomEMPRESAgravaAempresaNOevento() {
        Long empresa = empresas.paraDocumento(null, MARCA + " dona").getId();
        Loja l = lojaCom(empresa, "loja com empresa");
        Pedido p = pedidoDa(l.getId());

        publicador.dePedido(MARCA + "-PedidoRecebido", p, Map.of("canal", "PAINEL"));

        assertEquals(1L, eventosDoPedido(p.id), "gravou exatamente um evento");
        assertEquals(empresa, empresaGravadaDoPedido(p.id),
                "a empresa do evento tem de ser a empresa da loja — e era null antes de 18/11/2026");
    }

    /**
     * O CASO DA REDE: duas unidades da mesma dona geram eventos com a MESMA empresa e lojas
     * diferentes. É a forma exata das lojas 18 a 22 em produção, e é por isso que a coluna existe:
     * sem ela, o ERP veria duas vendas de dois clientes em vez de duas unidades de um.
     */
    @Test
    void duasUNIDADESdaMESMAdonaSAEMcomAmesmaEMPRESAelojasDIFERENTES() {
        Long empresa = empresas.paraDocumento(null, MARCA + " rede").getId();
        Loja unidadeA = lojaCom(empresa, "unidade A");
        Loja unidadeB = lojaCom(empresa, "unidade B");

        Pedido pa = pedidoDa(unidadeA.getId());
        Pedido pb = pedidoDa(unidadeB.getId());
        publicador.dePedido(MARCA + "-PedidoRecebido", pa, Map.of());
        publicador.dePedido(MARCA + "-PedidoRecebido", pb, Map.of());

        assertEquals(empresa, empresaGravadaDoPedido(pa.id));
        assertEquals(empresa, empresaGravadaDoPedido(pb.id));
        assertNotEquals(unidadeA.getId(), unidadeB.getId(), "sao lojas distintas");

        Long lojaA = jdbc.queryForObject(
                "select loja_id from evento_outbox where agregado_id = ?", Long.class, pa.id);
        Long lojaB = jdbc.queryForObject(
                "select loja_id from evento_outbox where agregado_id = ?", Long.class, pb.id);
        assertEquals(unidadeA.getId(), lojaA, "a loja do evento nao pode ter trocado de lugar com a "
                + "empresa: `lojaId` vai duas vezes nos parametros, e essa e a troca que o erro faria");
        assertEquals(unidadeB.getId(), lojaB);
    }

    // ----------------------------------------------------------------- as lacunas NÃO derrubam

    /**
     * Loja sem empresa no cadastro: o evento sai com empresa nula e <b>a venda segue</b>. É a
     * garantia da V74, e é mais importante que a coluna estar preenchida — lacuna de cadastro é
     * barata, venda perdida não.
     */
    @Test
    void lojaSEMempresaGRAVAeventoCOMempresaNULAeNAOderrubaAvenda() {
        Loja l = lojaCom(null, "loja orfa");
        Pedido p = pedidoDa(l.getId());

        assertDoesNotThrow(() -> publicador.dePedido(MARCA + "-PedidoRecebido", p, Map.of()));

        assertEquals(1L, eventosDoPedido(p.id), "o evento foi gravado mesmo sem empresa");
        assertNull(empresaGravadaDoPedido(p.id));
    }

    /**
     * Loja que NÃO EXISTE: a subconsulta não acha nada e devolve NULL. Não é erro.
     *
     * <p>Este é o teste que prova que eu não dei à fila o poder de recusar uma venda. Uma chave
     * estrangeira aqui transformaria um id de loja errado — ou uma loja apagada no meio da operação
     * — em venda perdida, porque no PostgreSQL a instrução que falha envenena a transação inteira
     * e este INSERT mora na transação da venda.</p>
     */
    @Test
    void lojaQUEnaoEXISTEnaoESTOURAeOeventoSAIcomEmpresaNULA() {
        Pedido p = pedidoDa(987_654_321L); // não existe, e não vai existir

        assertDoesNotThrow(() -> publicador.dePedido(MARCA + "-PedidoRecebido", p, Map.of()));

        assertEquals(1L, eventosDoPedido(p.id));
        assertNull(empresaGravadaDoPedido(p.id));
    }

    // ----------------------------------------------------------------- o que a tela mostra

    /**
     * A contagem de eventos sem empresa que a tela mostra conta de verdade — e o índice parcial que
     * a torna barata existe.
     *
     * <p>A asserção é relativa (antes/depois) e não absoluta: o banco de teste pode ter eventos de
     * outras rodadas, e um teste que exigisse zero quebraria por causa deles em vez de por causa de
     * um defeito.</p>
     */
    @Test
    void aTelaCONTAdeVERDADEoseventosSEMempresa() {
        Long antes = (Long) publicador.estado().get("eventosSemEmpresa");
        assertNotNull(antes, "a contagem tem de vir medida quando o banco esta de pe");

        Loja orfa = lojaCom(null, "para contar");
        publicador.dePedido(MARCA + "-PedidoRecebido", pedidoDa(orfa.getId()), Map.of());

        Long depois = (Long) publicador.estado().get("eventosSemEmpresa");
        assertEquals(antes + 1, depois, "um evento sem empresa a mais tem de aparecer na tela");

        // E uma venda COM empresa nao mexe na contagem: senao o numero da tela nao distinguiria
        // "coluna vazia" de "fila crescendo".
        Long empresa = empresas.paraDocumento(null, MARCA + " nao conta").getId();
        publicador.dePedido(MARCA + "-PedidoRecebido", pedidoDa(lojaCom(empresa, "ok").getId()), Map.of());
        assertEquals(depois, (Long) publicador.estado().get("eventosSemEmpresa"),
                "evento COM empresa nao pode entrar na contagem de quem esta sem");
    }

    /** O índice parcial da V76 está no banco — é ele que mantém a contagem instantânea. */
    @Test
    void oINDICEparcialDAv76existeEEHparcial() {
        String definicao = jdbc.queryForObject(
                "select indexdef from pg_indexes where indexname = 'ix_outbox_sem_empresa'",
                String.class);
        assertNotNull(definicao, "o indice ix_outbox_sem_empresa nao existe: a V76 nao rodou?");
        assertTrue(definicao.toLowerCase().contains("where (empresa_id is null)"),
                "sem o WHERE o indice cresce junto com a fila inteira e a contagem deixa de ser "
                        + "barata. Definicao medida: " + definicao);
    }

    /**
     * O RETROFIT da V76 funciona — medido rodando a instrução que está DENTRO da migração.
     *
     * <h3>O problema deste teste, e como ele é resolvido</h3>
     * <p>A migração já rodou quando o teste começa: não dá para "ver" o retrofit acontecer. E num
     * banco de teste limpo não existe evento antigo, então o teste de regressão logo abaixo passa
     * <b>sem medir nada</b> — ele é guarda contra o futuro, não prova do presente.</p>
     *
     * <p>Então aqui eu crio a situação à mão (loja COM empresa, evento SEM empresa, que é
     * exatamente o que a versão antiga do publicador gravava) e rodo o {@code UPDATE} <b>lido do
     * próprio arquivo da migração</b>, não uma cópia. Cópia de SQL dentro de teste envelhece calada:
     * alguém corrige a migração, o teste continua verde medindo a versão velha. Se os marcadores do
     * arquivo mudarem, este teste falha em vez de ficar verde.</p>
     */
    @Test
    void oRETROFITdaV76preencheEVENTOantigoDElojaQUEtemEMPRESA() throws Exception {
        Long empresa = empresas.paraDocumento(null, MARCA + " retrofit").getId();
        Loja l = lojaCom(empresa, "retrofit");

        // Exatamente o que o publicador gravava antes de 18/11/2026: empresa_id null literal.
        jdbc.update("""
                insert into evento_outbox
                  (tipo, versao_schema, empresa_id, loja_id, agregado_tipo, agregado_id,
                   payload, ocorrido_em, data_operacao)
                values (?, '0.0.0', null, ?, 'pedido', 333333, '{}'::jsonb, now(), current_date)
                """, MARCA + "-antigo", l.getId());
        assertNull(empresaGravadaDoPedido(333333L), "nasceu sem empresa, como a versao antiga fazia");

        jdbc.update(updateDaMigracaoV76());

        assertEquals(empresa, empresaGravadaDoPedido(333333L),
                "o retrofit da V76 tem de dar a esse evento a empresa da loja dele");
    }

    /**
     * O {@code UPDATE} de retrofit, lido do arquivo da V76 no classpath.
     *
     * <p>Recusa em voz alta em vez de devolver algo vazio: um extrator que falha em silêncio faria
     * o teste de cima rodar {@code jdbc.update("")} e passar sem medir o retrofit.</p>
     */
    private String updateDaMigracaoV76() throws Exception {
        String arquivo;
        try (var entrada = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V76__empresa_na_fila_de_eventos.sql")) {
            assertNotNull(entrada, "nao achei a V76 no classpath");
            arquivo = new String(entrada.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        int inicio = arquivo.indexOf("UPDATE evento_outbox");
        assertTrue(inicio >= 0, "a V76 nao tem mais o UPDATE de retrofit. Se ele saiu de proposito, "
                + "este teste tem de sair junto -- nao ficar verde medindo nada.");
        int fim = arquivo.indexOf(';', inicio);
        assertTrue(fim > inicio, "nao achei o fim do UPDATE da V76");
        String sql = arquivo.substring(inicio, fim);
        assertTrue(sql.toLowerCase().contains("empresa_id"), "extrai a instrucao errada: " + sql);
        return sql;
    }

    /** O retrofit da V76 não deixou para trás evento de loja que tem empresa. */
    @Test
    void naoSOBROUeventoSEMempresaDElojaQUEtemEMPRESA() {
        Long sobraram = jdbc.queryForObject("""
                select count(*) from evento_outbox e
                  join loja l on l.id = e.loja_id
                 where e.empresa_id is null
                   and l.empresa_id is not null
                """, Long.class);
        assertEquals(0L, sobraram,
                "evento sem empresa cuja loja TEM empresa e exatamente o que a V76 retrofitou; "
                        + "se sobrou, ou a migracao nao rodou ou o INSERT voltou a gravar null");
    }
}
