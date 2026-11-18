package br.com.bora.controller;

import br.com.bora.entity.ConfigPlataforma;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.ConfigPlataformaRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import br.com.bora.service.PublicadorDeEventos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * O interruptor da fila de eventos do ERP tem de ser ALCANCAVEL pela aplicacao.
 *
 * <h2>O defeito que este arquivo fecha (17/11/2026)</h2>
 * <p>A v167 subiu a gravacao da fila (outbox) desligada por uma chave global, e com o <b>valor</b>
 * dessa chave aparecendo na tela — de proposito, para quem precisasse desligar as pressas poder ver
 * em que estado ela estava.</p>
 *
 * <p>Faltou o essencial: <b>nao havia nenhum caminho pela aplicacao para ESCREVER a chave.</b> So o
 * {@code fiscal.habilitado} tinha endpoint de escrita. Ligar ou desligar a fila exigia escrever
 * direto no banco de producao.</p>
 *
 * <p>Isto e pior do que parece. A gravacao fica no caminho de <b>toda venda</b>. O interruptor de
 * emergencia dela so podia ser alcancado por quem tem acesso ao banco — e numa emergencia isso e o
 * mesmo que nao ter interruptor nenhum.</p>
 *
 * <h2>E o custo, que tem de ser visivel ANTES de decidir</h2>
 * <p>Desligar ou nao e uma decisao que se toma olhando quanto a fila esta custando. Por isso o GET
 * devolve a medicao junto do estado: quem vai decidir nao decide no escuro.</p>
 */
class OinterruptorDoOutboxPrecisaSerAlcancavelTest {

    private static final long EU = 99L;

    private Map<String, ConfigPlataforma> tabela;
    private ConfigPlataformaRepository configs;
    private JdbcTemplate jdbc;
    private PublicadorDeEventos publicador;
    private PlataformaController controller;

    @BeforeEach
    void montar() {
        tabela = new HashMap<>();
        configs = mock(ConfigPlataformaRepository.class);
        when(configs.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(tabela.get(inv.getArgument(0))));
        when(configs.save(any(ConfigPlataforma.class))).thenAnswer(inv -> {
            ConfigPlataforma c = inv.getArgument(0);
            tabela.put(c.getChave(), c);
            return c;
        });

        jdbc = mock(JdbcTemplate.class);
        publicador = new PublicadorDeEventos(jdbc, configs, new ObjectMapper());

        AuthContext ctx = mock(AuthContext.class);
        when(ctx.atual()).thenReturn(new BoraPrincipal(EU, null, "ADMINISTRADOR_BORA", "adm@borahapp.com.br"));

        controller = new PlataformaController(
                mock(br.com.bora.repository.LojaRepository.class),
                mock(br.com.bora.repository.UsuarioRepository.class),
                mock(org.springframework.security.crypto.password.PasswordEncoder.class),
                ctx,
                mock(br.com.bora.repository.UsuarioLojaRepository.class),
                configs,
                mock(br.com.bora.service.CifragemEmRepousoService.class),
                mock(br.com.bora.service.ProvisionamentoService.class),
                mock(br.com.bora.service.AssinaturaService.class),
                mock(br.com.bora.service.IntegracaoService.class),
                mock(br.com.bora.repository.AssinaturaRepository.class),
                mock(br.com.bora.repository.PagamentoAssinaturaRepository.class),
                mock(br.com.bora.repository.PedidoRepository.class),
                mock(br.com.bora.service.EmpresaService.class),
                mock(br.com.bora.security.JwtService.class),
                mock(br.com.bora.repository.ConfiguracaoLojaRepository.class),
                mock(br.com.bora.service.marketplace.CredenciaisMarketplace.class),
                mock(br.com.bora.service.LimpezaDeTestesService.class),
                new br.com.bora.service.EnderecoDaLojaService(),
                publicador,
                "0");
    }

    private Pedido pedido() {
        Pedido p = new Pedido();
        p.id = 42L;
        p.lojaId = 7L;
        p.codigo = "#42";
        p.status = StatusPedido.RECEBIDO;
        p.valorTotal = new BigDecimal("24.90");
        p.criadoEm = OffsetDateTime.now();
        return p;
    }

    // ------------------------------------------------------------ o interruptor existe

    /** A premissa do defeito: sem a chave gravada, a fila esta desligada. */
    @Test
    void semAchaveGravadaOestadoDizDESLIGADA() {
        assertEquals(false, controller.estadoDoOutbox().get("habilitado"));
    }

    @Test
    void oPUTcriaAchaveQuandoElaNuncaExistiu() {
        assertTrue(tabela.isEmpty(), "a premissa: a linha nao existe");

        controller.definirOutbox(Map.of("habilitado", true));

        assertEquals("true", tabela.get("outbox.habilitado").getValor());
        assertNotNull(tabela.get("outbox.habilitado").getAtualizadoEm(),
                "sem a data nao da para saber QUANDO alguem ligou isto");
    }

