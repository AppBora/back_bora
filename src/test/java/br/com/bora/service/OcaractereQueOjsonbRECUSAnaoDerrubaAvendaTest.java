package br.com.bora.service;

import br.com.bora.entity.ConfigPlataforma;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.ConfigPlataformaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * O caractere que o {@code jsonb} do PostgreSQL recusa NÃO derruba a venda.
 *
 * <h2>O defeito, medido em 18/11/2026 num PostgreSQL 16 de verdade</h2>
 * <pre>
 *   select '{"a":"x\u0000y"}'::jsonb;   -- ERROR: unsupported Unicode escape sequence
 *   select '{"a":"\ud800"}'::jsonb;      -- ERROR: invalid input syntax for type json
 *   select '{"a":"Açaí 500ml"}'::jsonb;  -- passa; acento nunca foi problema
 * </pre>
 *
 * <p>Por que isso era grave e não era teórico: o INSERT do {@link PublicadorDeEventos} mora na
 * transação da <b>venda</b>. O javadoc daquela classe diz, com razão, que no PostgreSQL uma
 * instrução que falha envenena a transação inteira — capturar não salvaria a venda. Então um
 * caractere NUL dentro de um {@code motivo} de cancelamento, ou de um {@code idExterno} vindo de
 * marketplace, <b>derrubava a venda</b>.</p>
 *
 * <p>E o {@code catch} que protege a serialização não ajudava: o Jackson serializa o NUL sem
 * reclamar. Quem recusa é o banco, depois — já dentro da transação.</p>
 *
 * <h2>O que estes testes provam, e o que não</h2>
 * <p><b>Provam</b> que o payload que sai desta classe não contém mais os dois caracteres, e que
 * texto legítimo (acento, emoji inteiro) passa intacto.</p>
 * <p><b>Não provam</b> que o PostgreSQL aceita o resultado — isso foi medido à mão, com os três
 * SELECTs acima. O mock de {@code JdbcTemplate} aqui aceita qualquer coisa.</p>
 */
