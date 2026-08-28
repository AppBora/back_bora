package br.com.bora.controller;

import br.com.bora.service.AsaasSubcontaService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Recebimento do PIX do cliente direto na conta do lojista (subconta Asaas white-label).
 * Substitui o "cole sua API key" por "ativar recebimento" + KYC por link.
 */
@RestController
@RequestMapping("/api/recebimento")
public class RecebimentoController {

    private final AsaasSubcontaService subcontas;

    public RecebimentoController(AsaasSubcontaService subcontas) {
        this.subcontas = subcontas;
    }

    /** Estado do recebimento da loja logada. */
    @GetMapping
    public Map<String, Object> status() {
        return subcontas.status();
    }

    /** Ativa o recebimento criando a subconta do lojista. Corpo: { cpfCnpj, mobilePhone?, postalCode?, ... }. */
    @PostMapping("/ativar")
    public Map<String, Object> ativar(@RequestBody Map<String, Object> body) {
        Object doc = body == null ? null : body.get("cpfCnpj");
        String cpfCnpj = doc == null ? null : String.valueOf(doc);
        return subcontas.ativar(cpfCnpj, body);
    }
}