    /**
     * LIGAR VALE NA PROXIMA VENDA, nao em trinta segundos.
     *
     * <p>A chave tem cache de 30 segundos para a venda nao pagar um SELECT a cada pedido. Quem
     * grava pela tela esta no mesmo processo, entao o cache pode ser invalidado na hora — e precisa
     * ser: numa emergencia, trinta segundos de venda caindo e muita coisa.</p>
     */
    @Test
    void oPUTvaleNAhoraSEMesperarOcacheVencer() {
        // Forca a leitura da chave com ela ausente: o cache passa a guardar "desligado".
        assertFalse(publicador.ligado());

        controller.definirOutbox(Map.of("habilitado", true));

        assertTrue(publicador.ligado(),
                "o cache de 30s ficou com o valor velho: ligar pela tela nao surtiu efeito");
    }

    @Test
    void oPUTdesligaEodesligarTAMBEMvaleNAhora() {
        controller.definirOutbox(Map.of("habilitado", true));
        assertTrue(publicador.ligado());

        controller.definirOutbox(Map.of("habilitado", false));

        assertEquals("false", tabela.get("outbox.habilitado").getValor());
        assertFalse(publicador.ligado(), "DESLIGAR e o caminho de emergencia: nao pode demorar");
    }

    /** Corpo vazio ou sem o campo nao pode ser lido como "ligar". Na duvida, desligado. */
    @Test
    void corpoSemOcampoNaoLIGAnada() {
        controller.definirOutbox(Map.of("habilitado", true));

        controller.definirOutbox(Map.of());
        assertEquals("false", tabela.get("outbox.habilitado").getValor());

        controller.definirOutbox(null);
        assertEquals("false", tabela.get("outbox.habilitado").getValor());
    }

    // ------------------------------------------------------------ a medicao

    @Test
    void antesDAprimeiraGravacaoNaoHAnumeroINVENTADO() {
        Map<String, Object> e = controller.estadoDoOutbox();

        assertEquals(0L, e.get("gravacoes"));
        assertNull(e.get("mediaMs"), "media de zero gravacao e NULO, nao zero: zero seria mentira");
        assertNull(e.get("maiorMs"));
        assertNull(e.get("primeiraGravacao"));
    }

    @Test
    void depoisDeGravarOestadoDizQUANTASeQUANTOcustou() {
        controller.definirOutbox(Map.of("habilitado", true));

        publicador.dePedido("PedidoRecebido", pedido(), Map.of("canal", "PAINEL"));
        publicador.dePedido("PedidoConfirmado", pedido(), Map.of());

        Map<String, Object> e = controller.estadoDoOutbox();
        assertEquals(2L, e.get("gravacoes"));
        assertNotNull(e.get("mediaMs"));
        assertNotNull(e.get("maiorMs"));
        assertNotNull(e.get("primeiraGravacao"), "sem o instante da primeira nao da para casar com o log");
        assertNotNull(e.get("ultimaGravacao"));
        assertTrue((Double) e.get("maiorMs") >= (Double) e.get("mediaMs"),
                "a maior nao pode ser menor que a media");
    }

    /**
     * A ARMADILHA QUE ESTE TESTE FECHA: contagem zero nao quer dizer chave desligada.
     *
     * <p>Os contadores sao do processo e zeram em cada deploy. Quem olhar "0 gravacoes" depois de
     * um deploy e concluir "esta desligada" vai ligar de novo uma chave que ja esta ligada — ou,
     * pior, parar de procurar o motivo de a fila nao encher.</p>
     */
    @Test
    void contagemZEROnaoQuerDizerCHAVEdesligada() {
        controller.definirOutbox(Map.of("habilitado", true));

        Map<String, Object> e = controller.estadoDoOutbox();

        assertEquals(0L, e.get("gravacoes"), "nenhuma venda aconteceu ainda");
        assertEquals(true, e.get("habilitado"), "e a chave que responde 'esta ligada?', nao a contagem");
    }

    /** Media de gravacao e media de GRAVACAO: o tempo de uma falha nao entra na conta. */
    @Test
    void oTEMPOdeUMAfalhaNAOentraNAmedia() {
        controller.definirOutbox(Map.of("habilitado", true));
        doThrow(new org.springframework.dao.DataIntegrityViolationException("coluna curta"))
                .when(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any());

        assertThrows(RuntimeException.class,
                () -> publicador.dePedido("PedidoRecebido", pedido(), Map.of()),
                "o INSERT que falha derruba a transacao da venda -- isso e esperado e esta no javadoc");

        assertEquals(0L, controller.estadoDoOutbox().get("gravacoes"));
        assertNull(controller.estadoDoOutbox().get("mediaMs"));
    }

    /** Com a chave desligada a medicao tambem fica parada: nada a medir, nada inventado. */
    @Test
    void comAchaveDESLIGADAnaoHAmedicao() {
        publicador.dePedido("PedidoRecebido", pedido(), Map.of());

        assertEquals(0L, controller.estadoDoOutbox().get("gravacoes"));
        verifyNoInteractions(jdbc);
    }

    /** O limite de "lenta" sai na resposta: quem le a media precisa saber contra o que comparar. */
    @Test
    void aRESPOSTAdizQualEHolimiteDEgravacaoLENTA() {
        assertEquals(PublicadorDeEventos.LENTA_MS, controller.estadoDoOutbox().get("lentaAcimaDeMs"));
    }

    /** A versao do contrato escrito acompanha o estado: e ela que o consumidor vai assinar. */
    @Test
    void aRESPOSTAdizQUALversaoDEschemaEstaSENDOgravada() {
        assertEquals(PublicadorDeEventos.VERSAO_SCHEMA, controller.estadoDoOutbox().get("versaoSchema"));
    }
}
