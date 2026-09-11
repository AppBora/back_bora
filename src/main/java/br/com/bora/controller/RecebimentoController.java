package br.com.bora.controller;

import br.com.bora.service.AsaasSubcontaService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

    /** O que o Asaas ainda espera para liberar a conta do lojista (documento com foto, selfie). */
    @GetMapping("/documentos")
    public Map<String, Object> documentos() {
        return subcontas.documentos();
    }

    /**
     * Envia ao Asaas a foto tirada pelo lojista. O arquivo so passa pela memoria - nada e gravado.
     * Corpo: multipart com `arquivo` (a foto) e `tipo` (IDENTIFICATION, IDENTIFICATION_SELFIE...).
     */
    @PostMapping("/documentos/{documentoId}")
    public Map<String, Object> enviarDocumento(@PathVariable String documentoId,
                                               @RequestParam(value = "tipo", required = false) String tipo,
                                               @RequestParam("arquivo") MultipartFile arquivo) {
        return subcontas.enviarDocumento(documentoId, tipo, arquivo);
    }

    /** Religa o aviso de pagamento (webhook) de quem ja tem subconta mas ficou sem ele. */
    @PostMapping("/webhook")
    public Map<String, Object> repararWebhook() {
        return subcontas.repararWebhook();
    }

    /** Ativa o recebimento criando a subconta do lojista. Corpo: { cpfCnpj, mobilePhone?, postalCode?, ... }. */
    @PostMapping("/ativar")
    public Map<String, Object> ativar(@RequestBody Map<String, Object> body) {
        Object doc = body == null ? null : body.get("cpfCnpj");
        String cpfCnpj = doc == null ? null : String.valueOf(doc);
        return subcontas.ativar(cpfCnpj, body);
    }
}
