package br.com.bora.service;

import br.com.bora.entity.ConfigPlataforma;
import br.com.bora.repository.ConfigPlataformaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ITEM 8 DA SECAO 4 DA VARREDURA (confirmado em 19/11/2026, mecanismo entregue em 20/11).
 *
 * <h2>O defeito</h2>
 * <p>{@code evento_outbox} nasceu na V74 e cresce para sempre: ate 6 eventos por pedido, ~620 bytes
 * cada, e ninguem apaga nada -- nao ha publicador nem consumidor ainda. Com 23 lojas vendendo, e
 * crescimento continuo num banco que ninguem olha, e a primeira a notar seria o disco.</p>
 *
 * <h2>O que este teste guarda, e nao e so o DELETE</h2>
 * <p>A varredura dizia que "a decisao de retencao deve vir JUNTO com o consumidor", e isso continua
 * certo. Entao o que entrou e o mecanismo <b>desligado</b>, com duas chaves separadas. O que estes
 * testes protegem, em ordem de gravidade:</p>
 * <ol>
 *   <li><b>Sem chave, nao apaga NADA.</b> E o estado de hoje. Um bug aqui apaga dado de producao
 *       que ninguem leu, e nao tem volta.</li>
 *   <li><b>Valor invalido tambem nao apaga nada</b> -- e nao e tratado como "0 dias", que apagaria a
 *       fila inteira. Este projeto ja errou tres vezes a familia "ausencia virando false/zero".</li>
 *   <li><b>As duas chaves sao independentes:</b> ligar a limpeza do que ja foi publicado nao pode
 *       levar embora o que ninguem leu.</li>
 * </ol>
 */
class AfilaDEeventosTEMretencaoTest {

    private JdbcTemplate jdbc;
    private ConfigPlataformaRepository configs;
    private RetencaoDaFila retencao;

    @BeforeEach
    void montar() {
        jdbc = mock(JdbcTemplate.class);
        configs = mock(ConfigPlataformaRepository.class);
        when(configs.findById(anyString())).thenReturn(Optional.empty());
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        retencao = new RetencaoDaFila(jdbc, configs);
    }

    private void chave(String nome, String valor) {
        ConfigPlataforma c = new ConfigPlataforma();
        c.setChave(nome);
        c.setValor(valor);
        when(configs.findById(nome)).thenReturn(Optional.of(c));
    }

    // =================================================================== a premissa

    // SEM ISTO, "nao apaga nada" passaria com um metodo que nunca apaga nada em nenhuma situacao --
    // e a retencao seria um botao que nao faz nada, com a suite verde.
    @Test
    void premissa_comACHAVEdefinidaOdeleteRODA() {
        chave(RetencaoDaFila.CHAVE_DIAS, "90");
        when(jdbc.update(contains("publicado_em is not null"), eq(90))).thenReturn(1234);

        Map<String, Object> r = retencao.limpar();

        assertEquals(1234L, r.get("publicadosApagados"));
        assertEquals(90, r.get("retencaoDias"));
        verify(jdbc).update(contains("publicado_em is not null"), eq(90));
    }

    // =================================================================== nao apagar nada

    @Test
    void semCHAVEnenhumaNADAehAPAGADO() {
        // O ESTADO DE HOJE. Esta e a linha que separa "mecanismo pronto" de "fila apagada".
        Map<String, Object> r = retencao.limpar();

        assertEquals(0L, r.get("publicadosApagados"));
        assertEquals(0L, r.get("pendentesApagados"));
        assertEquals(0L, r.get("consumidosApagados"));
        assertNull(r.get("retencaoDias"));
        assertNull(r.get("retencaoDiasPendentes"));
        verify(jdbc, never()).update(anyString(), anyInt());
        verify(jdbc, never()).update(anyString());
    }

    @Test
    void valorINVALIDOnaoEHzeroDIAS() {
        // Zero, negativo, vazio e texto: todos "nao apaga". Tratar valor invalido como 0 dias
        // apagaria a fila INTEIRA -- e um erro de digitacao na tela nao pode ter esse efeito.
        for (String ruim : new String[]{"0", "-1", "", "   ", "noventa", "90 dias", "9.5"}) {
            JdbcTemplate j = mock(JdbcTemplate.class);
            ConfigPlataformaRepository c = mock(ConfigPlataformaRepository.class);
            ConfigPlataforma linha = new ConfigPlataforma();
            linha.setChave(RetencaoDaFila.CHAVE_DIAS);
            linha.setValor(ruim);
            when(c.findById(anyString())).thenReturn(Optional.empty());
            when(c.findById(RetencaoDaFila.CHAVE_DIAS)).thenReturn(Optional.of(linha));

            Map<String, Object> r = new RetencaoDaFila(j, c).limpar();

            assertEquals(0L, r.get("publicadosApagados"), "o valor \"" + ruim + "\" apagou algo");
            verify(j, never()).update(anyString(), anyInt());
        }
    }

    @Test
    void aCHAVEqueNAOdaPARAlerNAOapagaNADAeNAOderrubaARotina() {
        // `config_plataforma.valor` e cifrado em repouso: a leitura lanca quando BORA_SEGREDO_COLUNAS
        // esta ausente ou foi TROCADA. Aqui isso nao pode virar excecao na rotina da madrugada --
        // e, acima de tudo, nao pode virar "nenhum dia", que apagaria tudo.
        when(configs.findById(RetencaoDaFila.CHAVE_DIAS))
                .thenThrow(new IllegalStateException("chave de cifragem ausente"));

        assertDoesNotThrow(() -> retencao.rotinaDiaria());
        assertNull(retencao.diasDaChave(RetencaoDaFila.CHAVE_DIAS));
        verify(jdbc, never()).update(anyString(), anyInt());
    }

