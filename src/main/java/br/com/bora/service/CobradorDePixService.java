package br.com.bora.service;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
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
    private final int minutosParaExpirar;

    public CobradorDePixService(PedidoRepository pedidos,
                                @Value("${bora.pix.minutos-para-expirar:30}") int minutosParaExpirar) {
        this.pedidos = pedidos;
        this.minutosParaExpirar = minutosParaExpirar;
    }

    /** A cada 5 minutos. O QR do Asaas costuma valer bem mais que isso, então ninguém paga tarde. */
    @Scheduled(fixedDelayString = "${bora.pix.intervalo-ms:300000}")
    @Transactional
    public void expirarAbandonados() {
        OffsetDateTime corte = OffsetDateTime.now().minusMinutes(minutosParaExpirar);
        List<Pedido> vencidos = pedidos.findByAguardandoPagamentoTrueAndCriadoEmBefore(corte);
        if (vencidos.isEmpty()) return;

        for (Pedido p : vencidos) {
            // Pago entre a consulta e agora? O webhook já limpou a marca; não cancelamos venda paga.
            if (p.pagoEm != null) continue;
            if (p.status == StatusPedido.CANCELADO) { p.aguardandoPagamento = false; continue; }
            p.status = StatusPedido.CANCELADO;
            p.canceladoEm = OffsetDateTime.now();
            p.atualizadoEm = OffsetDateTime.now();
            p.motivoCancelamento = "PIX não pago em " + minutosParaExpirar + " minutos";
            p.aguardandoPagamento = false;
            pedidos.save(p);
        }
        log.info("PIX abandonado: {} pedido(s) cancelado(s) depois de {} min", vencidos.size(), minutosParaExpirar);
    }
}
