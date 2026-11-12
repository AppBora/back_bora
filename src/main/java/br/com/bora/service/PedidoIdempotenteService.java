package br.com.bora.service;

import br.com.bora.dto.NovoPedidoRequest;
import br.com.bora.entity.Pedido;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.security.AuthContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * A mesma venda, gravada UMA vez — mesmo que o "Finalizar" chegue duas.
 *
 * <h2>O defeito, que e de hoje e nao precisa de internet ruim</h2>
 * <p>{@code POST /api/pedidos} nao tinha chave de idempotencia. Perder a VOLTA da resposta e o caso
 * comum — o pedido foi gravado, o navegador nao soube —, e ai o balconista clica "Finalizar" de novo.
 * A venda nascia duas vezes, com estrago triplo e todo em dinheiro: estoque (e insumo de ficha)
 * baixado duas vezes, cashback acumulado duas vezes, faturamento inflado. O pedido de marketplace ja
 * tinha essa trava desde a V28; o caminho mais usado do sistema ficou sem.</p>
 *
 * <h2>Por que esta classe existe separada do {@link PedidoService}</h2>
 * <p>Porque o {@code catch} do conflito tem de acontecer FORA da transacao que falhou. Quando o
 * indice unico recusa a segunda insercao, a transacao esta marcada para rollback e nenhuma leitura
 * dentro dela vale. Chamar {@code criar} de dentro do proprio {@code PedidoService} tambem nao
 * serviria: chamada no mesmo bean nao passa pelo proxy do Spring, e o {@code @Transactional} do
 * {@code criar} simplesmente nao valeria — cada {@code save} viraria a sua propria transacao, e uma
 * venda pela metade e pior que uma venda duplicada.</p>
 *
 * <h2>Quem garante a unicidade</h2>
 * <p>O indice {@code uq_pedido_loja_chave_idempotencia} (V71), e nao a consulta daqui. Dois cliques
 * quase juntos sao duas requisicoes em paralelo: as duas procuram antes de qualquer uma gravar, as
 * duas nao acham, as duas gravam. A consulta so poupa o trabalho no caso comum, o reenvio que chega
 * depois; o {@code catch} resolve a corrida. Mesma licao da V65.</p>
 *
 * <h2>Sem chave, nada muda</h2>
 * <p>Requisicao sem o cabecalho {@code Idempotency-Key} segue pelo caminho de sempre. E de
 * proposito: o cardapio publico, os webhooks e qualquer integracao de cliente antigo continuam
 * funcionando sem precisar subir junto com a API.</p>
 */
@Service
public class PedidoIdempotenteService {

    private static final org.slf4j.Logger AUDITORIA =
            org.slf4j.LoggerFactory.getLogger(PedidoIdempotenteService.class);

    /**
     * Teto do tamanho da chave. A coluna e {@code text} e aceitaria qualquer coisa; o teto esta aqui
     * para um cliente com defeito nao usar o pedido como deposito de texto. Um UUID tem 36.
     */
    static final int MAX_CHARS = 100;

    private final PedidoService pedidos;
    private final PedidoRepository repo;
    private final AuthContext ctx;

    public PedidoIdempotenteService(PedidoService pedidos, PedidoRepository repo, AuthContext ctx) {
        this.pedidos = pedidos;
        this.repo = repo;
        this.ctx = ctx;
    }

    /**
     * Grava o pedido, ou devolve o que esta chave ja gravou.
     *
     * <p><b>Sem transacao de proposito</b> — ver o javadoc da classe.</p>
     */
    public Pedido criar(NovoPedidoRequest req, String chaveCrua) {
        String chave = normalizar(chaveCrua);
        if (chave == null) return pedidos.criar(req, null);

        Long lojaId = ctx.lojaId();
        var jaGravado = repo.findByLojaIdAndChaveIdempotencia(lojaId, chave);
        if (jaGravado.isPresent()) {
            // Nao e erro, e o sistema funcionando: o reenvio recebe o MESMO pedido. Responder 409
            // deixaria o balconista achando que a venda nao entrou e lancando tudo de novo na mao.
            AUDITORIA.info("Pedido repetido barrado pela chave de idempotencia: loja {}, pedido {} "
                    + "devolvido de novo em vez de gravar uma segunda venda", lojaId, jaGravado.get().id);
            return jaGravado.get();
        }
        try {
            return pedidos.criar(req, chave);
        } catch (DataIntegrityViolationException e) {
            // A corrida: o outro clique gravou entre a consulta de cima e esta insercao. Quem perdeu
            // a corrida le o que o vencedor gravou. A leitura e nova (a transacao do `criar` ja
            // terminou em rollback), por isso este `catch` mora fora dela.
            var doOutroClique = repo.findByLojaIdAndChaveIdempotencia(lojaId, chave);
            if (doOutroClique.isEmpty()) {
                // O conflito era de OUTRO indice -- `uq_pedido_canal_externo`, telefone de cliente,
                // o que for. Engolir aqui esconderia um defeito de verdade.
                throw e;
            }
            AUDITORIA.warn("Dois 'Finalizar' ao mesmo tempo na loja {}: o indice unico recusou a "
                    + "segunda venda e o pedido {} foi devolvido para os dois", lojaId, doOutroClique.get().id);
            return doOutroClique.get();
        }
    }

    /**
     * A chave que vale, ou nulo quando nao ha chave utilizavel.
     *
     * <p>Corta nos {@value #MAX_CHARS} em vez de recusar: chave longa nao e motivo para perder uma
     * venda, e o corte e estavel — o reenvio corta no mesmo ponto e casa com o que foi gravado.</p>
     */
    static String normalizar(String crua) {
        if (crua == null) return null;
        String c = crua.trim();
        if (c.isEmpty()) return null;
        return c.length() > MAX_CHARS ? c.substring(0, MAX_CHARS) : c;
    }
}
