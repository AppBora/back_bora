package br.com.bora.controller;

import br.com.bora.dto.AssinaturaView;
import br.com.bora.service.AssinaturaService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Assinatura/cobrança da loja logada. */
@RestController
@RequestMapping("/api/assinatura")
public class AssinaturaController {

    private final AssinaturaService service;

    public AssinaturaController(AssinaturaService service) {
        this.service = service;
    }

    /** Situação atual da assinatura da loja (ou vazio se ainda não assinou). */
    @GetMapping
    public AssinaturaView status() {
        return AssinaturaView.de(service.status());
    }

    /** Cria/atualiza a assinatura da loja no plano atual. Corpo opcional: { "cpfCnpj": "..." }. */
    @PostMapping
    public AssinaturaView assinar(@RequestBody(required = false) Map<String, String> body) {
        String cpfCnpj = body == null ? null : body.get("cpfCnpj");
        return AssinaturaView.de(service.assinar(cpfCnpj));
    }
}
