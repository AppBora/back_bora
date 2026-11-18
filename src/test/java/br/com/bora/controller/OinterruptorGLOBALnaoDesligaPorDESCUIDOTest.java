package br.com.bora.controller;

import br.com.bora.entity.ConfigPlataforma;
import br.com.bora.repository.ConfigPlataformaRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import br.com.bora.service.PublicadorDeEventos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Os interruptores GLOBAIS não desligam por descuido, e não são acionáveis por quem não é o dono.
 *
 * <h2>Os dois defeitos que este arquivo fecha (achados pelo QA em 18/11/2026)</h2>
 *
 * <p><b>1. Corpo digitado errado DESLIGAVA e respondia 200.</b> Os dois PUT de chave global faziam
 * {@code Boolean.TRUE.equals(body.get("habilitado"))}. Corpo {@code {}} ou chave errada
 * ({@code "abilitado"}) virava <b>false</b> com resposta <b>200</b>. Medido pelo QA: com a fila
 * ligada e 10 gravações, um PUT com a chave errada <b>desligou a fila</b> e devolveu sucesso.</p>
 *
 * <p>É a terceira vez deste padrão no projeto. O {@code PUT /usuarios/{id}/ativo} já tinha sido
 * corrigido pelo mesmo motivo — corpo vazio desativava a conta respondendo 200. "Ausência" não é
 * "false": ausência é "não sei", e quem não sabe não aciona o interruptor de todas as lojas.</p>
 *
 * <p><b>2. A guarda de papel não tinha teste.</b> O QA mutou {@code definirOutbox} para fora o
 * {@code requireAdminBora()} e rodou a suíte inteira: <b>1503 testes, 0 falhas</b>. O código estava
 * (e está) certo, mas nada travava. Para o interruptor de uma gravação que fica no caminho de toda
 * venda, "está certo hoje" não basta.</p>
 */
class OinterruptorGLOBALnaoDesligaPorDESCUIDOTest {

    /**
     * Gerente de transacao de verdade, porem SEM transacao -- a semantica que o unitario quer.
     *
     * <p>Um mock cru nao serve: o {@code TransactionTemplate} chama {@code getTransaction} e
     * {@code commit}, e um status nulo estouraria. Aqui tambem fica explicito o LIMITE deste
     * arquivo: sem proxy transacional de verdade, ele nao ve o defeito de rollback-only que o
     * {@code AchaveDoOutboxNAOpodeDERRUBARaVENDAIT} mede.</p>
     */
    private static final org.springframework.transaction.PlatformTransactionManager SEM_TRANSACAO =
            new org.springframework.transaction.PlatformTransactionManager() {
                @Override public org.springframework.transaction.TransactionStatus getTransaction(
                        org.springframework.transaction.TransactionDefinition d) {
                    return new org.springframework.transaction.support.SimpleTransactionStatus(false);
                }
                @Override public void commit(org.springframework.transaction.TransactionStatus s) { }
                @Override public void rollback(org.springframework.transaction.TransactionStatus s) { }
            };

    private Map<String, ConfigPlataforma> tabela;
    private AuthContext ctx;
    private PublicadorDeEventos publicador;
    private PlataformaController controller;

