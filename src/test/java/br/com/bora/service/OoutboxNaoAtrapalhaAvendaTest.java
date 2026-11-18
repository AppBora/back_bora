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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A fila de eventos do ERP ({@link PublicadorDeEventos}) — entrega A2.
 *
 * <h2>Por que este arquivo existe, e qual é a promessa que ele guarda</h2>
 * <p>Esta gravação fica no caminho de <b>toda venda</b>. É o único ponto da fase A que pode doer no
 * cliente de hoje. A promessa é dupla:</p>
 * <ol>
 *   <li><b>Nasce desligada</b> — enquanto a chave global não for "true", nenhuma venda paga nada:
 *       nem um INSERT, nem a montagem do payload.</li>
 *   <li><b>Falha ao montar o evento NÃO derruba a venda</b> — evento perdido se recupera (o pedido
 *       está no banco); venda perdida, não.</li>
 * </ol>
 *
 * <h2>O que estes testes NÃO provam</h2>
 * <p>Que o INSERT entra na mesma transação do fato. Isso é comportamento do Spring com o
 * {@code JdbcTemplate} e foi medido contra um Postgres de verdade: forçando o rollback da venda, o
 * evento some junto.</p>
 */
class OoutboxNaoAtrapalhaAvendaTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ConfigPlataformaRepository configs = mock(ConfigPlataformaRepository.class);
    private final ObjectMapper json = new ObjectMapper();

    private PublicadorDeEventos publicador() {
        return new PublicadorDeEventos(jdbc, configs, json);
    }

    private void chave(String valor) {
        if (valor == null) {
            when(configs.findById(PublicadorDeEventos.CHAVE_LIGA)).thenReturn(Optional.empty());
            return;
        }
        ConfigPlataforma c = new ConfigPlataforma();
        c.setChave(PublicadorDeEventos.CHAVE_LIGA);
        c.setValor(valor);
        when(configs.findById(PublicadorDeEventos.CHAVE_LIGA)).thenReturn(Optional.of(c));
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

    // ------------------------------------------------------------- nasce desligada

    /**
     * SEM A CHAVE, A VENDA NÃO PAGA NADA. É o estado em que isto vai para produção: a chave não
     * existe em {@code config_plataforma}, e nenhuma das 23 lojas sente diferença.
     */
    @Test
    void semAchaveNaoGravaNadaENaoCustaNada() {
        chave(null);
        publicador().dePedido("PedidoRecebido", pedido(), Map.of());
        verifyNoInteractions(jdbc);
    }

    @Test
    void comAchaveEmFalseContinuaDesligado() {
        chave("false");
        publicador().dePedido("PedidoRecebido", pedido(), Map.of());
        verifyNoInteractions(jdbc);
    }

    @Test
    void comAchaveEmTrueGrava() {
        chave("true");
        publicador().dePedido("PedidoRecebido", pedido(), Map.of("canal", "PAINEL"));
        // OITO parametros desde 18/11/2026, nao sete: a empresa entrou como subconsulta e `lojaId`
        // passou a ir duas vezes. Este `verify` e o que fez a mudanca aparecer -- se o numero de
        // parametros mudar de novo, este teste quebra antes de qualquer venda sentir.
        verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------- a empresa, e por que ela vem do banco

    /**
     * A EMPRESA SAI DA LOJA, DENTRO DO PROPRIO INSERT — e {@code lojaId} vai duas vezes.
     *
     * <h3>O defeito que isto fecha</h3>
     * <p>Até 18/11/2026 o INSERT gravava {@code null} literal em {@code empresa_id}. Não era
     * descuido de digitação: o {@link Pedido} não carrega a empresa, só a loja. Mas o isolamento do
     * ERP é por empresa, e o caso que prova isso está em produção — as lojas 18, 19, 20, 21 e 22 da
     * Zirá são <b>uma empresa só</b>. Sem a coluna, quem consome a fila veria cinco clientes.</p>
     *
     * <h3>Por que o teste olha o SQL, e por que isso aqui é legítimo</h3>
     * <p>Normalmente afirmar coisas sobre uma string de SQL mede pouco. Aqui mede duas decisões de
     * comportamento que não existem em nenhum outro lugar do código:</p>
     * <ol>
     *   <li><b>que a empresa é lida no banco e não mandada como constante</b> — se alguém voltar a
     *       pôr {@code null} (ou um valor fixo), o {@code loja} some do SQL e este teste cai;</li>
     *   <li><b>que ela é lida na MESMA instrução</b> — um {@code SELECT} separado em Java passaria
     *       por qualquer asserção sobre o valor gravado, e dobraria o custo do outbox no caminho de
     *       toda venda, que é exatamente o que esta classe existe para não fazer.</li>
     * </ol>
     * <p>Quem prova que o valor gravado é o certo é o {@code AfilaDEeventosGRAVAaEMPRESAdaLOJAIT},
     * contra um Postgres de verdade. Os dois juntos fecham: este trava a forma, o IT trava o
     * resultado.</p>
     */
    @Test
    void aEmpresaVemDaLojaNaMESMAinstrucaoENAOcomoNULL() {
        chave("true");
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object> args = org.mockito.ArgumentCaptor.forClass(Object.class);

        publicador().dePedido("PedidoRecebido", pedido(), Map.of());

        verify(jdbc).update(sql.capture(), args.capture(), args.capture(), args.capture(),
                args.capture(), args.capture(), args.capture(), args.capture(), args.capture());

        String instrucao = sql.getValue().replaceAll("\\s+", " ").trim();
        assertTrue(instrucao.contains("(select empresa_id from loja where id = ?)"),
                "a empresa tem de ser lida da loja DENTRO do insert, nao mandada como constante. "
                        + "SQL medido: " + instrucao);
        assertFalse(instrucao.toLowerCase().contains("values (?, ?, null"),
                "voltou a gravar null literal em empresa_id: " + instrucao);

        // O 3o parametro alimenta a subconsulta e o 4o a coluna `loja_id`: os dois sao a MESMA
        // loja. Trocar essa ordem gravaria o evento na loja errada em silencio.
        assertEquals(7L, args.getAllValues().get(2), "3o parametro = a loja da subconsulta");
        assertEquals(7L, args.getAllValues().get(3), "4o parametro = a coluna loja_id");
        assertEquals(42L, args.getAllValues().get(4), "5o parametro = o id do pedido");
    }

    /**
     * A contagem de eventos sem empresa é o que torna a coluna observável — e ela <b>nunca</b> pode
     * derrubar a tela da fila. Banco fora do ar responde "não medido", não exceção.
     */
    @Test
    void aContagemDeEventosSemEmpresaNaoDerrubaAtela() {
        chave("true");
        when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenThrow(new RuntimeException("banco fora do ar"));

        Map<String, Object> estado = assertDoesNotThrow(() -> publicador().estado());
        assertTrue(estado.containsKey("eventosSemEmpresa"),
                "a chave tem de existir sempre: tela sem o campo nao distingue 'zero' de 'nao sei'");
        assertNull(estado.get("eventosSemEmpresa"), "na duvida, nao medido -- nunca um zero falso");
    }

    @Test
    void aTelaMostraAcontagemQuandoObancoResponde() {
        chave("true");
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(3L);
        assertEquals(3L, publicador().estado().get("eventosSemEmpresa"));
    }

    /**
     * Chave ilegível não pode derrubar venda. Na dúvida, desligado — a mesma regra que vale para
     * lacuna de cadastro no resto do sistema: "não sei" responde não.
     */
    @Test
    void chaveQueEstouraAoSerLidaDeixaDesligado() {
        when(configs.findById(PublicadorDeEventos.CHAVE_LIGA))
                .thenThrow(new RuntimeException("banco fora do ar"));
        assertDoesNotThrow(() -> publicador().dePedido("PedidoRecebido", pedido(), Map.of()));
        verifyNoInteractions(jdbc);
    }

    // ------------------------------------------------------------- não derruba a venda

    /**
     * Payload que não serializa é descartado com log — e a venda segue.
     *
     * <p>O {@code extras} abaixo tem um valor que o Jackson não sabe escrever. Antes do INSERT,
     * então dá para engolir: o pedido está gravado e o evento pode ser reconstruído depois. O
     * contrário — perder a venda porque o evento não serializou — seria trocar dinheiro por
     * integração que ninguém consome ainda.</p>
     */
    @Test
    void payloadQueNaoSerializaNaoDerrubaAvenda() {
        chave("true");
        Map<String, Object> ruim = Map.of("isto", new Object() {
            @SuppressWarnings("unused")
            public Object getLaco() { throw new IllegalStateException("nao serializa"); }
        });

        assertDoesNotThrow(() -> publicador().dePedido("PedidoRecebido", pedido(), ruim));
        verifyNoInteractions(jdbc);
    }

    @Test
    void pedidoNuloNaoEstoura() {
        chave("true");
        assertDoesNotThrow(() -> publicador().dePedido("PedidoRecebido", null, Map.of()));
        verifyNoInteractions(jdbc);
    }

    // ------------------------------------------------------------- o payload

    @Test
    void oPayloadLevaOqueOconsumidorPrecisaParaAcharOpedido() throws Exception {
        chave("true");
        org.mockito.ArgumentCaptor<Object> args = org.mockito.ArgumentCaptor.forClass(Object.class);

        publicador().dePedido("PedidoFinalizado", pedido(), Map.of("statusNovo", "ENTREGUE"));

        verify(jdbc).update(anyString(), args.capture(), args.capture(), args.capture(),
                args.capture(), args.capture(), args.capture(), args.capture(), args.capture());
        // O 6o argumento posicional e o corpo JSON. Era o 5o ate 18/11/2026: a empresa entrou como
        // subconsulta e empurrou todo mundo uma casa. O indice errado aqui nao daria erro de
        // compilacao -- daria um ClassCastException confuso, ou pior, um teste que mede outra coisa.
        String corpo = (String) args.getAllValues().get(5);
        Map<?, ?> m = json.readValue(corpo, Map.class);
        assertEquals(42, m.get("pedidoId"));
        assertEquals(7, m.get("lojaId"));
        assertEquals("ENTREGUE", m.get("statusNovo"), "o extra entra junto do basico");
        assertEquals("#42", m.get("codigo"));
    }

    // ------------------------------------------------------------- o nome do evento

    /**
     * O consumidor assina {@code PedidoConfirmado}, não {@code CONFIRMADO}. Trocar o nome do enum
     * pelo nome do contrato é o que faz um módulo conseguir se inscrever.
     */
    @Test
    void cadaStatusViraOnomeDoCatalogo() {
        assertEquals("PedidoConfirmado", PedidoService.eventoDeStatus(StatusPedido.CONFIRMADO));
        assertEquals("PedidoEmPreparo", PedidoService.eventoDeStatus(StatusPedido.EM_PREPARO));
        assertEquals("PedidoPronto", PedidoService.eventoDeStatus(StatusPedido.PRONTO));
        assertEquals("PedidoFinalizado", PedidoService.eventoDeStatus(StatusPedido.ENTREGUE),
                "ENTREGUE e o fim da venda: o catalogo chama isso de PedidoFinalizado");
        assertEquals("PedidoCancelado", PedidoService.eventoDeStatus(StatusPedido.CANCELADO));
        assertEquals("PedidoRecebido", PedidoService.eventoDeStatus(StatusPedido.RECEBIDO));
        assertEquals("PedidoStatusAlterado", PedidoService.eventoDeStatus(null),
                "nome generico e honesto e melhor que inventar um que ninguem assina");
    }

    /** Todo status do enum tem nome: um `default` silencioso esconderia um estado novo. */
    @Test
    void nenhumStatusFicaSemNomeDeEvento() {
        for (StatusPedido s : StatusPedido.values()) {
            String nome = PedidoService.eventoDeStatus(s);
            assertNotNull(nome);
            assertTrue(nome.startsWith("Pedido"), s + " virou " + nome);
        }
    }
}
