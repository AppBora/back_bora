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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
    private final EstornoDaVendaService devolucao;
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
                                EstornoDaVendaService devolucao,
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
        OffsetDateTime corte = OffsetDateTime.now().minusMinutes(minutosParaExpirar);
        List<Pedido> vencidos = tx.execute(st -> pedidos.findByAguardandoPagamentoTrueAndCriadoEmBefore(corte));
        if (vencidos == null || vencidos.isEmpty()) return;

        // PRIMEIRO pergunta ao Asaas quais foram pagos, e so depois cancela o resto.
        //
        // Antes daqui a rotina confiava so no `pagoEm`, que quem preenche e o webhook. Webhook falha:
        // o Asaas interrompe o envio depois de erros seguidos, o lojista apaga o webhook no painel
        // dele, e em 15/10/2026 havia loja em producao com a subconta viva e ZERO webhooks
        // registrados. Nesse estado, PIX pago virava pedido CANCELADO em 30 minutos -- com estoque e
        // cashback devolvidos, o cliente sem a comida e o dinheiro ja na conta do lojista.
        //
        // O Asaas e chamado FORA da transacao: a conexao do banco ja voltou para o pool, e uma
        // lentidao deles nao segura o cardapio inteiro.
        Set<Long> pagos = new LinkedHashSet<>();
        for (Pedido p : vencidos) {
            if (p.pagoEm != null || p.status == StatusPedido.CANCELADO) continue;
            if (pix.cobrancaFoiPaga(lojas.findById(p.lojaId).orElse(null),
                    integracoes.findByLojaIdAndCanal(p.lojaId, "PIX").orElse(null), p.idExterno)) {
                pagos.add(p.id);
            }
        }

        Integer confirmados = pagos.isEmpty() ? 0 : tx.execute(st -> confirmarOsQueForamPagos(pagos));
        List<Pedido> cancelados = tx.execute(st -> cancelarVencidos(corte, pagos));

        if (confirmados != null && confirmados > 0) {
            log.warn("PIX: {} pedido(s) estavam PAGOS no Asaas e o aviso nunca chegou. Confirmados aqui, "
                    + "e nao cancelados. Confira o webhook da loja na tela de Integracoes.", confirmados);
        }
        if (cancelados == null || cancelados.isEmpty()) return;
        // O Asaas tambem fica fora da transacao aqui, pelo mesmo motivo.
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

    /**
     * Pedido que o Asaas confirma como pago: entra como venda, nao como cancelamento.
     *
     * <p>Faz o mesmo que o webhook faria se tivesse chegado -- tira a marca de espera, carimba
     * {@code pagoEm} e credita o cashback uma unica vez. O pedido nao foi cancelado (quem chama aqui
     * garante isso), entao nao ha estoque nem cashback a devolver.</p>
     */
    @Transactional
    public int confirmarOsQueForamPagos(Set<Long> ids) {
        int n = 0;
        for (Long id : ids) {
            Pedido p = pedidos.findById(id).orElse(null);
            if (p == null || p.pagoEm != null) continue;      // o webhook pode ter chegado no meio
            if (p.status == StatusPedido.CANCELADO) continue; // nao e caso desta rotina
            p.formaPagamento = "PIX (pago)";
            p.aguardandoPagamento = false;
            p.pagoEm = OffsetDateTime.now();
            p.atualizadoEm = OffsetDateTime.now();
            pedidos.save(p);
            if (p.clienteId != null) {
                fidelidade.registrar(p.lojaId, p.clienteId, p.valorTotal, java.math.BigDecimal.ZERO);
            }
            n++;
        }
        return n;
    }

    /** Parte que mexe no banco, curta e transacional. Devolve o que foi cancelado de fato. */
    @Transactional
    public List<Pedido> cancelarVencidos(OffsetDateTime corte, Set<Long> naoCancelar) {
        List<Pedido> vencidos = pedidos.findByAguardandoPagamentoTrueAndCriadoEmBefore(corte);
        List<Pedido> cancelados = new java.util.ArrayList<>();
        for (Pedido p : vencidos) {
            // Pago entre a consulta e agora? O webhook (ou a confirmacao acima) ja limpou a marca;
            // nao cancelamos venda paga.
            if (p.pagoEm != null) continue;
            if (naoCancelar.contains(p.id)) continue;
            if (p.status == StatusPedido.CANCELADO) { p.aguardandoPagamento = false; continue; }
            StatusPedido anterior = p.status;
            p.status = StatusPedido.CANCELADO;
            p.canceladoEm = OffsetDateTime.now();
            p.atualizadoEm = OffsetDateTime.now();
            p.motivoCancelamento = "PIX nao pago em " + minutosParaExpirar + " minutos";
            p.aguardandoPagamento = false;
            pedidos.save(p);
            // O cashback foi debitado quando o pedido nasceu, para o mesmo saldo nao valer em dois
            // pedidos ao mesmo tempo. Venda que nao aconteceu devolve o saldo ao cliente -- e devolve o
            // estoque, porque o pedido baixou produto ou insumo quando nasceu e checkout abandonado
            // nunca foi para a cozinha. As duas coisas moram no EstornoDaVendaService.
            // false: este PIX nunca foi pago, entao a venda nao entrou no historico nem gerou cashback.
            devolucao.devolver(p, anterior, false);
            cancelados.add(p);
        }
        return cancelados;
    }
}
