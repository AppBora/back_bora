package br.com.bora.controller;

import br.com.bora.service.DesempenhoService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/** Desempenho consolidado: faturamento, custos e lucro por loja. */
@RestController
@RequestMapping("/api/desempenho")
public class DesempenhoController {

    private final DesempenhoService service;

    public DesempenhoController(DesempenhoService service) {
        this.service = service;
    }

    /** Plataforma vê todos os clientes; dono de rede vê as lojas dele. Sem datas, usa o mês corrente. */
    @GetMapping
    public Map<String, Object> consolidado(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
        return service.consolidado(inicio, fim);
    }
}
