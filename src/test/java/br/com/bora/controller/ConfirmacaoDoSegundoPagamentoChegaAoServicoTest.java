package br.com.bora.controller;

import br.com.bora.entity.Loja;
import br.com.bora.repository.LojaRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.service.AssinaturaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A confirmação do dono tem que CHEGAR ao serviço.
 *
 * <h2>Por que este teste existe</h2>
 * <p>Achado por mutação em 21/10/2026: trocar o {@code confirmado} por {@code false} na chamada do
 * serviço passou com os <b>28 testes</b> das duas classes de serviço verdes. O efeito seria o pior
 * possível para quem usa: o dono clica "registrar os dois", a tela manda a confirmação, o servidor
 * ignora e devolve <b>409 para sempre</b> — não haveria como registrar o segundo pagamento, e o
 * conserto do item 9 ficaria inútil sem nenhum teste reclamar.</p>
 *
 * <p>Teste de serviço não cobre fiação de controller. Esta classe cobre só isso: o que o corpo da
 * requisição diz chega ao serviço.</p>
 */
class ConfirmacaoDoSegundoPagamentoChegaAoServicoTest {

    private AssinaturaService assinaturas;
    private PlataformaController controller;

    private static final Long LOJA = 18L;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void montar() {
        assinaturas = mock(AssinaturaService.class);
        LojaRepository lojas = mock(LojaRepository.class);
        AuthContext ctx = mock(AuthContext.class);

        Loja l = new Loja();
        l.id = LOJA;
        l.setNome("Zirá Açaíteria");
        when(lojas.findById(LOJA)).thenReturn(Optional.of(l));

        when(assinaturas.registrarPagamentoRecebidoForaDoAsaas(anyLong(), any(), any(), any(), anyBoolean()))
                .thenReturn(new AssinaturaService.PagamentoRegistrado(true, "pay_1", "ok"));

        controller = new PlataformaController(lojas, mock(br.com.bora.repository.UsuarioRepository.class),
                mock(org.springframework.security.crypto.password.PasswordEncoder.class), ctx,
                mock(br.com.bora.repository.UsuarioLojaRepository.class),
                mock(br.com.bora.repository.ConfigPlataformaRepository.class),
                mock(br.com.bora.service.CifragemEmRepousoService.class),
                mock(br.com.bora.service.ProvisionamentoService.class), assinaturas,
                mock(br.com.bora.service.IntegracaoService.class),
                mock(br.com.bora.repository.AssinaturaRepository.class),
                mock(br.com.bora.repository.PagamentoAssinaturaRepository.class),
                mock(br.com.bora.repository.PedidoRepository.class),
                mock(br.com.bora.service.EmpresaService.class),
                mock(br.com.bora.security.JwtService.class),
                mock(br.com.bora.repository.ConfiguracaoLojaRepository.class),
                mock(br.com.bora.service.marketplace.CredenciaisMarketplace.class),
                mock(br.com.bora.service.LimpezaDeTestesService.class), "0");
    }

    /** Manda o corpo e devolve o valor do `confirmarSegundoPagamento` que chegou ao serviço. */
    private boolean confirmacaoQueChegou(Map<String, Object> corpo) {
        controller.registrarPagamentoRecebido(LOJA, corpo);
        ArgumentCaptor<Boolean> cap = ArgumentCaptor.forClass(Boolean.class);
        verify(assinaturas).registrarPagamentoRecebidoForaDoAsaas(
                eq(LOJA), any(), any(), any(), cap.capture());
        return cap.getValue();
    }

    private Map<String, Object> corpoBase() {
        Map<String, Object> m = new HashMap<>();
        m.put("valor", "199.00");
        m.put("pagoEm", "2026-10-15");
        return m;
    }

    @Test
    void SEMoCampo_naoConfirma() {
        // O padrao seguro: quem nao disse nada nao confirmou nada.
        assertFalse(confirmacaoQueChegou(corpoBase()));
    }

    @Test
    void comTRUEbooleano_confirma() {
        Map<String, Object> corpo = corpoBase();
        corpo.put("confirmarSegundoPagamento", true);

        assertTrue(confirmacaoQueChegou(corpo));
    }

    @Test
    void comTRUEemTEXTO_confirma() {
        // O JSON do navegador manda booleano, mas um curl ou um cliente velho pode mandar a string.
        // Ler so o booleano faria a confirmacao ser ignorada em silencio.
        Map<String, Object> corpo = corpoBase();
        corpo.put("confirmarSegundoPagamento", "true");

        assertTrue(confirmacaoQueChegou(corpo));
    }

    @Test
    void comFALSE_naoConfirma() {
        Map<String, Object> corpo = corpoBase();
        corpo.put("confirmarSegundoPagamento", false);

        assertFalse(confirmacaoQueChegou(corpo));
    }

    @Test
    void comLIXOnoCampo_naoConfirma() {
        // Qualquer coisa que nao seja "sim" vale como "nao": confirmar por acidente contaria receita
        // que nao entrou.
        Map<String, Object> corpo = corpoBase();
        corpo.put("confirmarSegundoPagamento", "talvez");

        assertFalse(confirmacaoQueChegou(corpo));
    }

    @Test
    void oVALOReADATAcontinuamChegandoCertos() {
        // A fiação toda, não só o campo novo.
        Map<String, Object> corpo = corpoBase();
        corpo.put("observacao", "PIX direto na conta");

        controller.registrarPagamentoRecebido(LOJA, corpo);

        verify(assinaturas).registrarPagamentoRecebidoForaDoAsaas(eq(LOJA),
                eq(new BigDecimal("199.00")), eq(java.time.LocalDate.of(2026, 10, 15)),
                eq("PIX direto na conta"), eq(false));
    }

    @Test
    void valorComVIRGULAcontinuaSendoAceito() {
        // O dono digita "199,00" no prompt da tela.
        Map<String, Object> corpo = corpoBase();
        corpo.put("valor", "199,00");

        controller.registrarPagamentoRecebido(LOJA, corpo);

        verify(assinaturas).registrarPagamentoRecebidoForaDoAsaas(eq(LOJA),
                eq(new BigDecimal("199.00")), any(), any(), anyBoolean());
    }
}
