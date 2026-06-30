package br.com.bora.controller;

import br.com.bora.dto.NovoPedidoRequest;
import br.com.bora.dto.PedidoCard;
import br.com.bora.entity.LogStatus;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.StatusPedido;
import br.com.bora.service.PedidoService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/pedidos")
public class PedidoController {

    private final PedidoService service;

    public PedidoController(PedidoService service) {
        this.service = service;
    }

    @GetMapping
    public List<Pedido> listar() {
        return service.listar();
    }

    /** Quadro de pedidos enriquecido (cliente, telefone, endereço, itens) para o kanban. */
    @GetMapping("/board")
    public List<PedidoCard> board() {
        return service.board();
    }

    @PostMapping
    public Pedido criar(@RequestBody NovoPedidoRequest req) {
        return service.criar(req);
    }

    @GetMapping("/{id}/itens")
    public List<PedidoItem> itens(@PathVariable Long id) {
        return service.itens(id);
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
