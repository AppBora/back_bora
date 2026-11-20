package br.com.bora.service;

import br.com.bora.entity.Promocao;
import br.com.bora.repository.CupomRepository;
import br.com.bora.repository.PromocaoRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Duas promocoes de mesmo codigo NAO podem derrubar o cupom do cardapio publico.
 *
 * <h2>O defeito, medido em producao</h2>
 * <p>{@code DescontoService.resolver} buscava a promocao por um metodo que devolvia {@code Optional}.
 * Com duas linhas de mesmo codigo na mesma loja, o Spring Data lanca
 * {@code IncorrectResultSizeDataAccessException} e o endpoint publico responde <b>500</b>.</p>
 *
 * <p>No dump de producao de 19/11/2026, a <b>loja 18 tinha 4 linhas</b> de {@code BORA15L18} — todas
 * {@code ativa = false}, {@code usos = 0}. O cupom daquela loja (cliente pagante) estava quebrado.</p>
 *
 * <h2>Por que este teste existe mesmo com o indice unico</h2>
 * <p>A V77 renomeia as duplicadas e cria {@code uq_promocao_loja_codigo}, entao o caso nao deve mais
 * nascer. <b>Mas o caminho publico nao pode depender disso.</b> Uma linha a mais no banco — por
 * importacao, por script, por uma migracao futura que esqueca o indice — voltaria a derrubar o
 * checkout. Aqui eu provo que o servico <b>escolhe pela regra</b> em vez de estourar.</p>
 *
 * <p>E por isso este teste e unitario e nao de integracao: <b>com o indice no lugar nao da mais para
 * criar a duplicata no banco</b>. A parte que vive no banco esta em
 * {@code OcodigoDePromocaoEhUNICOporLOJAIT}.</p>
 */
class ODescontoNAOestouraComCODIGOduplicadoTest {

    private static final Long LOJA = 18L;

    private final CupomRepository cupons = mock(CupomRepository.class);
    private final PromocaoRepository promocoes = mock(PromocaoRepository.class);
    private final DescontoService svc = new DescontoService(cupons, promocoes, gerenciadorFalso());

    private static org.springframework.transaction.PlatformTransactionManager gerenciadorFalso() {
        var g = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(g.getTransaction(any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        return g;
    }

    /**
     * Uma promocao como as da loja 18.
     *
     * @param valendo quando false, a janela ja passou — e a promocao nao vale mais
     */
    private Promocao uma(long id, boolean valendo, String percentual) {
        Promocao p = new Promocao();
        p.setId(id);
        p.setLojaId(LOJA);
        p.setTipo("RELAMPAGO");
        p.setCodigo("BORA15L18");
        p.setPercentualDesconto(new BigDecimal(percentual));
        p.setLimiteUsos(20);
        p.setUsos(0);
        p.setAtiva(valendo);
        if (valendo) {
            p.setInicio(OffsetDateTime.now().minusMinutes(10));
            p.setFim(OffsetDateTime.now().plusHours(3));
        } else {
            p.setInicio(OffsetDateTime.now().minusDays(3));
            p.setFim(OffsetDateTime.now().minusDays(3).plusHours(3));
        }
        return p;
    }

    private void oBancoDevolve(Promocao... linhas) {
        when(promocoes.findByLojaIdAndCodigoIgnoreCaseOrderByInicioDesc(LOJA, "BORA15L18"))
                .thenReturn(List.of(linhas));
        when(cupons.findByLojaIdAndCodigoIgnoreCase(anyLong(), any())).thenReturn(Optional.empty());
    }

    // ----------------------------------------------------------------- a premissa

    /**
     * A PREMISSA: com UMA linha valendo, o desconto sai. Sem isto, um teste que passasse porque
     * {@code resolver} nunca devolve nada pareceria prova de robustez.
     */
    @Test
    void aPREMISSAcomUMAlinhaVALENDOoDESCONTOsai() {
        oBancoDevolve(uma(1, true, "15.00"));

        var d = svc.resolver(LOJA, "BORA15L18", new BigDecimal("100.00"));

        assertTrue(d.isPresent(), "uma promocao valendo tinha de dar desconto");
        assertEquals(0, d.get().valorAbatido().compareTo(new BigDecimal("15.00")));
    }

    // ----------------------------------------------------------------- o que importa

    /** AS QUATRO LINHAS DA LOJA 18, nenhuma valendo: resposta vazia, e NENHUMA excecao. */
    @Test
    void asQUATROlinhasDaLOJA18naoDERRUBAMoCHECKOUT() {
        oBancoDevolve(uma(6, false, "15.00"), uma(5, false, "15.00"),
                      uma(4, false, "15.00"), uma(3, false, "15.00"));

        var d = assertDoesNotThrow(
                () -> svc.resolver(LOJA, "BORA15L18", new BigDecimal("100.00")),
                "quatro linhas de mesmo codigo estouraram o checkout — era exatamente o 500 da loja 18");

        assertTrue(d.isEmpty(), "nenhuma das quatro estava valendo: nao ha desconto, e nao ha erro");
    }

    /**
     * E o caso que importa de verdade: entre duplicadas, a que VALE e escolhida.
     *
     * <p>Com a ordenacao por {@code inicio} decrescente, a primeira da lista e a mais recente. Mas o
     * que decide e a regra {@code valeAgora}, nao a posicao: aqui a que vale esta em <b>terceiro</b>
     * lugar, de proposito. Se alguem trocar o filtro por um {@code findFirst()} cru, este teste cai.</p>
     */
    @Test
    void entreDUPLICADASaQUEvaleEHaESCOLHIDA() {
        oBancoDevolve(uma(6, false, "15.00"), uma(5, false, "15.00"),
                      uma(4, true, "25.00"), uma(3, false, "15.00"));

        var d = svc.resolver(LOJA, "BORA15L18", new BigDecimal("100.00"));

        assertTrue(d.isPresent(), "havia uma promocao valendo na lista e ela foi ignorada");
        assertEquals(0, d.get().valorAbatido().compareTo(new BigDecimal("25.00")),
                "escolheu a linha errada: o abatimento tinha de ser o da promocao que VALE (25%), "
                        + "nao o da primeira da lista");
        assertEquals(4L, d.get().promocaoId().longValue(),
                "o id devolvido tem de ser o da promocao escolhida — e ele e quem recebe o uso");
    }

    /** O CONTROLE: lista vazia continua sendo "sem desconto", sem erro. */
    @Test
    void listaVAZIAcontinuaSENDOsemDESCONTO() {
        oBancoDevolve();

        assertTrue(svc.resolver(LOJA, "BORA15L18", new BigDecimal("100.00")).isEmpty());
    }
}