class OcaractereQueOjsonbRECUSAnaoDerrubaAvendaTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ConfigPlataformaRepository configs = mock(ConfigPlataformaRepository.class);
    private final ObjectMapper json = new ObjectMapper();

    private PublicadorDeEventos ligado() {
        ConfigPlataforma c = new ConfigPlataforma();
        c.setChave(PublicadorDeEventos.CHAVE_LIGA);
        c.setValor("true");
        when(configs.findById(PublicadorDeEventos.CHAVE_LIGA)).thenReturn(Optional.of(c));
        return new PublicadorDeEventos(jdbc, configs, json);
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

    /** O corpo JSON que foi para o banco. */
    private String corpoGravado() {
        var args = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(), args.capture(), args.capture(), args.capture(),
                args.capture(), args.capture(), args.capture(), args.capture());
        return (String) args.getAllValues().get(4);
    }

    // ------------------------------------------------------------- o saneador

    @Test
    void oNULsaiDoTextoEoRESTOfica() {
        assertEquals("xy", PublicadorDeEventos.saneado("x\u0000y"));
        assertEquals("", PublicadorDeEventos.saneado("\u0000"));
        assertEquals("abc", PublicadorDeEventos.saneado("\u0000abc\u0000"));
    }

    /**
     * EMOJI INTEIRO PASSA. É um par de surrogates válido, e o jsonb aceita.
     *
     * <p>Isto importa porque o lojista escreve motivo de cancelamento no celular, com emoji. Sanear
     * demais apagaria texto legítimo e seria um defeito trocado por outro.</p>
     */
    @Test
    void oEMOJIinteiroPASSAintacto() {
        assertEquals("pedido 🍕 ok", PublicadorDeEventos.saneado("pedido 🍕 ok"));
        assertEquals("Açaí 500ml — São Paulo", PublicadorDeEventos.saneado("Açaí 500ml — São Paulo"));
    }

    /**
     * O MEIO DE UM EMOJI SAI. É o caso realista: este sistema CORTA texto por largura de coluna em
     * vários pontos (justamente para o Postgres não recusar o INSERT), e cortar no meio de um par
     * deixa metade do emoji.
     */
    @Test
    void oMEIOdeUMemojiSAI() {
        String emoji = "🍕";
        assertEquals(2, emoji.length(), "a premissa: o emoji sao dois chars");

        assertEquals("ab", PublicadorDeEventos.saneado("ab" + emoji.charAt(0)), "alto sozinho sai");
        assertEquals("ab", PublicadorDeEventos.saneado("ab" + emoji.charAt(1)), "baixo sozinho sai");
        assertEquals("ab" + emoji, PublicadorDeEventos.saneado("ab" + emoji), "o par fica");
    }

    @Test
    void nuloEvazioEnumeroPASSAMsemMEXER() {
        assertNull(PublicadorDeEventos.saneado(null));
        assertEquals("", PublicadorDeEventos.saneado(""));
        assertEquals(42L, PublicadorDeEventos.saneado(42L), "numero nao e texto: passa como esta");
        assertEquals(Boolean.TRUE, PublicadorDeEventos.saneado(Boolean.TRUE));
        BigDecimal v = new BigDecimal("24.90");
        assertSame(v, PublicadorDeEventos.saneado(v), "nem copia: o mesmo objeto");
    }

    /** Texto sem nada de errado volta o MESMO objeto: o caminho normal não paga cópia. */
    @Test
    void textoLIMPOnaoEHcopiado() {
        String t = "motivo normal de cancelamento";
        assertSame(t, PublicadorDeEventos.saneado(t));
    }

    // ------------------------------------------------------------- no payload de verdade

    /**
     * O CAMINHO QUE DERRUBAVA A VENDA: um NUL no `motivo` de um cancelamento.
     *
     * <p>É texto que o lojista digita, ou que vem de marketplace. Chega ao payload por
     * {@code PedidoService.alterarStatus} e por {@code cancelarPorMarketplace}.</p>
     */
    @Test
    void oNULnoMOTIVOnaoChegaAoBanco() {
        ligado().dePedido("PedidoCancelado", pedido(),
                Map.of("motivo", "cliente desistiu\u0000", "fase", "RECEBIDO"));

        String corpo = corpoGravado();
        assertFalse(corpo.contains("\\u0000"), "o NUL escapado foi para o jsonb: " + corpo);
        assertFalse(corpo.contains("\u0000"), "o NUL cru foi para o jsonb");
        assertTrue(corpo.contains("cliente desistiu"), "e o texto util tem de sobreviver: " + corpo);
    }

    @Test
    void oNULnoIDexternoDoMarketplaceNaoChegaAoBanco() {
        ligado().dePedido("PedidoRecebido", pedido(),
                Map.of("canal", "IFOOD", "idExterno", "ext\u0000123"));

        String corpo = corpoGravado();
        assertFalse(corpo.contains("\\u0000"));
        assertTrue(corpo.contains("ext123"), "o id util fica, sem o lixo: " + corpo);
    }

    /**
     * O NUL tambem e tirado dos campos que vem DO BANCO, nao so dos extras.
     *
     * <p>`codigo`, `origem` e `formaPagamento` sao gravados por caminhos de marketplace. Sanear so
     * os extras deixaria metade do problema em pe.</p>
     */
    @Test
    void oNULnosCAMPOSdoPROPRIOpedidoTambemSAI() {
        Pedido p = pedido();
        p.origem = "iFood\u0000";
        p.formaPagamento = "ONLINE\u0000";
        p.codigo = "IF-\u0000900";

        ligado().dePedido("PedidoRecebido", p, Map.of());

        String corpo = corpoGravado();
        assertFalse(corpo.contains("\\u0000"), "campo do pedido levou NUL ao jsonb: " + corpo);
        assertTrue(corpo.contains("iFood"));
        assertTrue(corpo.contains("IF-900"));
    }

    /** O payload continua sendo JSON válido depois de sanear. */
    @Test
    void oPAYLOADsaneadoCONTINUAjsonVALIDO() throws Exception {
        ligado().dePedido("PedidoCancelado", pedido(),
                Map.of("motivo", "a\u0000b", "extra", "🍕 ok"));

        Map<?, ?> lido = json.readValue(corpoGravado(), Map.class);
        assertEquals("ab", lido.get("motivo"));
        assertEquals("🍕 ok", lido.get("extra"));
        assertEquals(42, lido.get("pedidoId"), "o resto do payload nao foi afetado");
    }

    /** E a venda não cai: o fluxo inteiro passa sem exceção. */
    @Test
    void aVENDAnaoCAIcomTextoSUJO() {
        assertDoesNotThrow(() -> ligado().dePedido("PedidoCancelado", pedido(),
                Map.of("motivo", "\u0000\ud800meio de emoji\udc00\u0000")));
        verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any());
    }
}
