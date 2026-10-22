package br.com.bora.controller;

import br.com.bora.entity.Loja;
import br.com.bora.entity.PagamentoAssinatura;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PagamentoAssinaturaRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.service.AssinaturaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O faturamento do mês desconta o estorno parcial, e o webhook lê quanto voltou.
 *
 * <h2>Por que esta classe existe</h2>
 * <p>Achado por mutação em 21/10/2026: trocar {@code total.add(ficou)} por {@code total.add(v)} no
 * relatório de faturamento passou com a suíte inteira verde — <b>nada cobria a conta do
 * relatório</b>. E é a conta que o dono usa para emitir nota fiscal: contar cheio uma mensalidade
 * parcialmente devolvida significa emitir nota de dinheiro que voltou.</p>
 *
 * <p>A segunda mutação que sobreviveu: fazer a leitura dos {@code refunds} aceitar devolução
 * <b>PENDENTE</b>. Uma devolução pedida e não concluída ainda não tirou dinheiro de ninguém — contá-la
 * reduziria o faturamento de um mês que não perdeu nada.</p>
 */
class OfaturamentoDescontaOestornoTest {

    private PagamentoAssinaturaRepository pagamentos;
    private PlataformaController controller;
    private AssinaturaService servico;
    private AsaasWebhookController webhook;

    private static final Long LOJA = 18L;

