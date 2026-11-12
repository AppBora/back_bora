package br.com.bora.service;

import br.com.bora.dto.ItemPedidoRequest;
import br.com.bora.dto.NovoPedidoRequest;
import br.com.bora.entity.Pedido;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A mesma venda, gravada UMA vez — mesmo que o "Finalizar" chegue duas.
 *
 * <h2>O defeito, que e de hoje e nao precisa de internet ruim</h2>
 * <p>{@code POST /api/pedidos} nao tinha chave de idempotencia. Perder a VOLTA da resposta e o caso
 * comum — o pedido foi gravado, o navegador nao soube —, e ai o balconista clica "Finalizar" de
 * novo. A venda nascia duas vezes: estoque (e insumo de ficha) baixado duas vezes, cashback
 * acumulado duas vezes, faturamento inflado. O pedido de marketplace tinha essa trava desde a V28;
 * o caminho mais usado do sistema ficou sem.</p>
 *
 * <h2>O que cada teste mede, e por que o {@code verify(pedidos, never())} e o que importa</h2>
 * <p>Nao basta a resposta sair certa: o que estraga dinheiro e o SEGUNDO {@code criar} rodando —
 * ele baixa estoque, consome insumo e credita cashback antes de qualquer resposta. Por isso os
 * testes olham se o {@link PedidoService} foi chamado, e nao so o que voltou.</p>
 */
class AmesmaVendaNaoPodeSerGravadaDuasVezesTest {

    private static final Long LOJA = 7L;
    private static final String CHAVE = "11111111-2222-3333-4444-555555555555";

    private PedidoService pedidos;
    private PedidoRepository repo;
    private PedidoIdempotenteService umaVezSo;

    private NovoPedidoRequest venda() {
        return new NovoPedidoRequest(null, "#001", "Dinheiro", "Balcao", null, false,
                List.of(new ItemPedidoRequest(10L, 1, null)), null, null, null, null);
    }

    private static Pedido pedido(long id) {
        Pedido p = new Pedido();
        p.id = id;
        p.lojaId = LOJA;
        p.chaveIdempotencia = CHAVE;
        return p;
    }

    @BeforeEach
    void montar() {
        pedidos = mock(PedidoService.class);
        repo = mock(PedidoRepository.class);
        AuthContext ctx = mock(AuthContext.class);
        when(ctx.lojaId()).thenReturn(LOJA);
        umaVezSo = new PedidoIdempotenteService(pedidos, repo, ctx);
    }

    // ------------------------------------------------------------------ o defeito

    @Test
    void oSEGUNDOcliqueDEVOLVEoMESMOpedidoEnaoGRAVAoutraVENDA() {
        when(repo.findByLojaIdAndChaveIdempotencia(LOJA, CHAVE)).thenReturn(Optional.of(pedido(500L)));

        Pedido resposta = umaVezSo.criar(venda(), CHAVE);

        assertEquals(500L, resposta.id, "o reenvio tem de receber o pedido que JA existe");
        verify(pedidos, never()).criar(any(), any());
        verify(pedidos, never()).criar(any());
    }

    @Test
    void doisFINALIZARaoMESMOtempoENTREGAMumPEDIDOso() {
        // A corrida: as duas chamadas procuram antes de qualquer uma gravar, as duas nao acham. Quem
        // perde leva a recusa do indice unico -- e tem de ler o que o vencedor gravou, nao estourar
        // na cara do balconista.
        when(repo.findByLojaIdAndChaveIdempotencia(LOJA, CHAVE))
                .thenReturn(Optional.empty())           // antes de tentar
                .thenReturn(Optional.of(pedido(501L))); // depois da recusa do indice
        when(pedidos.criar(any(), eq(CHAVE)))
                .thenThrow(new DataIntegrityViolationException("uq_pedido_loja_chave_idempotencia"));

        Pedido resposta = umaVezSo.criar(venda(), CHAVE);

        assertEquals(501L, resposta.id, "quem perdeu a corrida le a venda do vencedor");
    }

