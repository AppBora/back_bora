package br.com.bora.controller;

import br.com.bora.service.AnaliseRedeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * Painéis de análise da rede (multi-loja): canais + comissão, horário de pico,
 * tempo por status e cancelamentos. Escopado às lojas do usuário (ADMINISTRADOR_LOJA).
 */
@RestController
@RequestMapping("/api/analise")
public class AnaliseController {

    private final AnaliseRedeService service;

    public AnaliseController(AnaliseRedeService service) {
        this.service = service;
    }

    /** Faturamento por canal, comissão estimada dos marketplaces e ranking de produtos. */
    @GetMapping("/canais")
    public Map<String, Object> canais(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
        return service.canais(inicio, fim);
    }

    /** Mapa de vendas por horário — dias úteis x fim de semana. */
    @GetMapping("/horario")
    public Map<String, Object> horario(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
        return service.horario(inicio, fim);
    }

    /** Tempo médio por status do pedido + cancelamentos por motivo e por loja. */
    @GetMapping("/tempos")
    public Map<String, Object> tempos(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
        return service.tempos(inicio, fim);
    }
}
