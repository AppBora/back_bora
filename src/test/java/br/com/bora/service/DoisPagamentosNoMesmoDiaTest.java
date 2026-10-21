package br.com.bora.service;

import br.com.bora.entity.Assinatura;
import br.com.bora.entity.Loja;
import br.com.bora.entity.PagamentoAssinatura;
import br.com.bora.entity.Plano;
import br.com.bora.entity.StatusAssinatura;
import br.com.bora.repository.AssinaturaRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PagamentoAssinaturaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Dois pagamentos registrados à mão no mesmo dia: os dois têm que contar.
 *
 * <h2>O defeito (medido em 21/10/2026)</h2>
 * <p>Sem cobrança aberta no Asaas, a chave do registro era {@code "manual:" + lojaId + ":" + dia} — a
 * mesma para todo pagamento daquela loja naquele dia. E o {@code registrarPagamento} descarta id
 * repetido <b>em silêncio</b>. O segundo pagamento desaparecia do faturamento, e a tela dizia
 * "Pagamento registrado".</p>
 *
 * <p><b>Conferido na produção:</b> a loja 18 tem dois registros de R$ 199 no mesmo dia (15/10/2026),
 * somando os R$ 398 que ela pagou — e <b>nada se perdeu</b>, por sorte: um dos dois achou cobrança
 * aberta no Asaas e ficou com o id de lá, então as chaves não colidiram. Hoje a assinatura dela está
 * deletada no Asaas, ou seja: não há cobrança aberta, e o próximo par de registros no mesmo dia
 * colidiria.</p>
 *
 * <h2>Os dois erros opostos</h2>
 * <p>Chave única resolve a perda, mas abre a porta para o clique duplo contar duas vezes — e contar
 * receita que não entrou é tão ruim quanto perder a que entrou. Pelo dado não há como distinguir, então
 * quem decide é o dono: mesmo dia e mesmo valor <b>recusa</b> e pede confirmação; valor diferente
 * registra; confirmado registra com chave própria.</p>
 */
class DoisPagamentosNoMesmoDiaTest {

    private PagamentoAssinaturaRepository pagamentos;
    private AsaasClient asaas;
    private AssinaturaService service;
    private final List<PagamentoAssinatura> gravados = new ArrayList<>();
    /** As chaves que "já estão no banco", para o existsByAsaasPaymentId responder a verdade. */
    private final java.util.Set<String> chavesNoBanco = new java.util.HashSet<>();

    private static final Long LOJA = 18L;
    private static final LocalDate DIA = LocalDate.of(2026, 10, 15);
    private static final BigDecimal CENTO_NOVENTA_NOVE = new BigDecimal("199.00");

