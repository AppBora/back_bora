package br.com.bora.service;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Limpa o checkout abandonado.
 *
 * <p>Quem montava o pedido no cardápio, escolhia PIX e fechava a página deixava um pedido vivo: ele
 * apitava na cozinha como qualquer outro, entrava no faturamento do dia e ficava lá para sempre,
 * porque nada no sistema expirava um pagamento que nunca chegou.</p>
 *
 * <p>Este serviço cancela o que passou do prazo, com motivo escrito, para o lojista entender o que
 * aconteceu. Só toca em pedido que está esperando PIX: nada de balcão, WhatsApp ou marketplace.</p>
 */
@Slf4j
@Service
public class CobradorDePixService {

    private final PedidoRepository pedidos;
    private final FidelidadeService fidelidade;
    private final LojaRepository lojas;
    private final IntegracaoCanalRepository integracoes;
    private final PixService pix;
    private final DevolucaoDeEstoqueService devolucao;
    /**
     * Transacao explicita. O metodo abaixo chamava {@code cancelarVencidos()} em si mesmo, e chamada
     * interna NAO passa pelo proxy do Spring: o {@code @Transactional} daquele metodo nunca valeu
     * aqui. Cancelar o pedido, devolver o cashback e devolver o estoque viravam tres gravacoes
     * soltas — se a ultima falhasse, o pedido ficava cancelado e o estoque nunca voltava, e nenhuma
     * rodada seguinte o pegava de novo.
     */
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final int minutosParaExpirar;

    public CobradorDePixService(PedidoRepository pedidos, FidelidadeService fidelidade,
                                LojaRepository lojas, IntegracaoCanalRepository integracoes, PixService pix,
                                DevolucaoDeEstoqueService devolucao,
                                org.springframework.transaction.PlatformTransactionManager gerenciadorDeTransacao,
                                @Value("${bora.pix.minutos-para-expirar:30}") int minutosParaExpirar) {
        this.pedidos = pedidos;
        this.fidelidade = fidelidade;
        this.lojas = lojas;
        this.integracoes = integracoes;
        this.pix = pix;
        this.devolucao = devolucao;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(gerenciadorDeTransacao);
        this.minutosParaExpirar = minutosParaExpirar;
    }

    /**
     * A cada 5 minutos. Cancela o pedido E a cobrança no Asaas.
     *
     * <p>O comentário antigo aqui dizia que "o QR do Asaas costuma valer bem mais que isso, então
     * ninguém paga tarde". Era o contrário: por valer mais, dava para pagar DEPOIS do cancelamento. O
     * dinheiro caía na conta do lojista e o pedido continuava cancelado — cliente esperando comida,
     * cozinha sem pedido nenhum, e ninguém avisado.</p>
     */
    @Scheduled(fixedDelayString = "${bora.pix.intervalo-ms:300000}")
    public void expirarAbandonados() {
        List<Pedido> cancelados = tx.execute(st -> cancelarVencidos());
        if (cancelados == null || cancelados.isEmpty()) return;
        // O Asaas é chamado FORA da transação: aqui a conexão do banco já voltou para o pool. Uma
        // lentidão deles não pode segurar conexão enquanto o cardápio inteiro espera.
        int fechadas = 0;
        for (Pedido p : cancelados) {
            if (pix.cancelarCobranca(lojas.findById(p.lojaId).orElse(null),
                    integracoes.findByLojaIdAndCanal(p.lojaId, "PIX").orElse(null), p.idExterno)) {
                fechadas++;
            }
        }
        log.info("PIX abandonado: {} pedido(s) cancelado(s) depois de {} min; {} cobranca(s) fechada(s) no Asaas",
                cancelados.size(), minutosParaExpirar, fechadas);
    }

    /** Parte que mexe no banco, curta e transacional. Devolve o que foi cancelado de fato. */
    @Transactional
    public List<Pedido> cancelarVencidos() {
        OffsetDateTime corte = OffsetDateTime.now().minusMinutes(minutosParaExpirar);
        List<Pedido> vencidos = pedidos.findByAguardandoPagamentoTrueAndCriadoEmBefore(corte);
        List<Pedido> cancelados = new java.util.ArrayList<>();
        for (Pedido p : vencidos) {
            // Pago entre a consulta e agora? O webhook já limpou a marca; não cancelamos venda paga.
            if (p.pagoEm != null) continue;
            if (p.status == StatusPedido.CANCELADO) { p.aguardandoPagamento = false; continue; }
            StatusPedido anterior = p.status;
            p.status = StatusPedido.CANCELADO;
            p.canceladoEm = OffsetDateTime.now();
            p.atualizadoEm = OffsetDateTime.now();
            p.motivoCancelamento = "PIX não pago em " + minutosParaExpirar + " minutos";
            p.aguardandoPagamento = false;
            pedidos.save(p);
            // O cashback foi debitado quando o pedido nasceu, para o mesmo saldo não valer em dois
            // pedidos ao mesmo tempo. Venda que não aconteceu devolve o saldo ao cliente.
            fidelidade.devolver(p.lojaId, p.clienteId, p.cashbackUsado);
            // E o estoque tambem. O pedido baixou produto ou insumo quando nasceu, e checkout
            // abandonado nunca foi para a cozinha: tudo o que ele consumiu volta.
            devolucao.devolver(p, anterior);
            cancelados.add(p);
        }
        return cancelados;
    }
}
