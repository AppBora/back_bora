package br.com.bora.service;

import br.com.bora.dto.AcertoLinha;
import br.com.bora.dto.FazerAcertoRequest;
import br.com.bora.entity.AcertoEntregador;
import br.com.bora.entity.Pedido;
import br.com.bora.repository.AcertoEntregadorRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Acerto de entregadores: soma as taxas/valores das entregas realizadas e registra o pagamento (acerto). */
@Service
public class AcertoEntregadorService {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private final PedidoRepository pedidos;
    private final AcertoEntregadorRepository acertos;
    private final AuthContext ctx;

    public AcertoEntregadorService(PedidoRepository pedidos, AcertoEntregadorRepository acertos, AuthContext ctx) {
        this.pedidos = pedidos;
        this.acertos = acertos;
        this.ctx = ctx;
    }

    /** Prévia por entregador (entregas realizadas ainda não acertadas no período). */
    public List<AcertoLinha> previa(LocalDate inicio, LocalDate fim) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        validarPeriodo(inicio, fim);
        OffsetDateTime ini = inicio.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime fimEx = fim.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();

        List<AcertoLinha> linhas = new ArrayList<>();
        for (Object[] r : pedidos.previaAcerto(ctx.lojaId(), ini, fimEx)) {
            String entregador = (String) r[0];
            long qtd = ((Number) r[1]).longValue();
            BigDecimal taxas = big(r[2]);
            BigDecimal dinheiro = big(r[3]);
            BigDecimal total = big(r[4]);
            BigDecimal outras = total.subtract(dinheiro);
            linhas.add(new AcertoLinha(entregador, qtd, taxas, dinheiro, outras, total, taxas));
        }
        return linhas;
    }

    /** Fecha o acerto de um entregador: registra o pagamento e marca as entregas como acertadas. */
    @Transactional
    public AcertoEntregador fazer(FazerAcertoRequest req) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        if (req.entregador() == null || req.entregador().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Entregador é obrigatório");
        }
        validarPeriodo(req.inicio(), req.fim());
        Long lojaId = ctx.lojaId();
        String entregador = req.entregador().trim();
        OffsetDateTime ini = req.inicio().atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime fimEx = req.fim().plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();

        List<Pedido> peds = pedidos.entregasParaAcerto(lojaId, entregador, ini, fimEx);
        if (peds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nenhuma entrega em aberto para " + entregador + " neste período");
        }

        long qtd = peds.size();
        BigDecimal taxas = BigDecimal.ZERO, dinheiro = BigDecimal.ZERO, total = BigDecimal.ZERO;
        for (Pedido p : peds) {
            taxas = taxas.add(p.taxaEntrega == null ? BigDecimal.ZERO : p.taxaEntrega);
            BigDecimal v = p.valorTotal == null ? BigDecimal.ZERO : p.valorTotal;
            total = total.add(v);
            if (p.formaPagamento != null && p.formaPagamento.toUpperCase().contains("DINHEIRO")) {
                dinheiro = dinheiro.add(v);
            }
        }
        BigDecimal outras = total.subtract(dinheiro);
        BigDecimal aPagar = taxas;
        BigDecimal pago = req.valorPago() == null ? aPagar : req.valorPago();
        BigDecimal descontos = req.descontos() == null ? BigDecimal.ZERO : req.descontos();
        BigDecimal saldo = aPagar.subtract(pago).subtract(descontos);

        AcertoEntregador a = new AcertoEntregador();
        a.lojaId = lojaId;
        a.entregador = entregador;
        a.periodoInicio = req.inicio();
        a.periodoFim = req.fim();
        a.qtdeEntregas = (int) qtd;
        a.valorTaxas = taxas;
        a.valorDinheiro = dinheiro;
        a.valorOutras = outras;
        a.valorTotal = total;
        a.valorAPagar = aPagar;
        a.valorPago = pago;
        a.descontos = descontos;
        a.saldo = saldo;
        a.observacao = req.observacao();
        a.criadoEm = OffsetDateTime.now();
        a.criadoPor = ctx.atual().userId();
        AcertoEntregador salvo = acertos.save(a);

        peds.forEach(p -> p.acertoId = salvo.id); // trava as entregas nesse acerto (não entram de novo)
        pedidos.saveAll(peds);
        return salvo;
    }

    /** Histórico de acertos já realizados na loja. */
    public List<AcertoEntregador> historico() {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        return acertos.findByLojaIdOrderByCriadoEmDesc(ctx.lojaId());
    }

    private void validarPeriodo(LocalDate inicio, LocalDate fim) {
        if (inicio == null || fim == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe início e fim do período");
        }
        if (fim.isBefore(inicio)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A data fim não pode ser anterior ao início");
        }
    }

    private static BigDecimal big(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        return new BigDecimal(o.toString());
    }
}
