package br.com.bora.controller;

import br.com.bora.dto.NovoUsuario;
import br.com.bora.dto.UsuarioView;
import br.com.bora.service.UsuarioService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/usuarios")
public class UsuarioController {

    private final UsuarioService service;

    public UsuarioController(UsuarioService service) {
        this.service = service;
    }

    @GetMapping
    public List<UsuarioView> listar() {
        return service.listar();
    }

    @PostMapping
    public UsuarioView criar(@RequestBody NovoUsuario novo) {
        return service.criar(novo);
    }

    @PutMapping("/{id}/papel")
    public UsuarioView papel(@PathVariable Long id, @RequestParam String papel) {
        return service.atualizarPapel(id, papel);
    }

    /** Redefine a senha de um usuário da loja (o funcionário esqueceu). Só o admin. */
    @PutMapping("/{id}/senha")
    public br.com.bora.dto.UsuarioView senha(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        return service.definirSenha(id, body == null ? null : body.get("novaSenha"));
    }

    @PutMapping("/{id}/ativo")
    public UsuarioView ativo(@PathVariable Long id, @RequestParam boolean ativo) {
        return service.definirAtivo(id, ativo);
    }
}
