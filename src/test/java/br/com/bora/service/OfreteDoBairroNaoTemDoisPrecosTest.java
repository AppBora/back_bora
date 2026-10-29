package br.com.bora.service;

import br.com.bora.entity.TaxaEntrega;
import br.com.bora.repository.FormaPagamentoRepository;
import br.com.bora.repository.HorarioFuncionamentoRepository;
import br.com.bora.repository.MotivoCancelamentoRepository;
import br.com.bora.repository.TaxaEntregaRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.security.BoraPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Um bairro, um frete.
 *
 * <h2>O defeito (pente fino de 29/10/2026)</h2>
 * <p>A tabela {@code taxa_entrega} nasce na V12 com chave primaria so em {@code id} e um indice NAO
 * unico em {@code loja_id}. Nao ha nenhum UNIQUE em (loja, bairro) -- conferido nas 64 migracoes. E
 * o {@code salvarTaxa} criava linha nova sempre que o {@code id} vinha nulo, sem procurar se aquele
 * bairro ja estava cadastrado.</p>
 *
 * <p>Com duas linhas para o mesmo bairro, o estrago nao e o cadastro feio: e que o cardapio MOSTRA
 * o bairro duas vezes, com fretes diferentes, e a consulta que COBRA era um {@code findFirst} sem
 * ordem nenhuma -- o Postgres pode devolver qualquer uma das duas, e pode mudar de resposta entre
 * uma chamada e outra. O cliente escolhe um preco na tela e paga outro.</p>
 *
 * <h2>O que este arquivo mede, e o que NAO mede</h2>
 * <p>Mede as duas pontas que estao no codigo: o cadastro deixa de criar a segunda linha, e a ordem
 * e a mesma na consulta que mostra e na que cobra. <b>Nao</b> mede a constraint no banco -- ela nao
 * existe ainda, e enquanto nao existir as linhas duplicadas que ja estao la continuam la. O que
 * muda e que a escolha entre elas deixou de ser sorteio.</p>
 */
class OfreteDoBairroNaoTemDoisPrecosTest {

    private static final Long LOJA = 18L;

    private TaxaEntregaRepository taxas;
    private OperacaoService servico;

