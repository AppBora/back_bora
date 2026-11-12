package br.com.bora.service;

import br.com.bora.entity.ConfiguracaoLoja;
import br.com.bora.entity.Plano;
import br.com.bora.repository.ConfiguracaoLojaRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O percentual de cashback e a aliquota de imposto: quem grava, e com que limites.
 *
 * <h2>Por que este arquivo existe (pente fino de 29/10/2026)</h2>
 * <p>{@code ConfiguracaoLojaService.atualizar} e quem grava o percentual que <b>todo pedido</b> usa
 * para creditar saldo, e a aliquota que entra nos relatorios. <b>Nenhum teste exercitava esse
 * metodo</b>: {@code grep -rl "\bConfiguracaoLojaService\b" src/test/java} devolvia 0 arquivos, e a
 * unica linha do projeto que tocava o campo era uma atribuicao de fixture
 * ({@code CancelamentoDevolveCashbackTest:63}).</p>
 *
 * <p>O que ficava sem rede: o teto de 50% do cashback, a faixa de 0 a 100 da aliquota, o custo fixo
 * nao-negativo, e a regra <b>"zero nao e vazio"</b> -- que neste projeto ja errou duas vezes no mesmo
 * mes. Se a guarda cair, a loja passa a creditar cashback errado em cada venda, e o relatorio a
 * calcular imposto errado, sem nada acender.</p>
 *
 * <p>{@code AuthContext} e a classe real, com o {@code SecurityContextHolder} montado:
 * {@code requirePapel} e varargs, e mock com {@code thenCallRealMethod} nao repassa a chamada -- isso
 * ja fez cinco testes deste projeto passarem verde medindo nada.</p>
 */
class OsNUMEROSdaCONFIGURACAOtemLIMITETest {

    private static final Long LOJA = 18L;

    private ConfiguracaoLojaRepository repo;
    private PlanoService planos;

    @BeforeEach
    void montar() {
        repo = mock(ConfiguracaoLojaRepository.class);
        planos = mock(PlanoService.class);
        when(planos.plano(anyLong())).thenReturn(Plano.UNICO);
        when(repo.findByLojaId(LOJA)).thenReturn(Optional.empty());
        when(repo.save(any(ConfiguracaoLoja.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void limparSessao() {
        SecurityContextHolder.clearContext();
    }

    private ConfiguracaoLojaService comoPapel(String papel) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new BoraPrincipal(7L, LOJA, papel, "eu@loja.com"), null, List.of()));
        // O LojaRepository entrou no construtor para o GET/PUT devolverem o nome REAL da loja
        // (ConfiguracaoLoja.nomeDaLoja), que e o que faz a rede de seguranca do painel funcionar
        // quando o lojista apaga o "nome de exibicao". Este teste e sobre os LIMITES dos numeros:
        // um duble vazio basta, e `findById` devolvendo Optional.empty() deixa o nome nulo.
        return new ConfiguracaoLojaService(repo, planos, new AuthContext(),
                org.mockito.Mockito.mock(br.com.bora.repository.LojaRepository.class));
    }

    private ConfiguracaoLoja comCashback(String pct) {
        ConfiguracaoLoja d = new ConfiguracaoLoja();
        d.cashbackPercentual = pct == null ? null : new BigDecimal(pct);
        return d;
    }

    private ConfiguracaoLoja comAliquota(String pct) {
        ConfiguracaoLoja d = new ConfiguracaoLoja();
        d.aliquotaImposto = pct == null ? null : new BigDecimal(pct);
        return d;
    }

    private static int status(ResponseStatusException e) {
        return e.getStatusCode().value();
    }

    // ============================================================ o cashback

    @Test
    void oCASHBACKacimaDE50porCENTOeRECUSADO() {
        ConfiguracaoLojaService s = comoPapel("ADMINISTRADOR_LOJA");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> s.atualizar(comCashback("51")));

        assertEquals(HttpStatus.BAD_REQUEST.value(), status(e));
        verify(repo, never()).save(any(ConfiguracaoLoja.class));
    }

    @Test
    void oERROdeDIGITACAOdeDOISzerosNAOviraPREJUIZO() {
        // O caso real que o teto existe para conter: digitar 500 em vez de 5,00.
        ConfiguracaoLojaService s = comoPapel("ADMINISTRADOR_LOJA");

        assertThrows(ResponseStatusException.class, () -> s.atualizar(comCashback("500")));

        verify(repo, never()).save(any(ConfiguracaoLoja.class));
    }

    @Test
    void oCASHBACKnegativoeRECUSADO() {
        ConfiguracaoLojaService s = comoPapel("ADMINISTRADOR_LOJA");

        assertThrows(ResponseStatusException.class, () -> s.atualizar(comCashback("-1")));

        verify(repo, never()).save(any(ConfiguracaoLoja.class));
    }

    @Test
    void os50porCENTOexatosPASSAM() {
        // Fronteira: o teto e inclusivo. Recusar 50 seria recusar o valor que a mensagem oferece.
        ConfiguracaoLojaService s = comoPapel("ADMINISTRADOR_LOJA");

        assertEquals(0, new BigDecimal("50").compareTo(s.atualizar(comCashback("50")).cashbackPercentual));
    }

