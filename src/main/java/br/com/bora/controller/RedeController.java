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

    /** Equipe da loja atual, com as lojas de cada pessoa. */
    @GetMapping("/equipe")
    public List<Map<String, Object>> equipe() {
        return rede.equipe();
    }

    /** Lojas da mesma empresa — as opções válidas para vincular alguém. */
    @GetMapping("/lojas-da-empresa")
    public List<Map<String, Object>> lojasDaEmpresa() {
        return rede.lojasDaEmpresa();
    }

    /** Dá a um usuário acesso a outra loja da mesma empresa (ex.: gerente que cobre duas unidades). */
    @PostMapping("/usuarios/{usuarioId}/lojas")
    public Map<String, Object> vincular(@PathVariable Long usuarioId, @RequestBody Map<String, Long> body) {
        return rede.vincular(usuarioId, body == null ? null : body.get("lojaId"));
    }

    /** Remove o acesso — vale já no request seguinte, não só quando o token expirar. */
    @DeleteMapping("/usuarios/{usuarioId}/lojas/{lojaId}")
    public Map<String, Object> desvincular(@PathVariable Long usuarioId, @PathVariable Long lojaId) {
        return rede.desvincular(usuarioId, lojaId);
    }
}
