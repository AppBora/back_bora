package br.com.bora.controller;

import br.com.bora.dto.AcertoLinha;
import br.com.bora.dto.FazerAcertoRequest;
import br.com.bora.entity.AcertoEntregador;
import br.com.bora.service.AcertoEntregadorService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/acertos")
public class AcertoEntregadorController {

    private final AcertoEntregadorService service;

    public AcertoEntregadorController(AcertoEntregadorService service) {
        this.service = service;
    }

    /** Prévia por entregador (entregas realizadas ainda não acertadas no período). */
    @GetMapping("/previa")
    public List<AcertoLinha> previa(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
        return service.previa(inicio, fim);
    }

    /** Fecha o acerto de um entregador (registra o pagamento e trava as entregas). */
    @PostMapping
    public AcertoEntregador fazer(@RequestBody FazerAcertoRequest req) {
        return service.fazer(req);
    }

    /** Histórico de acertos já realizados na loja. */
    @GetMapping("/historico")
    public List<AcertoEntregador> historico() {
        return service.historico();
    }
}