    @Test
    void ZEROdesligaOcashback_eNAOehTRATADOcomoVAZIO() {
        // "Zero NAO e vazio" ja errou duas vezes neste projeto no mesmo mes. Zero aqui e a decisao do
        // lojista de DESLIGAR o cashback; ignorar o campo manteria o percentual antigo creditando.
        ConfiguracaoLojaService s = comoPapel("ADMINISTRADOR_LOJA");
        ConfiguracaoLoja existente = new ConfiguracaoLoja();
        existente.lojaId = LOJA;
        existente.cashbackPercentual = new BigDecimal("5");
        when(repo.findByLojaId(LOJA)).thenReturn(Optional.of(existente));

        ConfiguracaoLoja gravado = s.atualizar(comCashback("0"));

        assertEquals(0, BigDecimal.ZERO.compareTo(gravado.cashbackPercentual),
                "zero foi tratado como campo vazio: a loja continua creditando 5% depois de desligar");
    }

    @Test
    void oCAMPOomitidoNAOapagaOpercentualQUEaLOJAtinha() {
        // A outra ponta da mesma regra: null e "nao mandei este campo", e nao "zere".
        ConfiguracaoLojaService s = comoPapel("ADMINISTRADOR_LOJA");
        ConfiguracaoLoja existente = new ConfiguracaoLoja();
        existente.lojaId = LOJA;
        existente.cashbackPercentual = new BigDecimal("5");
        when(repo.findByLojaId(LOJA)).thenReturn(Optional.of(existente));

        ConfiguracaoLoja gravado = s.atualizar(comCashback(null));

        assertEquals(0, new BigDecimal("5").compareTo(gravado.cashbackPercentual),
                "salvar outra aba da tela apagou o cashback da loja");
    }

    // ============================================================ a aliquota e o custo fixo

    @Test
    void aALIQUOTAforaDEzeroACEMeRECUSADA() {
        ConfiguracaoLojaService s = comoPapel("ADMINISTRADOR_LOJA");

        assertEquals(HttpStatus.BAD_REQUEST.value(),
                status(assertThrows(ResponseStatusException.class, () -> s.atualizar(comAliquota("101")))));
        assertEquals(HttpStatus.BAD_REQUEST.value(),
                status(assertThrows(ResponseStatusException.class, () -> s.atualizar(comAliquota("-0.5")))));
        verify(repo, never()).save(any(ConfiguracaoLoja.class));
    }

    @Test
    void aALIQUOTAzeroEHlegitima_lojaISENTA() {
        ConfiguracaoLojaService s = comoPapel("ADMINISTRADOR_LOJA");

        ConfiguracaoLoja gravado = s.atualizar(comAliquota("0"));

        assertEquals(0, BigDecimal.ZERO.compareTo(gravado.aliquotaImposto),
                "loja isenta nao consegue gravar isencao, e o relatorio segue dizendo 'custos incompletos'");
    }

    @Test
    void oCUSTOfixoNEGATIVOeRECUSADO() {
        ConfiguracaoLojaService s = comoPapel("ADMINISTRADOR_LOJA");
        ConfiguracaoLoja d = new ConfiguracaoLoja();
        d.custoFixoMensal = new BigDecimal("-10");

        assertEquals(HttpStatus.BAD_REQUEST.value(),
                status(assertThrows(ResponseStatusException.class, () -> s.atualizar(d))));
        verify(repo, never()).save(any(ConfiguracaoLoja.class));
    }

    // ============================================================ quem pode mexer

    @Test
    void soOADMINdaLOJAmexeNOSnumerosDEdinheiro() {
        for (String papel : List.of("OPERADOR", "GERENTE")) {
            ConfiguracaoLojaService s = comoPapel(papel);

            assertThrows(ResponseStatusException.class, () -> s.atualizar(comCashback("5")),
                    papel + " conseguiu mudar o percentual de cashback da loja");
        }
        verify(repo, never()).save(any(ConfiguracaoLoja.class));
    }

    // ============================================================ o plano

    @Test
    void oPLANOqueNAOpermiteBANNERnaoGRAVAbanner() {
        // RN10: o que o plano nao libera e IGNORADO, nao recusado -- a tela manda o formulario inteiro.
        ConfiguracaoLojaService s = comoPapel("ADMINISTRADOR_LOJA");
        when(planos.plano(LOJA)).thenReturn(Plano.UNICO);
        ConfiguracaoLoja d = new ConfiguracaoLoja();
        d.bannerUrl = "https://exemplo.com/banner.jpg";
        d.nomeExibicao = "Zira Centro";

        ConfiguracaoLoja gravado = s.atualizar(d);

        assertEquals("Zira Centro", gravado.nomeExibicao, "o que o plano libera tem de ser gravado");
        if (!Plano.UNICO.permiteBanner()) {
            assertNull(gravado.bannerUrl, "gravou um campo que o plano da loja nao libera");
        }
    }

    @Test
    void aMARCAdoBORAficaLIGADAenquantoOplanoNAOpermiteREMOVER() {
        ConfiguracaoLojaService s = comoPapel("ADMINISTRADOR_LOJA");
        ConfiguracaoLoja d = new ConfiguracaoLoja();
        d.mostrarMarcaBora = false;   // a tela mandando que nao

        ConfiguracaoLoja gravado = s.atualizar(d);

        assertEquals(!Plano.UNICO.permiteRemoverMarca(), gravado.mostrarMarcaBora,
                "quem decide tirar a marca e o plano, nao o corpo da requisicao");
    }
}
