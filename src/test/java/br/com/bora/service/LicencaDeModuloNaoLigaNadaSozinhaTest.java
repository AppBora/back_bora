package br.com.bora.service;

import br.com.bora.entity.ConfigPlataforma;
import br.com.bora.entity.LicencaModulo;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Modulo;
import br.com.bora.repository.ConfigPlataformaRepository;
import br.com.bora.repository.LicencaModuloRepository;
import br.com.bora.repository.LojaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * O portão de licença de módulo ({@link LicencaModuloService}).
 *
 * <h2>O que este arquivo guarda, e é a coisa mais importante da V74</h2>
 * <p>A tabela {@code licenca_modulo} nasceu <b>vazia</b> em produção, com 23 lojas rodando. A
 * promessa que isso faz é: <b>sem linha, o comportamento é exatamente o de antes</b>. Se essa
 * promessa quebrar, um cliente perde um módulo que pagou — ou ganha um que não contratou, e esse é
 * pior, porque módulo ligado por engano escreve no banco dele.</p>
 *
 * <p>Por isso quase todo teste aqui é sobre o caso <b>sem linha nenhuma</b>.</p>
 *
 * <h2>O que não prova</h2>
 * <p>Não prova que a tabela existe nem que a entidade casa com ela — isso é o
 * {@code ddl-auto=validate} contra um Postgres de verdade, e foi medido à parte.</p>
 */
class LicencaDeModuloNaoLigaNadaSozinhaTest {

    private final LicencaModuloRepository licencas = mock(LicencaModuloRepository.class);
    private final ConfigPlataformaRepository configs = mock(ConfigPlataformaRepository.class);
    private final LojaRepository lojas = mock(LojaRepository.class);

    private final LicencaModuloService servico = new LicencaModuloService(licencas, configs, lojas);

    private void semLinhaNenhuma() {
        when(licencas.findByLojaIdAndModulo(any(), any())).thenReturn(Optional.empty());
    }

    private void chaveFiscalGlobal(String valor) {
        ConfigPlataforma c = new ConfigPlataforma();
        c.setChave("fiscal.habilitado");
        c.setValor(valor);
        when(configs.findById("fiscal.habilitado")).thenReturn(Optional.of(c));
    }

    private void lojaComIa(long id, boolean ia) {
        Loja l = new Loja();
        l.setId(id);
        l.moduloIa = ia;
        when(lojas.findById(id)).thenReturn(Optional.of(l));
    }

    // ------------------------------------------------- o principal nao se licencia

    @Test
    void coreEpedidosEstaoSempreLigados() {
        semLinhaNenhuma();
        assertTrue(servico.ligado(7L, Modulo.CORE));
        assertTrue(servico.ligado(7L, Modulo.PEDIDOS));
        // Nem precisam de loja: sao o modulo principal, nao um produto contratado.
        assertTrue(servico.ligado(null, Modulo.CORE));
        verifyNoInteractions(licencas, configs, lojas);
    }

