package br.com.bora.controller;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.service.FiscalService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Emissão fiscal (NFC-e) — DORMENTE: responde 409 enquanto o ADMINISTRADOR_BORA
 * não ligar a chave global (config_plataforma 'fiscal.habilitado').
 */
@RestController
@RequestMapping("/api/fiscal")
public class FiscalController {

    private final FiscalService fiscal;
    private final PedidoRepository pedidos;
    private final PedidoItemRepository itens;
    private final LojaRepository lojas;
    private final AuthContext ctx;

    public FiscalController(FiscalService fiscal, PedidoRepository pedidos, PedidoItemRepository itens,
                            LojaRepository lojas, AuthContext ctx) {
        this.fiscal = fiscal;
        this.pedidos = pedidos;
        this.itens = itens;
        this.lojas = lojas;
        this.ctx = ctx;
    }

    /** Emite a NFC-e de um pedido da loja logada (somente com o módulo ligado na plataforma). */
    @PostMapping("/nfce/{pedidoId}")
    public Map<String, Object> emitir(@PathVariable Long pedidoId) {
        ctx.requirePapel("ADMINISTRADOR_LOJA", "GERENTE");
        Long lojaId = ctx.lojaId();
        Pedido p = pedidos.findByIdAndLojaId(pedidoId, lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado"));
        Loja loja = lojas.findById(lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
        return fiscal.emitirNfce(loja, p, itens.findByLojaIdAndPedidoIdOrderById(lojaId, pedidoId));
    }
}
