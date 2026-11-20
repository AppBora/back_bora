package br.com.bora.controller;

import br.com.bora.dto.NovoPedidoRequest;
import br.com.bora.dto.PedidoCard;
import br.com.bora.dto.PedidoItemView;
import br.com.bora.entity.LogStatus;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import br.com.bora.service.PedidoIdempotenteService;
import br.com.bora.service.PedidoService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/pedidos")
public class PedidoController {

    private final PedidoService service;
    private final PedidoIdempotenteService umaVezSo;
    /** Quem tem a tabela de motoboys: traduz id/nome em cadastro (item 3 da secao 4). */
    private final br.com.bora.service.EntregadorService entregadores;

    public PedidoController(PedidoService service, PedidoIdempotenteService umaVezSo,
                            br.com.bora.service.EntregadorService entregadores) {
        this.service = service;
        this.umaVezSo = umaVezSo;
        this.entregadores = entregadores;
    }

    @GetMapping
    public List<Pedido> listar() {
        return service.listar();
    }

    /** Quadro de pedidos enriquecido (cliente, telefone, endereço, itens) para o kanban. */
    @GetMapping("/board")
    public List<PedidoCard> board(@RequestParam(required = false)
                                  @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                                  java.time.LocalDate dia,
                                  @RequestParam(required = false)
                                  @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                                  java.time.LocalDate desde) {
        return service.board(dia, desde);
    }

    /**
     * Lança a venda. Com {@code Idempotency-Key}, lança UMA vez por mais que o clique repita.
     *
     * <p>A chave vem em cabeçalho, e não no corpo, por dois motivos: é a convenção de HTTP para
     * isto, e assim o {@code NovoPedidoRequest} não muda -- cliente antigo que não conhece a chave
     * continua funcionando igual, sem precisar subir junto com a API. Ver
     * {@link PedidoIdempotenteService} para o que acontece quando ela repete.</p>
     */
    @PostMapping
    public Pedido criar(@RequestBody NovoPedidoRequest req,
                        @RequestHeader(name = "Idempotency-Key", required = false) String chave) {
        return umaVezSo.criar(req, chave);
    }

    @GetMapping("/{id}/itens")
    public List<PedidoItemView> itens(@PathVariable Long id) {
        return service.itens(id).stream().map(PedidoItemView::de).toList();
    }

    @PatchMapping("/{id}/status")
    public Pedido status(@PathVariable Long id, @RequestParam StatusPedido status,
                         @RequestParam(required = false) String motivo) {
        return service.alterarStatus(id, status, motivo);
    }

    /**
     * Escolhe ou troca o motoboy do pedido.
     *
     * <p>Aceita {@code entregadorId} (o que a tela manda desde 20/11/2026) ou {@code nome} (o que
     * ela mandava antes, e o que chamada antiga da API ainda manda). Ver
     * {@code EntregadorService.resolver}: id de outra loja e recusado, e nome que nao casa com um
     * unico cadastro grava texto sem id -- comportamento de antes da V80.</p>
     *
     * <p>Os dois em branco apagam o motoboy do pedido.</p>
     */
    @PatchMapping("/{id}/entregador")
    public Pedido entregador(@PathVariable Long id,
                             @RequestParam(required = false) String nome,
                             @RequestParam(required = false) Long entregadorId) {
        br.com.bora.entity.Entregador cadastro = entregadores.resolver(entregadorId, nome);
        // O NOME GRAVADO E O DO CADASTRO quando ele existe, nao o texto que veio na requisicao: senao
        // a tela poderia mandar id de um e nome de outro, e o retrato no pedido mentiria sobre quem
        // entregou -- o recibo do motoboy le esse texto.
        return cadastro != null
                ? service.definirEntregador(id, cadastro.id, cadastro.nome)
                : service.definirEntregador(id, null, nome);
    }

    @DeleteMapping("/{id}")
    public void excluir(@PathVariable Long id) {
        service.excluir(id);
    }

    @GetMapping("/{id}/historico")
    public List<LogStatus> historico(@PathVariable Long id) {
        return service.historico(id);
    }
}