    @Test
    void conflitoDeOUTROindiceNAOpodeSERengolido() {
        // Engolir aqui transformaria um defeito de verdade (telefone repetido, id externo repetido)
        // em "deu tudo certo" com uma resposta vazia.
        when(repo.findByLojaIdAndChaveIdempotencia(LOJA, CHAVE)).thenReturn(Optional.empty());
        when(pedidos.criar(any(), eq(CHAVE)))
                .thenThrow(new DataIntegrityViolationException("uq_cliente_loja_telefone"));

        assertThrows(DataIntegrityViolationException.class, () -> umaVezSo.criar(venda(), CHAVE));
    }

    // ------------------------------------------------------------------ controle positivo

    @Test
    void aPRIMEIRAvezGRAVAnormalmente() {
        when(repo.findByLojaIdAndChaveIdempotencia(LOJA, CHAVE)).thenReturn(Optional.empty());
        when(pedidos.criar(any(), eq(CHAVE))).thenReturn(pedido(502L));

        assertEquals(502L, umaVezSo.criar(venda(), CHAVE).id);
        verify(pedidos).criar(any(), eq(CHAVE));
    }

    @Test
    void chavesDIFERENTESsaoVENDASdiferentes() {
        when(repo.findByLojaIdAndChaveIdempotencia(eq(LOJA), anyString())).thenReturn(Optional.empty());
        when(pedidos.criar(any(), eq("chave-A"))).thenReturn(pedido(601L));
        when(pedidos.criar(any(), eq("chave-B"))).thenReturn(pedido(602L));

        assertEquals(601L, umaVezSo.criar(venda(), "chave-A").id);
        assertEquals(602L, umaVezSo.criar(venda(), "chave-B").id, "dois clientes pedindo o mesmo "
                + "acai no mesmo minuto sao DUAS vendas -- a trava nao pode confundir isso");
    }

    // ------------------------------------------------------------------ sem chave, nada muda

    @Test
    void semCHAVEoCAMINHOeOdeSEMPRE() {
        when(pedidos.criar(any(), isNull())).thenReturn(pedido(700L));

        assertEquals(700L, umaVezSo.criar(venda(), null).id);
        assertEquals(700L, umaVezSo.criar(venda(), "   ").id, "cabecalho vazio e o mesmo que nao mandar");

        // Nem consulta ao banco: cliente antigo (cardapio publico, webhook, integracao de terceiro)
        // nao paga nada por uma trava que nao pediu.
        verify(repo, never()).findByLojaIdAndChaveIdempotencia(any(), any());
    }

    @Test
    void chaveABSURDAmenteLONGAeCORTADAnoMESMOponto() {
        String longa = "x".repeat(500);
        String cortada = "x".repeat(PedidoIdempotenteService.MAX_CHARS);
        when(repo.findByLojaIdAndChaveIdempotencia(LOJA, cortada)).thenReturn(Optional.of(pedido(800L)));

        assertEquals(800L, umaVezSo.criar(venda(), longa).id,
                "o corte tem de ser estavel: o reenvio corta no mesmo ponto e casa com o gravado");
    }

    // ------------------------------------------------------------------ teste do teste

    @Test
    void oPedidoServiceDEmentiraESTAsendoCHAMADO() {
        // Sem isto, um erro de montagem faria os testes de cima passarem sem nunca exercitar o
        // caminho de gravar. Ja aconteceu neste projeto: cinco testes medindo nada por causa de um
        // mock mal ligado.
        when(repo.findByLojaIdAndChaveIdempotencia(LOJA, CHAVE)).thenReturn(Optional.empty());
        when(pedidos.criar(any(), eq(CHAVE))).thenReturn(pedido(900L));

        umaVezSo.criar(venda(), CHAVE);

        verify(pedidos, times(1)).criar(any(), eq(CHAVE));
        verify(repo, times(1)).findByLojaIdAndChaveIdempotencia(LOJA, CHAVE);
    }
}