    @Test
    void naoDaParaDesligarOqueFazParteDoPrincipal() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> servico.definir(7L, Modulo.PEDIDOS, false, 1L, "teste"));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode(),
                "tem de virar 400 na API, e n\u00e3o 500 -- IllegalArgumentException virava 500");
        verify(licencas, never()).save(any());
    }

    /**
     * FISCAL e IA ainda s\u00e3o governados pelos mecanismos antigos. Gravar linha aqui criaria uma
     * TERCEIRA opini\u00e3o -- e em dinheiro: o {@code IaService} e a cobran\u00e7a leem
     * {@code loja.modulo_ia}, ent\u00e3o uma licen\u00e7a ativa diria "ligado" para um add-on que ningu\u00e9m
     * est\u00e1 cobrando.
     */
    @Test
    void naoGravaLicencaDeModuloQueOmecanismoAntigoAindaGoverna() {
        for (Modulo m : new Modulo[]{Modulo.FISCAL, Modulo.IA}) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> servico.definir(7L, m, true, 1L, null), m.name());
            assertEquals(HttpStatus.CONFLICT, e.getStatusCode(), m.name());
        }
        verify(licencas, never()).save(any());
        verify(lojas, never()).findById(any());
    }

    @Test
    void lojaQueNaoExisteNaoRecebeLicenca() {
        when(lojas.findById(777L)).thenReturn(Optional.empty());
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> servico.definir(777L, Modulo.ESTOQUE, true, 1L, null));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode(),
                "sem isto vira 500 por viola\u00e7\u00e3o de chave estrangeira");
        verify(licencas, never()).save(any());
    }

    // ------------------------------------------------- sem linha: nada muda

    @Test
    void semLinhaOfiscalSegueAchaveGlobal() {
        semLinhaNenhuma();
        chaveFiscalGlobal("true");
        assertTrue(servico.ligado(7L, Modulo.FISCAL), "a chave global diz true");

        chaveFiscalGlobal("false");
        assertFalse(servico.ligado(7L, Modulo.FISCAL), "a chave global diz false");
    }

    @Test
    void semLinhaEsemChaveOfiscalEhNao() {
        semLinhaNenhuma();
        when(configs.findById("fiscal.habilitado")).thenReturn(Optional.empty());
        assertFalse(servico.ligado(7L, Modulo.FISCAL), "lacuna nunca vira sim");
    }

    @Test
    void semLinhaOiaSegueAcolunaDaLoja() {
        semLinhaNenhuma();
        lojaComIa(7L, true);
        assertTrue(servico.ligado(7L, Modulo.IA));

        lojaComIa(8L, false);
        assertFalse(servico.ligado(8L, Modulo.IA));
    }

    /**
     * O estado de produção em 17/11/2026: tabela vazia, nenhum módulo do ERP construído. Tudo o que
     * não existe tem de responder <b>não</b> — e sem perguntar nada a banco nenhum.
     */
    @Test
    void semLinhaOsModulosQueAindaNaoExistemSaoNao() {
        semLinhaNenhuma();
        for (Modulo m : new Modulo[]{Modulo.ESTOQUE, Modulo.COMPRAS, Modulo.FINANCEIRO,
                                     Modulo.CONTABIL, Modulo.GESTAO, Modulo.LOGISTICA}) {
            assertFalse(servico.ligado(7L, m), m + " nao existe e nao pode responder sim");
        }
        verifyNoInteractions(configs, lojas);
    }

    // ------------------------------------------------- com linha: a linha manda

    @Test
    void comLinhaAtivaAlinhaGanhaDoMecanismoAntigo() {
        LicencaModulo l = new LicencaModulo();
        l.setLojaId(7L);
        l.setModulo(Modulo.FISCAL);
        l.setAtivo(true);
        when(licencas.findByLojaIdAndModulo(7L, Modulo.FISCAL)).thenReturn(Optional.of(l));
        chaveFiscalGlobal("false"); // o antigo diz NAO

        assertTrue(servico.ligado(7L, Modulo.FISCAL),
                "e assim que um modulo migra de loja em loja, sem big-bang");
        verify(configs, never()).findById(any());
    }

    @Test
    void comLinhaInativaAlinhaGanhaTambem() {
        LicencaModulo l = new LicencaModulo();
        l.setLojaId(7L);
        l.setModulo(Modulo.IA);
        l.setAtivo(false);
        when(licencas.findByLojaIdAndModulo(7L, Modulo.IA)).thenReturn(Optional.of(l));
        lojaComIa(7L, true); // o antigo diz SIM

        assertFalse(servico.ligado(7L, Modulo.IA));
        verify(lojas, never()).findById(any());
    }

    // ------------------------------------------------- "nao sei" responde nao

    @Test
    void semLojaOuSemModuloResponderNaoEmVezDeEstourar() {
        semLinhaNenhuma();
        assertFalse(servico.ligado(null, Modulo.FISCAL), "sem loja no contexto");
        assertFalse(servico.ligado(7L, null), "modulo nulo");
    }

    @Test
    void lojaQueNaoExisteNaoLigaIa() {
        semLinhaNenhuma();
        when(lojas.findById(999L)).thenReturn(Optional.empty());
        assertFalse(servico.ligado(999L, Modulo.IA));
    }

    // ------------------------------------------------- gravar a decisao

    @Test
    void ligarGravaQuemLigouEquando() {
        when(licencas.findByLojaIdAndModulo(7L, Modulo.ESTOQUE)).thenReturn(Optional.empty());
        when(licencas.save(any())).thenAnswer(i -> i.getArgument(0));
        when(lojas.findById(7L)).thenReturn(Optional.of(new Loja()));

        LicencaModulo l = servico.definir(7L, Modulo.ESTOQUE, true, 42L, null);

        assertTrue(l.getAtivo());
        assertEquals(42L, l.getAtivadoPor());
        assertNotNull(l.getAtivadoEm());
        assertNull(l.getDesativadoEm(), "ligar limpa a marca de desligamento anterior");
    }

    /**
     * O motivo não é enfeite: falta de pagamento volta sozinho quando o cliente paga; pedido de
     * cancelamento não volta. Sem o motivo gravado, as duas situações ficam indistinguíveis.
     */
    @Test
    void desligarGravaOmotivo() {
        LicencaModulo existente = new LicencaModulo();
        existente.setLojaId(7L);
        existente.setModulo(Modulo.ESTOQUE);
        existente.setAtivo(true);
        when(licencas.findByLojaIdAndModulo(7L, Modulo.ESTOQUE)).thenReturn(Optional.of(existente));
        when(licencas.save(any())).thenAnswer(i -> i.getArgument(0));
        when(lojas.findById(7L)).thenReturn(Optional.of(new Loja()));

        LicencaModulo l = servico.definir(7L, Modulo.ESTOQUE, false, 42L, "cliente cancelou");

        assertFalse(l.getAtivo());
        assertEquals("cliente cancelou", l.getMotivoDesativacao());
        assertNotNull(l.getDesativadoEm());
        assertEquals(42L, l.getDesativadoPor(), "o motivo sem o quem n\u00e3o fecha a conversa");
    }

    // ------------------------------------------------- enum x banco

    /**
     * O enum {@link Modulo} e o {@code CHECK} da coluna {@code licenca_modulo.modulo} sao DUAS
     * listas do mesmo conjunto, em arquivos diferentes.
     *
     * <p>Adicionar um modulo no enum e esquecer da migracao nao quebra o build, nao quebra o
     * startup, e nao aparece em teste nenhum -- aparece quando alguem tenta LIGAR aquele modulo
     * para um cliente e leva uma violacao de constraint, em producao. Este teste le o SQL e
     * compara.</p>
     */
    @Test
    void todoModuloDoEnumEhAceitoPeloCheckDaMigracao() throws Exception {
        java.nio.file.Path sql = java.nio.file.Path.of(
                "src/main/resources/db/migration/V74__fundacao_para_o_acoplamento_dos_modulos.sql");
        assertTrue(java.nio.file.Files.exists(sql), "a V74 mudou de nome? " + sql);
        String texto = java.nio.file.Files.readString(sql, java.nio.charset.StandardCharsets.UTF_8);

        // Por REGEX, e n\u00e3o por espa\u00e7amento exato: a primeira vers\u00e3o casava a linha inteira
        // ("modulo             TEXT        NOT NULL CHECK...") e qualquer reformata\u00e7\u00e3o da V74 a
        // quebrava -- um teste que quebra por indenta\u00e7\u00e3o ensina a desligar o teste.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("modulo\\s+TEXT\\s+NOT NULL\\s+CHECK\\s*\\(\\s*modulo\\s+IN\\s*\\(([^)]*)\\)")
                .matcher(texto);
        assertTrue(m.find(), "nao achei o CHECK da coluna modulo na V74");
        String lista = m.group(1);

        for (Modulo mod : Modulo.values()) {
            assertTrue(lista.contains("'" + mod.name() + "'"),
                    "o modulo " + mod + " existe no enum e NAO esta no CHECK da V74 -- ligar ele "
                            + "para um cliente estouraria violacao de constraint em producao");
        }
        // O OUTRO SENTIDO, que faltava: valor no banco que o enum n\u00e3o conhece faz o
        // Enumerated(STRING) estourar IllegalArgumentException ao LER a linha -- e a\u00ed a tela
        // quebra na leitura, n\u00e3o na escrita.
        for (String noBanco : lista.replace("'", "").split(",")) {
            String v = noBanco.trim();
            if (v.isEmpty()) continue;
            assertDoesNotThrow(() -> Modulo.valueOf(v),
                    "o CHECK da V74 aceita \"" + v + "\", que o enum Modulo n\u00e3o tem -- ler essa "
                            + "linha estouraria na convers\u00e3o");
        }
    }

    // ------------------------------------------------- o quadro completo

    @Test
    void situacaoDeUmaLojaDeHojeMostraSoOprincipalLigado() {
        semLinhaNenhuma();
        when(configs.findById("fiscal.habilitado")).thenReturn(Optional.empty());
        lojaComIa(7L, false);

        Map<Modulo, Boolean> s = servico.situacao(7L);

        assertEquals(Modulo.values().length, s.size(), "o quadro lista todos os modulos");
        assertEquals(2, s.values().stream().filter(Boolean::booleanValue).count(),
                "numa loja de hoje so CORE e PEDIDOS estao ligados");
        assertTrue(s.get(Modulo.CORE));
        assertTrue(s.get(Modulo.PEDIDOS));
    }
}