    @BeforeEach
    void montar() {
        pagamentos = mock(PagamentoAssinaturaRepository.class);
        asaas = mock(AsaasClient.class);
        AssinaturaRepository repo = mock(AssinaturaRepository.class);
        LojaRepository lojas = mock(LojaRepository.class);
        service = new AssinaturaService(repo, lojas, mock(UsuarioRepository.class), asaas,
                mock(AuthContext.class), pagamentos, 10);

        Assinatura a = new Assinatura();
        a.setId(4L);
        a.setLojaId(LOJA);
        a.setPlano(Plano.UNICO);
        a.setStatus(StatusAssinatura.INADIMPLENTE);
        a.setValor(new BigDecimal("298.00"));
        a.setAsaasSubscriptionId("sub_zira");
        when(repo.findByLojaId(LOJA)).thenReturn(Optional.of(a));
        when(repo.save(any(Assinatura.class))).thenAnswer(i -> i.getArgument(0));

        Loja l = new Loja();
        l.id = LOJA;
        l.ativo = true;
        l.setNome("Zirá Açaíteria");
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));
        when(lojas.save(any(Loja.class))).thenAnswer(i -> i.getArgument(0));

        gravados.clear();
        chavesNoBanco.clear();
        when(pagamentos.existsByAsaasPaymentId(anyString()))
                .thenAnswer(i -> chavesNoBanco.contains(i.getArgument(0)));
        // O save e chamado DUAS vezes por registro: uma para gravar e outra para acrescentar a
        // observacao na mesma linha. Um dublê que so faz `add` contaria dois registros para um
        // pagamento -- e foi o que aconteceu na primeira versao deste teste. Aqui o segundo save
        // SUBSTITUI, como um repositorio de verdade faz.
        when(pagamentos.save(any(PagamentoAssinatura.class))).thenAnswer(i -> {
            PagamentoAssinatura pg = i.getArgument(0);
            gravados.removeIf(x -> x.asaasPaymentId != null
                    && x.asaasPaymentId.equals(pg.asaasPaymentId));
            gravados.add(pg);
            chavesNoBanco.add(pg.asaasPaymentId);
            return pg;
        });
        when(pagamentos.findFirstByAsaasPaymentId(anyString())).thenAnswer(i -> gravados.stream()
                .filter(pg -> i.getArgument(0).equals(pg.asaasPaymentId)).findFirst());
        // O que ja esta registrado naquele dia sai da lista de gravados: o dublê acompanha o que
        // o proprio teste gravou, em vez de responder sempre o mesmo.
        when(pagamentos.findByLojaIdAndPagoEmGreaterThanEqualAndPagoEmLessThan(anyLong(), any(), any()))
                .thenAnswer(i -> gravados.stream().filter(pg -> LOJA.equals(pg.lojaId)).toList());

        // Sem cobranca aberta no Asaas: e o caso da Zira hoje (assinatura deletada la fora), e e o
        // caso em que a chave manual e usada.
        when(asaas.configurado()).thenReturn(true);
        when(asaas.cobrancaAbertaDaAssinatura("sub_zira")).thenReturn(null);
    }

    private AssinaturaService.PagamentoRegistrado registrar(String valor, boolean confirmado) {
        return service.registrarPagamentoRecebidoForaDoAsaas(
                LOJA, new BigDecimal(valor), DIA, "PIX direto na conta", confirmado);
    }

    // =============================================================== o conserto

    @Test
    void oSEGUNDOpagamentoDoMESMOdiaEvalorEhRECUSADO_naoDescartadoEmSilencio() {
        registrar("199.00", false);
        assertEquals(1, gravados.size());

        var erro = assertThrows(ResponseStatusException.class, () -> registrar("199.00", false));

        assertEquals(HttpStatus.CONFLICT, erro.getStatusCode());
        assertEquals(1, gravados.size(), "nao pode gravar nada na recusa");
    }

    @Test
    void aRECUSAdizOvalorEoDIA_paraOdonoDecidirSabendo() {
        registrar("199.00", false);

        var erro = assertThrows(ResponseStatusException.class, () -> registrar("199.00", false));

        assertTrue(erro.getReason().contains("199.00"), "a mensagem foi: " + erro.getReason());
        assertTrue(erro.getReason().contains("2026-10-15"), "a mensagem foi: " + erro.getReason());
        assertTrue(erro.getReason().toLowerCase().contains("confirme"),
                "tem que dizer o que fazer; foi: " + erro.getReason());
    }

    @Test
    void CONFIRMADO_osDOISpagamentosEntramNoFaturamento() {
        // O coracao do conserto. Antes, o segundo desaparecia e a tela dizia "Pagamento registrado".
        registrar("199.00", false);
        registrar("199.00", true);

        assertEquals(2, gravados.size(), "os dois R$ 199 tem que contar");
        assertEquals(2, gravados.stream().map(pg -> pg.asaasPaymentId).distinct().count(),
                "com chaves DIFERENTES, senao o segundo cai no descarte silencioso");
        assertEquals(0, gravados.stream().map(pg -> pg.valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(new BigDecimal("398.00")),
                "e a soma tem que dar os R$ 398 que a loja pagou");
    }

    @Test
    void aCHAVEmanualTemNUMEROnoFim_eEhLEGIVEL() {
        // Legivel de proposito: quem for conferir o faturamento a mao enxerga a loja, o dia e qual
        // registro daquele dia. Um UUID resolveria a colisao e tornaria a conferencia impossivel.
        registrar("199.00", false);

        assertEquals("manual:18:2026-10-15:1", gravados.get(0).asaasPaymentId);
    }

    @Test
    void oTERCEIROpagamentoDoDiaGanhaOnumeroSEGUINTE() {
        registrar("199.00", false);
        registrar("199.00", true);
        registrar("199.00", true);

        assertEquals(List.of("manual:18:2026-10-15:1", "manual:18:2026-10-15:2", "manual:18:2026-10-15:3"),
                gravados.stream().map(pg -> pg.asaasPaymentId).toList());
    }

    @Test
    void aCHAVEantigaSEMnumeroNAOestorva() {
        // A loja 18 tem uma chave "manual:18:2026-10-15" no banco, do formato antigo. Ela nao colide
        // com nenhuma ":n", entao o registro novo segue normal -- e o antigo continua la, contando no
        // faturamento.
        chavesNoBanco.add("manual:18:2026-10-15");

        registrar("199.00", false);

        assertEquals("manual:18:2026-10-15:1", gravados.get(0).asaasPaymentId);
    }

    // =============================================================== valor diferente nao pergunta

    @Test
    void valorDIFERENTEnoMesmoDiaNAOpergunta() {
        // Dois valores diferentes no mesmo dia nao sao clique duplo: sao dois pagamentos. Perguntar
        // aqui seria atrito sem motivo.
        registrar("199.00", false);

        assertDoesNotThrow(() -> registrar("99.00", false));

        assertEquals(2, gravados.size());
    }

    // =============================================================== o caminho com cobranca aberta

    @Test
    void comCOBRANCAabertaNoAsaas_oIdDeLAehUsado() {
        // Quando o Asaas tem cobranca aberta e a baixa sai, o id dele e melhor que a chave manual:
        // amarra o nosso registro a cobranca de la.
        when(asaas.cobrancaAbertaDaAssinatura("sub_zira")).thenReturn("pay_real");
        when(asaas.receberEmDinheiro(eq("pay_real"), anyDouble(), anyString())).thenReturn(true);

        registrar("199.00", false);

        assertEquals("pay_real", gravados.get(0).asaasPaymentId);
    }

    @Test
    void seOidDoAsaasJAestaRegistrado_caiNaChaveManualEmVezDeDESCARTAR() {
        // O outro caminho para a mesma perda: o Asaas devolve uma cobranca que ja esta no nosso
        // faturamento. Antes, o registrarPagamento descartava em silencio.
        chavesNoBanco.add("pay_repetida");
        when(asaas.cobrancaAbertaDaAssinatura("sub_zira")).thenReturn("pay_repetida");
        when(asaas.receberEmDinheiro(eq("pay_repetida"), anyDouble(), anyString())).thenReturn(true);

        registrar("199.00", true);

        assertEquals(1, gravados.size(), "o pagamento nao pode ser perdido");
        assertEquals("manual:18:2026-10-15:1", gravados.get(0).asaasPaymentId);
    }

    @Test
    void baixaQueFALHAnoAsaas_usaAchaveManualEavisa() {
        when(asaas.cobrancaAbertaDaAssinatura("sub_zira")).thenReturn("pay_real");
        when(asaas.receberEmDinheiro(anyString(), anyDouble(), anyString())).thenReturn(false);

        var r = registrar("199.00", false);

        assertEquals("manual:18:2026-10-15:1", gravados.get(0).asaasPaymentId);
        assertFalse(r.baixadoNoAsaas());
        assertTrue(r.recado().contains("continua aberta"), "o recado foi: " + r.recado());
    }

    // =============================================================== o que ja funcionava

    @Test
    void oREGISTROcontinuaDevolvendoOacessoSEMprazo() {
        var r = registrar("199.00", false);

        assertTrue(r.recado().toLowerCase().contains("registrado"));
        assertEquals(1, gravados.size());
        assertEquals(LOJA, gravados.get(0).lojaId);
        assertEquals(4L, gravados.get(0).assinaturaId);
    }

    @Test
    void aOBSERVACAOcontinuaSendoGuardada() {
        registrar("199.00", false);

        assertTrue(String.valueOf(gravados.get(0).descricao).contains("PIX direto na conta"),
                "a descricao foi: " + gravados.get(0).descricao);
    }

    @Test
    void valorZEROouNEGATIVOcontinuaRecusado() {
        assertThrows(ResponseStatusException.class, () -> registrar("0", false));
        assertThrows(ResponseStatusException.class, () -> registrar("-5", false));
        assertEquals(0, gravados.size());
    }

    @Test
    void dataNOFUTUROcontinuaRecusada() {
        var erro = assertThrows(ResponseStatusException.class, () ->
                service.registrarPagamentoRecebidoForaDoAsaas(LOJA, CENTO_NOVENTA_NOVE,
                        LocalDate.now().plusDays(1), null, false));

        assertEquals(HttpStatus.BAD_REQUEST, erro.getStatusCode());
    }

    @Test
    void aSOBRECARGAantigaDeQUATROargumentosContinuaValendo_eNAOconfirma() {
        // Quem chamar sem o quinto argumento tem o comportamento seguro: pergunta no caso ambiguo.
        service.registrarPagamentoRecebidoForaDoAsaas(LOJA, CENTO_NOVENTA_NOVE, DIA, null);

        assertThrows(ResponseStatusException.class, () ->
                service.registrarPagamentoRecebidoForaDoAsaas(LOJA, CENTO_NOVENTA_NOVE, DIA, null));
    }
}
