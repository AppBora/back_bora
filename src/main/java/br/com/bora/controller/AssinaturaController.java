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

    /**
     * Situação atual da assinatura da loja — 204 quando ela ainda não assinou.
     *
     * <p>Devolver null aqui virava <b>200 com corpo vazio</b>, e o navegador estourava ao ler aquilo
     * como JSON. A tela de planos morria nesse erro e nunca chegava a desenhar o botão "Ativar
     * assinatura": na pratica, loja que nunca assinou <b>nao conseguia assinar</b>. 204 diz "nao ha
     * conteudo" sem mentir que ha.</p>
     */
    @GetMapping
    public org.springframework.http.ResponseEntity<AssinaturaView> status() {
        AssinaturaView v = AssinaturaView.de(service.status());
        return v == null ? org.springframework.http.ResponseEntity.noContent().build()
                         : org.springframework.http.ResponseEntity.ok(v);
    }

    /** Cria/atualiza a assinatura da loja no plano atual. Corpo opcional: { "cpfCnpj": "..." }. */
    @PostMapping
    public AssinaturaView assinar(@RequestBody(required = false) Map<String, String> body) {
        String cpfCnpj = body == null ? null : body.get("cpfCnpj");
        return AssinaturaView.de(service.assinar(cpfCnpj));
    }
}
