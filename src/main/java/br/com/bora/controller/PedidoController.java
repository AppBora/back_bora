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

    public PedidoController(PedidoService service, PedidoIdempotenteService umaVezSo) {
        this.service = service;
        this.umaVezSo = umaVezSo;
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

    @PatchMapping("/{id}/entregador")
    public Pedido entregador(@PathVariable Long id, @RequestParam(required = false) String nome) {
        return service.definirEntregador(id, nome);
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