    @BeforeEach
    void montar() {
        pagamentos = mock(PagamentoAssinaturaRepository.class);
        LojaRepository lojas = mock(LojaRepository.class);
        AuthContext ctx = mock(AuthContext.class);
        servico = mock(AssinaturaService.class);

        Loja l = new Loja();
        l.id = LOJA;
        l.setNome("Zirá Açaíteria");
        l.setDocumento("12345678000199");
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));

        controller = new PlataformaController(lojas, mock(br.com.bora.repository.UsuarioRepository.class),
                mock(org.springframework.security.crypto.password.PasswordEncoder.class), ctx,
                mock(br.com.bora.repository.UsuarioLojaRepository.class),
                mock(br.com.bora.repository.ConfigPlataformaRepository.class),
                mock(br.com.bora.service.CifragemEmRepousoService.class),
                mock(br.com.bora.service.ProvisionamentoService.class), servico,
                mock(br.com.bora.service.IntegracaoService.class),
                mock(br.com.bora.repository.AssinaturaRepository.class),
                pagamentos, mock(br.com.bora.repository.PedidoRepository.class),
                mock(br.com.bora.service.EmpresaService.class),
                mock(br.com.bora.security.JwtService.class),
                mock(br.com.bora.repository.ConfiguracaoLojaRepository.class),
                mock(br.com.bora.service.marketplace.CredenciaisMarketplace.class),
                mock(br.com.bora.service.LimpezaDeTestesService.class), "2");

        // O segundo argumento e o token do webhook; vazio desliga a conferencia, que nao e o assunto
        // desta classe (ela tem teste proprio no AsaasWebhookPayloadTest).
        webhook = new AsaasWebhookController(servico, "");
    }

    /** Uma mensalidade paga no mês, com o estorno que se quiser. */
    private PagamentoAssinatura mensalidade(String valor, String estornadoValor, boolean integral) {
        PagamentoAssinatura p = new PagamentoAssinatura();
        p.id = 1L;
        p.lojaId = LOJA;
        p.asaasPaymentId = "pay_1";
        p.valor = new BigDecimal(valor);
        p.pagoEm = OffsetDateTime.now().withDayOfMonth(5);
        p.estornadoValor = estornadoValor == null ? null : new BigDecimal(estornadoValor);
        if (integral) p.estornadoEm = OffsetDateTime.now();
        when(pagamentos.findByPagoEmGreaterThanEqualAndPagoEmLessThanOrderByPagoEmAsc(any(), any()))
                .thenReturn(List.of(p));
        return p;
    }

    private Map<String, Object> faturamento() {
        return controller.faturamento(null);
    }

    private BigDecimal big(Object o) {
        return o == null ? BigDecimal.ZERO : new BigDecimal(o.toString());
    }

    // =============================================================== a conta do mes

    @Test
    void mensalidadeSEMestornoCONTAcheia() {
        mensalidade("199.00", null, false);

        assertEquals(0, new BigDecimal("199.00").compareTo(big(faturamento().get("total"))));
    }

    @Test
    void oESTORNOparcialDESCONTAsoOqueVOLTOU() {
        // O coracao: R$ 10 de volta deixa R$ 189 no mes, nao R$ 199 nem R$ 0.
        mensalidade("199.00", "10.00", false);

        var r = faturamento();

        assertEquals(0, new BigDecimal("189.00").compareTo(big(r.get("total"))),
                "o mes tem que contar o que ficou");
        assertEquals(0, new BigDecimal("10.00").compareTo(big(r.get("totalEstornado"))),
                "e o que voltou aparece em separado, senao o estorno desaparece de quem confere o mes");
    }

    @Test
    void aNOTAfiscalEHdoQUEficou_naoDOcheio() {
        // Emitir nota do valor cheio de uma mensalidade parcialmente devolvida e nota de dinheiro que
        // voltou -- e e justamente para isso que esta tela existe.
        mensalidade("199.00", "10.00", false);

        assertEquals(0, new BigDecimal("189.00").compareTo(big(faturamento().get("totalSemNota"))));
    }

    @Test
    void oESTORNOintegralSAIdoMESinteiro() {
        mensalidade("199.00", null, true);

        var r = faturamento();

        assertEquals(0, BigDecimal.ZERO.compareTo(big(r.get("total"))));
        assertEquals(0, new BigDecimal("199.00").compareTo(big(r.get("totalEstornado"))));
    }

    @Test
    void aLINHAmostraOSdoisNUMEROS() {
        // Quem fecha o mes precisa de quanto voltou E quanto ficou, nao so da marca de que houve
        // estorno.
        mensalidade("199.00", "10.00", false);

        @SuppressWarnings("unchecked")
        var itens = (List<Map<String, Object>>) faturamento().get("itens");

        assertEquals(1, itens.size());
        assertEquals(0, new BigDecimal("10.00").compareTo(big(itens.get(0).get("estornadoValor"))));
        assertEquals(0, new BigDecimal("189.00").compareTo(big(itens.get(0).get("valorQueFicou"))));
        assertEquals(0, new BigDecimal("199.00").compareTo(big(itens.get(0).get("valor"))),
                "o valor cheio continua na linha: e o que foi cobrado");
    }

    // =============================================================== o webhook le os refunds

    private Map<String, Object> aviso(Object refunds) {
        Map<String, Object> pagamento = new HashMap<>();
        pagamento.put("id", "pay_1");
        pagamento.put("value", 199.00);
        pagamento.put("refunds", refunds);
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("event", "PAYMENT_PARTIALLY_REFUNDED");
        corpo.put("payment", pagamento);
        return corpo;
    }

    private BigDecimal estornadoQueChegou(Object refunds) {
        webhook.receber(null, aviso(refunds));
        var cap = org.mockito.ArgumentCaptor.forClass(BigDecimal.class);
        verify(servico).processarWebhook(eq("PAYMENT_PARTIALLY_REFUNDED"), any(), eq("pay_1"),
                cap.capture(), any(), any());
        return cap.getValue();
    }

    @Test
    void oVALORdevolvidoCHEGAaoSERVICO() {
        BigDecimal v = estornadoQueChegou(List.of(Map.of("value", 10.00, "status", "DONE")));

        assertEquals(0, new BigDecimal("10.00").compareTo(v));
    }

    @Test
    void DUASdevolucoesSOMAM() {
        BigDecimal v = estornadoQueChegou(List.of(
                Map.of("value", 10.00, "status", "DONE"),
                Map.of("value", 15.00, "status", "DONE")));

        assertEquals(0, new BigDecimal("25.00").compareTo(v));
    }

    @Test
    void aDEVOLUCAOpendenteNAOconta() {
        // Devolucao pedida e nao concluida ainda nao tirou dinheiro de ninguem. Contar reduziria o
        // faturamento de um mes que nao perdeu nada -- e a definitiva chega depois.
        BigDecimal v = estornadoQueChegou(List.of(
                Map.of("value", 10.00, "status", "DONE"),
                Map.of("value", 50.00, "status", "PENDING")));

        assertEquals(0, new BigDecimal("10.00").compareTo(v), "so a concluida conta");
    }

    @Test
    void SOpendenteVOLTAnulo() {
        assertNull(estornadoQueChegou(List.of(Map.of("value", 50.00, "status", "PENDING"))));
    }

    @Test
    void refundsAUSENTEouVAZIOvoltaNULO() {
        // Nulo faz o servico avisar alto e deixar a mensalidade cheia, em vez de chutar um numero.
        assertNull(estornadoQueChegou(null));
        reset(servico);
        assertNull(estornadoQueChegou(List.of()));
    }

    @Test
    void refundsEMformatoESTRANHOnaoDERRUBAoWEBHOOK() {
        // O webhook tem que responder 200 de qualquer jeito: se ele estourar, o Asaas reenvia para
        // sempre. E a estrutura do `refunds` nao esta documentada na pagina de webhooks.
        assertDoesNotThrow(() -> estornadoQueChegou("texto no lugar da lista"));
        reset(servico);
        assertDoesNotThrow(() -> estornadoQueChegou(List.of("sem mapa")));
        reset(servico);
        assertDoesNotThrow(() -> estornadoQueChegou(List.of(Map.of("value", "nao e numero", "status", "DONE"))));
    }
}
