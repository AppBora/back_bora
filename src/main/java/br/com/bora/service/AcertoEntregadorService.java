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
    /** Para a previa mostrar o nome ATUAL do motoboy, e nao o que estava gravado no pedido. */
    private final br.com.bora.repository.EntregadorRepository entregadores;

    public AcertoEntregadorService(PedidoRepository pedidos, AcertoEntregadorRepository acertos,
                                   AuthContext ctx,
                                   br.com.bora.repository.EntregadorRepository entregadores) {
        this.pedidos = pedidos;
        this.acertos = acertos;
        this.ctx = ctx;
        this.entregadores = entregadores;
    }

    /**
     * O nome da linha das entregas sem motoboy escolhido (item 6.4).
     *
     * <p>E texto, nao entregador: nenhuma entrega tem este nome, entao tentar fechar o acerto dela e
     * recusado pelo proprio {@code fazer}, sem precisar de guarda nova.</p>
     */
    public static final String SEM_MOTOBOY = "(entregas sem motoboy escolhido)";

    /** Prévia por entregador (entregas realizadas ainda não acertadas no período). */
    public List<AcertoLinha> previa(LocalDate inicio, LocalDate fim) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        validarPeriodo(inicio, fim);
        OffsetDateTime ini = inicio.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime fimEx = fim.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();

        List<AcertoLinha> linhas = new ArrayList<>();

        // 1. POR CADASTRO (item 3 da secao 4). Renomear o motoboy no meio do periodo dividia as
        // corridas dele em duas linhas: as de antes com o nome velho, as de depois com o novo. Quem
        // fechava o acerto pagava uma e nao a outra, e a que ficava para tras nao aparecia como
        // pendencia -- ela virava "outro entregador". Agrupado por id, trocar o nome nao mexe na conta.
        //
        // O NOME EXIBIDO E O DO CADASTRO, nao o gravado no pedido: o do pedido e o retrato do dia da
        // entrega e pode estar velho -- e e justamente o nome velho que fazia o lojista achar que
        // eram duas pessoas. Se o cadastro tiver sido apagado, cai no nome gravado (a FK da V80 e
        // ON DELETE SET NULL, mas uma previa aberta em outra aba pode ter o id em maos).
        for (Object[] r : pedidos.previaAcertoPorCadastro(ctx.lojaId(), ini, fimEx)) {
            Long entregadorId = ((Number) r[0]).longValue();
            String nomeNoPedido = (String) r[1];
            long qtd = ((Number) r[2]).longValue();
            BigDecimal taxas = big(r[3]);
            BigDecimal dinheiro = big(r[4]);
            BigDecimal total = big(r[5]);
            String nome = entregadores.findByIdAndLojaId(entregadorId, ctx.lojaId())
                    .map(e -> e.nome).filter(x -> x != null && !x.isBlank()).orElse(nomeNoPedido);
            linhas.add(new AcertoLinha(nome, qtd, taxas, dinheiro, total.subtract(dinheiro), total,
                    taxas, entregadorId));
        }

        // 2. O LEGADO, por TEXTO: entrega gravada antes da V80, ou nome que nao casou com um unico
        // cadastro. A consulta exclui quem tem cadastro, senao o mesmo dinheiro sairia nas duas
        // linhas e fechar as duas pagaria o motoboy em dobro.
        for (Object[] r : pedidos.previaAcerto(ctx.lojaId(), ini, fimEx)) {
            String entregador = (String) r[0];
            long qtd = ((Number) r[1]).longValue();
            BigDecimal taxas = big(r[2]);
            BigDecimal dinheiro = big(r[3]);
            BigDecimal total = big(r[4]);
            BigDecimal outras = total.subtract(dinheiro);
            linhas.add(new AcertoLinha(entregador, qtd, taxas, dinheiro, outras, total, taxas, null));
        }

        // As duas listas vem ordenadas por nome cada uma; juntas, nao. Ordena aqui para a tela nao
        // mostrar o motoboy com cadastro em cima e o legado embaixo, sem explicacao.
        linhas.sort(java.util.Comparator.comparing(
                l -> l.entregador() == null ? "" : l.entregador().toLowerCase()));

        // Item 6.4: entrega concluida SEM motoboy escolhido nao cabe em nenhuma linha por entregador,
        // e por isso desaparecia de todo acerto. Entra como uma linha propria, com o nome que diz o
        // que e: quem faz o acerto e quem precisa saber que esse dinheiro esta fora da conta.
        //
        // Fechar essa linha e naturalmente recusado: o `fazer` procura as entregas por
        // `lower(trim(entregador))`, e nenhuma entrega tem este texto como nome.
        for (Object[] r : pedidos.entregasSemMotoboy(ctx.lojaId(), ini, fimEx)) {
            long qtd = ((Number) r[0]).longValue();
            if (qtd == 0) continue;
            BigDecimal taxas = big(r[1]);
            BigDecimal total = big(r[2]);
            // Sem cadastro, por definicao: e a linha das entregas que terminaram sem ninguem
            // escolhido. Fica por ULTIMO na tela, depois da ordenacao acima.
            linhas.add(new AcertoLinha(SEM_MOTOBOY, qtd, taxas, BigDecimal.ZERO, total, total,
                    taxas, null));
        }
        return linhas;
    }

    /** Fecha o acerto de um entregador: registra o pagamento e marca as entregas como acertadas. */
    @Transactional
    public AcertoEntregador fazer(FazerAcertoRequest req) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        boolean porCadastro = req.entregadorId() != null;
        if (!porCadastro && (req.entregador() == null || req.entregador().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Entregador é obrigatório");
        }
        validarPeriodo(req.inicio(), req.fim());
        Long lojaId = ctx.lojaId();
        OffsetDateTime ini = req.inicio().atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime fimEx = req.fim().plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();

        // QUEM MANDA E O ID, QUANDO ELE VEM. (item 3 da secao 4)
        //
        // O cadastro e lido da LOJA de quem esta logado: id de outra loja vira 404 aqui, nao uma
        // soma de entregas de outra pessoa. E o nome gravado no acerto passa a ser o do cadastro --
        // o recibo impresso tem de dizer o nome de agora, nao o texto que a tela mandou junto.
        br.com.bora.entity.Entregador cadastro = null;
        if (porCadastro) {
            cadastro = entregadores.findByIdAndLojaId(req.entregadorId(), lojaId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Entregador não encontrado nesta loja"));
        }
        String entregador = cadastro != null && cadastro.nome != null && !cadastro.nome.isBlank()
                ? cadastro.nome.trim()
                : req.entregador() == null ? "" : req.entregador().trim();

        List<Pedido> peds = porCadastro
                ? pedidos.entregasParaAcertoPorCadastro(lojaId, req.entregadorId(), ini, fimEx)
                : pedidos.entregasParaAcerto(lojaId, entregador, ini, fimEx);
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
        // Isto aqui registra pagamento de gente de verdade e nao tinha validacao nenhuma: valor
        // negativo passava, e desconto maior que a divida tambem.
        if (pago.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O valor pago não pode ser negativo");
        }
        if (descontos.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O desconto não pode ser negativo");
        }
        if (descontos.compareTo(aPagar) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O desconto (R$ " + descontos + ") é maior que o valor devido ao entregador (R$ " + aPagar + ")");
        }
        BigDecimal saldo = aPagar.subtract(pago).subtract(descontos);

        AcertoEntregador a = new AcertoEntregador();
        a.lojaId = lojaId;
        a.entregador = entregador;
        a.entregadorId = req.entregadorId();
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

        // Trava as entregas NESTE acerto, e so as que ainda estavam livres. Se outra pessoa fechou o
        // acerto deste entregador entre a nossa leitura e agora, o banco devolve menos linhas e a
        // transacao inteira volta atras — melhor um erro na tela do que pagar duas vezes a mesma corrida.
        int amarradas = pedidos.amarrarAoAcerto(salvo.id, peds.stream().map(p -> p.id).toList());
        if (amarradas != peds.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Outro acerto de " + entregador + " foi fechado agora mesmo. Confira o histórico e refaça.");
        }
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
