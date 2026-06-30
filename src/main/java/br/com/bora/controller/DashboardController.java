package br.com.bora.controller;

import br.com.bora.service.PedidoService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final PedidoService service;

    public DashboardController(PedidoService service) {
        this.service = service;
    }

    @GetMapping("/resumo")
    public Map<String, Object> resumo() {
        return service.resumo(); // loja vem do token (multi-tenant)
    }
}
