package br.com.bora.controller;

import br.com.bora.entity.*;
import br.com.bora.service.OperacaoService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Configurações operacionais da loja (taxas, formas de pagamento, horário, motivos). */
@RestController
@RequestMapping("/api")
public class OperacaoController {

    private final OperacaoService service;

    public OperacaoController(OperacaoService service) {
        this.service = service;
    }

    // Taxas de entrega
    @GetMapping("/taxas") public List<TaxaEntrega> taxas() { return service.taxas(); }
    @PostMapping("/taxas") public TaxaEntrega criarTaxa(@RequestBody TaxaEntrega t) { t.id = null; return service.salvarTaxa(t); }
    @PutMapping("/taxas/{id}") public TaxaEntrega editarTaxa(@PathVariable Long id, @RequestBody TaxaEntrega t) { t.id = id; return service.salvarTaxa(t); }
    @DeleteMapping("/taxas/{id}") public void excluirTaxa(@PathVariable Long id) { service.excluirTaxa(id); }

    // Formas de pagamento
    @GetMapping("/formas-pagamento") public List<FormaPagamento> formas() { return service.formas(); }
    @PostMapping("/formas-pagamento") public FormaPagamento criarForma(@RequestBody FormaPagamento f) { f.id = null; return service.salvarForma(f); }
    @PutMapping("/formas-pagamento/{id}") public FormaPagamento editarForma(@PathVariable Long id, @RequestBody FormaPagamento f) { f.id = id; return service.salvarForma(f); }
    @DeleteMapping("/formas-pagamento/{id}") public void excluirForma(@PathVariable Long id) { service.excluirForma(id); }

    // Horário de funcionamento
    @GetMapping("/horarios") public List<HorarioFuncionamento> horarios() { return service.horarios(); }
    @PutMapping("/horarios") public List<HorarioFuncionamento> salvarHorarios(@RequestBody List<HorarioFuncionamento> h) { return service.salvarHorarios(h); }

    // Motivos de cancelamento
    @GetMapping("/motivos") public List<MotivoCancelamento> motivos() { return service.motivos(); }
    @PostMapping("/motivos") public MotivoCancelamento criarMotivo(@RequestBody MotivoCancelamento m) { m.id = null; return service.salvarMotivo(m); }
    @PutMapping("/motivos/{id}") public MotivoCancelamento editarMotivo(@PathVariable Long id, @RequestBody MotivoCancelamento m) { m.id = id; return service.salvarMotivo(m); }
    @DeleteMapping("/motivos/{id}") public void excluirMotivo(@PathVariable Long id) { service.excluirMotivo(id); }
}
