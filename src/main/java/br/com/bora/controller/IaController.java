package br.com.bora.controller;

import br.com.bora.entity.Loja;
import br.com.bora.repository.LojaRepository;
import br.com.bora.security.AuthContext;
import br.com.bora.service.IaService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Módulo IA (add-on pago) — endpoints da loja; o gate (loja.moduloIa) é liberado só pelo adm. */
@RestController
@RequestMapping("/api/ia")
public class IaController {

    private final IaService ia;
    private final LojaRepository lojas;
    private final AuthContext ctx;

    public IaController(IaService ia, LojaRepository lojas, AuthContext ctx) {
        this.ia = ia;
        this.lojas = lojas;
        this.ctx = ctx;
    }

    /** Status do add-on para a loja logada (a UI usa para mostrar/ocultar o módulo). */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Long lojaId = ctx.lojaId();
        Loja l = lojas.findById(lojaId).orElse(null);
        return Map.of(
                "contratado", ia.contratado(lojaId),
                "whatsappDono", l == null || l.whatsappDono == null ? "" : l.whatsappDono);
    }

    /** Define o WhatsApp do dono (destino do Gerente Virtual). */
    @PutMapping("/whatsapp-dono")
    public Map<String, Object> whatsappDono(@RequestBody Map<String, String> body) {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        Loja l = ia.exigirModulo(ctx.lojaId());
        l.whatsappDono = body == null ? null : body.get("numero");
        lojas.save(l);
        return Map.of("whatsappDono", l.whatsappDono == null ? "" : l.whatsappDono);
    }

    /** Dispara o Recuperador de clientes agora (também roda sozinho todo dia). */
    @PostMapping("/recuperar")
    public Map<String, Object> recuperar() {
        ctx.requirePapel("ADMINISTRADOR_LOJA", "GERENTE");
        return ia.recuperarClientes(ctx.lojaId());
    }

    /** ROI do Recuperador nos últimos 30 dias. */
    @GetMapping("/recuperacao/resumo")
    public Map<String, Object> resumoRecuperacao() {
        return ia.resumoRecuperacao(ctx.lojaId());
    }

    /** Migração por foto: body { imagemBase64, mediaType } → produtos criados. */
    @PostMapping("/importar-cardapio")
    public Map<String, Object> importarCardapio(@RequestBody Map<String, String> body) {
        ctx.requirePapel("ADMINISTRADOR_LOJA", "GERENTE");
        return ia.importarCardapio(ctx.lojaId(),
                body == null ? null : body.get("imagemBase64"),
                body == null ? null : body.get("mediaType"));
    }

    /** Gera (e opcionalmente envia) o resumo do Gerente Virtual agora. */
    @PostMapping("/gerente")
    public Map<String, Object> gerente(@RequestParam(defaultValue = "false") boolean enviar) {
        ctx.requirePapel("ADMINISTRADOR_LOJA", "GERENTE");
        return ia.gerenteResumo(ctx.lojaId(), enviar);
    }
}