    @BeforeEach
    void montar() {
        tabela = new HashMap<>();
        ConfigPlataformaRepository configs = mock(ConfigPlataformaRepository.class);
        when(configs.findById(anyString()))
                .thenAnswer(i -> Optional.ofNullable(tabela.get(i.getArgument(0))));
        when(configs.save(any(ConfigPlataforma.class))).thenAnswer(i -> {
            ConfigPlataforma c = i.getArgument(0);
            tabela.put(c.getChave(), c);
            return c;
        });

        publicador = new PublicadorDeEventos(mock(JdbcTemplate.class), configs, new ObjectMapper(), SEM_TRANSACAO);
        ctx = mock(AuthContext.class);
        when(ctx.atual()).thenReturn(new BoraPrincipal(99L, null, "ADMINISTRADOR_BORA", "adm@borahapp.com.br"));

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

    private void ligada() {
        controller.definirOutbox(Map.of("habilitado", true));
        assertTrue(publicador.ligado(), "a premissa: a fila esta LIGADA");
    }

    private String valorNoBanco(String chave) {
        ConfigPlataforma c = tabela.get(chave);
        return c == null ? null : c.getValor();
    }

    // ================================================== corpo errado nao desliga

    /**
     * O CASO MEDIDO PELO QA: a fila estava ligada, o PUT veio com a chave digitada errada, e ela
     * desligou respondendo 200.
     */
    @Test
    void chaveDIGITADAerradaNAOdesligaAfila() {
        ligada();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.definirOutbox(Map.of("abilitado", true)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertEquals("true", valorNoBanco("outbox.habilitado"), "a fila foi DESLIGADA por um erro de digitacao");
        assertTrue(publicador.ligado());
    }

    @Test
    void corpoVAZIOnaoDesligaAfila() {
        ligada();

        assertThrows(ResponseStatusException.class, () -> controller.definirOutbox(Map.of()));

        assertEquals("true", valorNoBanco("outbox.habilitado"));
        assertTrue(publicador.ligado());
    }

    @Test
    void corpoNULOnaoDesligaAfila() {
        ligada();

        assertThrows(ResponseStatusException.class, () -> controller.definirOutbox(null));

        assertEquals("true", valorNoBanco("outbox.habilitado"));
    }

    /** Valor que não é booleano nem "true"/"false" é recusado: número, texto solto, nulo. */
    @Test
    void valorQueNAOehBooleanoEHrecusado() {
        ligada();

        for (Object ruim : new Object[] { 1, 0, "sim", "", "TRUEE", 1.0 }) {
            Map<String, Object> corpo = new HashMap<>();
            corpo.put("habilitado", ruim);
            assertThrows(ResponseStatusException.class, () -> controller.definirOutbox(corpo),
                    "deveria recusar: " + ruim);
        }
        Map<String, Object> comNulo = new HashMap<>();
        comNulo.put("habilitado", null);
        assertThrows(ResponseStatusException.class, () -> controller.definirOutbox(comNulo),
                "nulo explicito e 'nao sei', nao 'false'");

        assertEquals("true", valorNoBanco("outbox.habilitado"), "nada disso pode ter mexido na chave");
    }

    /**
     * O MESMO DEFEITO ESTAVA NO INTERRUPTOR FISCAL. Ele liga a emissão de NFC-e para a plataforma
     * inteira: desligar por engano respondendo 200 é igualmente caro.
     */
    @Test
    void oInterruptorFISCALtambemRecusaCorpoERRADO() {
        controller.definirFiscal(Map.of("habilitado", true));
        assertEquals("true", valorNoBanco("fiscal.habilitado"));

        assertThrows(ResponseStatusException.class, () -> controller.definirFiscal(Map.of()));
        assertThrows(ResponseStatusException.class, () -> controller.definirFiscal(Map.of("abilitado", false)));

        assertEquals("true", valorNoBanco("fiscal.habilitado"));
    }

    // ================================================== o que CONTINUA funcionando

    @Test
    void booleanoDEverdadeFUNCIONAnosDOISsentidos() {
        controller.definirOutbox(Map.of("habilitado", true));
        assertEquals("true", valorNoBanco("outbox.habilitado"));

        controller.definirOutbox(Map.of("habilitado", false));
        assertEquals("false", valorNoBanco("outbox.habilitado"));
        assertFalse(publicador.ligado(), "DESLIGAR de proposito tem de continuar valendo na hora");
    }

    /**
     * Texto "true"/"false" é aceito de propósito: é o que um {@code curl} de suporte manda sem
     * pensar, a intenção é inequívoca, e recusar seria rigor sem ganho.
     */
    @Test
    void oTEXTOtrueEfalseEHaceitoDEproposito() {
        controller.definirOutbox(Map.of("habilitado", "true"));
        assertEquals("true", valorNoBanco("outbox.habilitado"));

        controller.definirOutbox(Map.of("habilitado", " FALSE "));
        assertEquals("false", valorNoBanco("outbox.habilitado"), "com espaco e maiuscula tambem");
    }

    // ================================================== a guarda de papel (T1)

    /**
     * A GUARDA DE PAPEL, TRAVADA. O QA removeu o {@code requireAdminBora()} do PUT e a suíte
     * inteira — 1503 testes — ficou verde.
     *
     * <p>Aqui o dublê de {@code AuthContext} <b>estoura</b>, que é o que ele faz de verdade quando
     * quem chama não é administrador da plataforma. Se alguém apagar a chamada, estes testes passam
     * a ver a operação acontecer e reprovam.</p>
     */
    @Test
    void semSERadminDAplataformaOpUTnaoMEXEnaCHAVE() {
        ligada();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Só o administrador do Bora"))
                .when(ctx).requireAdminBora();

        assertThrows(ResponseStatusException.class,
                () -> controller.definirOutbox(Map.of("habilitado", false)));

        assertEquals("true", valorNoBanco("outbox.habilitado"),
                "a guarda de papel foi removida: quem nao e dono desligou a fila de todas as lojas");
        assertTrue(publicador.ligado());
    }

    @Test
    void semSERadminDAplataformaOgETnaoRESPONDE() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Só o administrador do Bora"))
                .when(ctx).requireAdminBora();

        assertThrows(ResponseStatusException.class, () -> controller.estadoDoOutbox());
    }

    @Test
    void semSERadminDAplataformaOfISCALtambemNaoCede() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Só o administrador do Bora"))
                .when(ctx).requireAdminBora();

        assertThrows(ResponseStatusException.class,
                () -> controller.definirFiscal(Map.of("habilitado", true)));
        assertNull(valorNoBanco("fiscal.habilitado"));
    }

    /** A guarda é chamada ANTES de qualquer escrita, não depois. */
    @Test
    void aGUARDAvemANTESdeESCREVER() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "nao"))
                .when(ctx).requireAdminBora();

        assertThrows(ResponseStatusException.class,
                () -> controller.definirOutbox(Map.of("habilitado", true)));

        assertTrue(tabela.isEmpty(), "escreveu antes de conferir o papel");
    }
}