    @Test
    void aROTINAdiariaNAOpropagaFALHAdoBANCO() {
        chave(RetencaoDaFila.CHAVE_DIAS, "30");
        when(jdbc.update(anyString(), eq(30))).thenThrow(new RuntimeException("banco fora"));

        // Faxina que falha tenta de novo amanha; derrubar o agendador pararia TODAS as rotinas.
        assertDoesNotThrow(() -> retencao.rotinaDiaria());
    }

    // =================================================================== as duas chaves

    @Test
    void asDUASchavesSAOindependentes() {
        // Ligar a limpeza do que JA FOI PUBLICADO nao pode levar embora o que ninguem leu. Hoje
        // nada publica, entao tudo na fila e "nao publicado": confundir as duas apagaria a fila toda.
        chave(RetencaoDaFila.CHAVE_DIAS, "90");
        when(jdbc.update(contains("publicado_em is not null"), eq(90))).thenReturn(10);

        Map<String, Object> r = retencao.limpar();

        assertEquals(10L, r.get("publicadosApagados"));
        assertEquals(0L, r.get("pendentesApagados"), "a limpeza de publicados apagou pendente");
        verify(jdbc, never()).update(contains("publicado_em is null"), anyInt());
    }

    @Test
    void aCHAVEdePENDENTESapagaOqueNINGUEMleu() {
        chave(RetencaoDaFila.CHAVE_DIAS_PENDENTES, "365");
        when(jdbc.update(contains("publicado_em is null"), eq(365))).thenReturn(7);

        Map<String, Object> r = retencao.limpar();

        assertEquals(7L, r.get("pendentesApagados"));
        assertEquals(0L, r.get("publicadosApagados"));
        verify(jdbc, never()).update(contains("publicado_em is not null"), anyInt());
    }

    @Test
    void oLOTElimitaCADAexecucao() {
        // DELETE que pega milhoes de linhas de uma vez mantem lock e infla o WAL por minutos, no
        // mesmo banco onde as lojas estao vendendo. O que sobrar sai amanha.
        chave(RetencaoDaFila.CHAVE_DIAS, "90");
        retencao.limpar();

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(90));
        assertTrue(sql.getValue().contains("limit " + RetencaoDaFila.LOTE),
                "o DELETE perdeu o teto por execucao: " + sql.getValue());
    }

    // =================================================================== a tabela ao lado

    @Test
    void oEVENTOconsumidoSOehLIMPADOquandoALGOfoiAPAGADO() {
        // Sem nada apagado da fila nao existe linha orfa nova, e varrer `evento_consumido` por
        // nada custaria um NOT EXISTS na tabela inteira toda madrugada.
        chave(RetencaoDaFila.CHAVE_DIAS, "90");
        when(jdbc.update(anyString(), eq(90))).thenReturn(0);

        retencao.limpar();
        verify(jdbc, never()).update(contains("evento_consumido"));

        when(jdbc.update(anyString(), eq(90))).thenReturn(5);
        when(jdbc.update(contains("evento_consumido"))).thenReturn(2);
        Map<String, Object> r = retencao.limpar();

        assertEquals(2L, r.get("consumidosApagados"));
    }

    // =================================================================== a tela

    @Test
    void oESTADOnaoDERRUBAaTELAquandoNAOdaPARAmedir() {
        // Medir nao pode ser o que quebra: a tela mostra "nao medido", que e resposta honesta.
        when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenThrow(new RuntimeException("sem banco"));

        Map<String, Object> m = assertDoesNotThrow(() -> retencao.estado());

        assertNull(m.get("naFila"));
        assertNull(m.get("pendentes"));
        assertTrue(m.containsKey("tetoDeAvisoLinhas"), "o teto e constante: tem de sair mesmo assim");
    }

    @Test
    void oESTADOlevaOtamanhoEaIDADEdaFILAparaATELA() {
        // O PIOR DO ITEM 8 nao era a falta do DELETE: era que o tamanho da fila nao aparecia em
        // lugar nenhum. Sem estes campos, "a fila esta crescendo" seria descoberto pelo disco.
        when(jdbc.queryForObject(eq("select count(*) from evento_outbox"), eq(Long.class))).thenReturn(1234L);
        when(jdbc.queryForObject(contains("publicado_em is null"), eq(Long.class))).thenReturn(1234L);
        when(jdbc.queryForObject(contains("min(criado_em)"), eq(Long.class))).thenReturn(12L);

        Map<String, Object> m = retencao.estado();

        assertEquals(1234L, m.get("naFila"));
        assertEquals(1234L, m.get("pendentes"));
        assertEquals(12L, m.get("maisAntigoDias"));
        assertNull(m.get("ultimaLimpeza"), "nunca limpou: nao pode inventar uma data");
        assertNull(m.get("apagadosNaUltimaLimpeza"));
    }

    @Test
    void depoisDeLIMPARoESTADOdizQUANDOeQUANTO() {
        chave(RetencaoDaFila.CHAVE_DIAS, "90");
        when(jdbc.update(anyString(), eq(90))).thenReturn(3);
        when(jdbc.update(contains("evento_consumido"))).thenReturn(0);

        retencao.limpar();
        Map<String, Object> m = retencao.estado();

        assertNotNull(m.get("ultimaLimpeza"));
        assertEquals(3L, m.get("apagadosNaUltimaLimpeza"));
    }
}