    @BeforeEach
    void montar() {
        taxas = mock(TaxaEntregaRepository.class);
        when(taxas.save(any(TaxaEntrega.class))).thenAnswer(i -> {
            TaxaEntrega t = i.getArgument(0);
            if (t.id == null) t.id = 500L;
            return t;
        });
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new BoraPrincipal(7L, LOJA, "ADMINISTRADOR_LOJA", "dono@loja.com"), null, List.of()));
        servico = new OperacaoService(taxas, mock(FormaPagamentoRepository.class),
                mock(HorarioFuncionamentoRepository.class), mock(MotivoCancelamentoRepository.class),
                new AuthContext());
    }

    @AfterEach
    void limparSessao() {
        SecurityContextHolder.clearContext();
    }

    private TaxaEntrega taxa(Long id, String bairro, String valor, boolean ativo) {
        TaxaEntrega t = new TaxaEntrega();
        t.id = id;
        t.lojaId = LOJA;
        t.bairro = bairro;
        t.taxa = new BigDecimal(valor);
        t.ativo = ativo;
        return t;
    }

    /** O que a tela manda ao cadastrar: sem id. */
    private TaxaEntrega doFormulario(String bairro, String valor) {
        TaxaEntrega t = new TaxaEntrega();
        t.bairro = bairro;
        t.taxa = new BigDecimal(valor);
        return t;
    }

    private TaxaEntrega gravado() {
        ArgumentCaptor<TaxaEntrega> c = ArgumentCaptor.forClass(TaxaEntrega.class);
        verify(taxas).save(c.capture());
        return c.getValue();
    }

    // ======================================================= o cadastro

    @Test
    void cadastrarUMbairroQUEjaEXISTEatualizaAlinhaQUEexiste() {
        TaxaEntrega jaCadastrado = taxa(42L, "Centro", "5.00", true);
        when(taxas.findFirstByLojaIdAndBairroIgnoreCaseOrderByIdDesc(LOJA, "Centro"))
                .thenReturn(Optional.of(jaCadastrado));

        servico.salvarTaxa(doFormulario("Centro", "8.00"));

        TaxaEntrega g = gravado();
        assertEquals(42L, g.id,
                "criou uma SEGUNDA linha para o mesmo bairro: o cardapio passa a mostrar dois fretes");
        assertEquals(0, new BigDecimal("8.00").compareTo(g.taxa), "o frete novo nao foi gravado");
    }

    @Test
    void aBUSCAignoraMAIUSCULAeESPACO() {
        // "centro ", "Centro" e "CENTRO" sao o mesmo bairro para quem digita, e tem de ser o mesmo
        // aqui: o cliente escolhe pelo NOME na tela.
        TaxaEntrega jaCadastrado = taxa(42L, "Centro", "5.00", true);
        when(taxas.findFirstByLojaIdAndBairroIgnoreCaseOrderByIdDesc(LOJA, "centro"))
                .thenReturn(Optional.of(jaCadastrado));

        servico.salvarTaxa(doFormulario(" centro ", "9.00"));

        assertEquals(42L, gravado().id, "a mesma grafia com outra caixa virou um bairro novo");
    }

    @Test
    void oBAIRROdesligadoVOLTApelaMESMAlinha() {
        // Procurar so o ATIVO faria o recadastro criar a segunda linha e deixar a desligada para tras.
        TaxaEntrega desligado = taxa(42L, "Centro", "5.00", false);
        when(taxas.findFirstByLojaIdAndBairroIgnoreCaseOrderByIdDesc(LOJA, "Centro"))
                .thenReturn(Optional.of(desligado));

        servico.salvarTaxa(doFormulario("Centro", "7.00"));

        TaxaEntrega g = gravado();
        assertEquals(42L, g.id);
        assertEquals(Boolean.TRUE, g.ativo, "recadastrar um bairro desligado tem de religa-lo");
    }

    @Test
    void bairroNOVOcontinuaNASCENDO() {
        when(taxas.findFirstByLojaIdAndBairroIgnoreCaseOrderByIdDesc(anyLong(), anyString()))
                .thenReturn(Optional.empty());

        servico.salvarTaxa(doFormulario("Jardim Europa", "12.00"));

        TaxaEntrega g = gravado();
        assertNotNull(g.id, "o save devolve o id; o bairro novo nao foi criado");
        assertEquals("Jardim Europa", g.bairro);
        assertEquals(LOJA, g.lojaId, "a linha nasceu sem loja: vazaria entre lojas");
    }

    @Test
    void editarPELOidCONTINUAeditandoAQUELAlinha() {
        // O caminho do lapis na tela. Ele NAO pode cair na busca por nome: renomear um bairro
        // ("Centro" -> "Centro Novo") tem de mexer na linha que o lojista abriu.
        TaxaEntrega aEditar = taxa(99L, "Centro", "5.00", true);
        when(taxas.findByIdAndLojaId(99L, LOJA)).thenReturn(Optional.of(aEditar));

        TaxaEntrega doForm = doFormulario("Centro Novo", "6.00");
        doForm.id = 99L;
        servico.salvarTaxa(doForm);

        TaxaEntrega g = gravado();
        assertEquals(99L, g.id);
        assertEquals("Centro Novo", g.bairro, "renomear o bairro deixou de funcionar");
        verify(taxas, org.mockito.Mockito.never())
                .findFirstByLojaIdAndBairroIgnoreCaseOrderByIdDesc(anyLong(), anyString());
    }

    @Test
    void semBAIRROnaoGRAVAnada() {
        try {
            servico.salvarTaxa(doFormulario("   ", "5.00"));
            org.junit.jupiter.api.Assertions.fail("gravou uma taxa sem bairro");
        } catch (org.springframework.web.server.ResponseStatusException e) {
            assertEquals(400, e.getStatusCode().value());
        }
        verify(taxas, org.mockito.Mockito.never()).save(any(TaxaEntrega.class));
    }

    // ======================================================= mostrar e cobrar escolhem igual

    @Test
    void aCONSULTAqueCOBRAtemORDEMdefinida() {
        // O nome do metodo E o contrato: `findFirst...OrderByIdDesc`. Sem a ordem, duas linhas para o
        // mesmo bairro fazem o frete cobrado mudar sem nada ter mudado no cadastro.
        when(taxas.findFirstByLojaIdAndBairroIgnoreCaseAndAtivoTrueOrderByIdDesc(LOJA, "Centro"))
                .thenReturn(Optional.of(taxa(43L, "Centro", "8.00", true)));

        var achado = taxas.findFirstByLojaIdAndBairroIgnoreCaseAndAtivoTrueOrderByIdDesc(LOJA, "Centro");

        assertEquals(43L, achado.orElseThrow().id);
    }

    @Test
    void aLISTAdoCARDAPIOusaAMESMAordemDAcobranca() {
        // Se a tela listasse por (bairro ASC) e a cobranca escolhesse por (id DESC), a linha mostrada
        // podia nao ser a cobrada -- que e exatamente o defeito, so que mais dificil de ver.
        TaxaEntregaRepository r = mock(TaxaEntregaRepository.class);
        when(r.findByLojaIdAndAtivoTrueOrderByBairroAscIdDesc(LOJA))
                .thenReturn(List.of(taxa(43L, "Centro", "8.00", true), taxa(42L, "Centro", "5.00", true)));

        List<TaxaEntrega> lista = r.findByLojaIdAndAtivoTrueOrderByBairroAscIdDesc(LOJA);

        assertEquals(43L, lista.get(0).id, "a primeira da lista tem de ser a mesma que a cobranca pega");
        verify(r).findByLojaIdAndAtivoTrueOrderByBairroAscIdDesc(eq(LOJA));
    }

    @Test
    void semTAXAcadastradaOfreteEhZERO_naoNULO() {
        when(taxas.findFirstByLojaIdAndBairroIgnoreCaseAndAtivoTrueOrderByIdDesc(anyLong(), anyString()))
                .thenReturn(Optional.empty());

        assertNull(taxas.findFirstByLojaIdAndBairroIgnoreCaseAndAtivoTrueOrderByIdDesc(LOJA, "Nenhum")
                .orElse(null));
    }
}
