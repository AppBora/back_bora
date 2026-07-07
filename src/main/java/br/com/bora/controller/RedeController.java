package br.com.bora.controller;

import br.com.bora.service.RedeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Rede multi-loja: lojas do usuário e balancete consolidado. */
@RestController
@RequestMapping("/api/rede")
public class RedeController {

    private final RedeService rede;

    public RedeController(RedeService rede) {
        this.rede = rede;
    }

    /** Lojas vinculadas ao usuário logado (para o seletor de loja). */
    @GetMapping("/lojas")
    public List<Map<String, Object>> lojas() {
        return rede.minhasLojas();
    }

    /** Balancete: faturamento por loja + consolidado no período (default: mês atual). */
    @GetMapping("/balancete")
    public Map<String, Object> balancete(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
        return rede.balancete(inicio, fim);
    }
}
