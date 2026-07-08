package br.com.bora.controller;

import br.com.bora.dto.NovaLojaRequest;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Papel;
import br.com.bora.entity.Plano;
import br.com.bora.entity.Usuario;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Administração da plataforma (cross-tenant) — restrito a ADMINISTRADOR_BORA. */
@RestController
@RequestMapping("/admin-bora")
public class PlataformaController {

    private final LojaRepository lojas;
    private final UsuarioRepository usuarios;
    private final PasswordEncoder encoder;
    private final AuthContext ctx;
    private final br.com.bora.repository.UsuarioLojaRepository vinculos;
    private final br.com.bora.repository.ConfigPlataformaRepository configs;

    public PlataformaController(LojaRepository lojas, UsuarioRepository usuarios, PasswordEncoder encoder,
                                AuthContext ctx, br.com.bora.repository.UsuarioLojaRepository vinculos,
                                br.com.bora.repository.ConfigPlataformaRepository configs) {
        this.lojas = lojas;
        this.usuarios = usuarios;
        this.encoder = encoder;
        this.ctx = ctx;
        this.vinculos = vinculos;
        this.configs = configs;
    }

    @GetMapping("/lojas")
    public List<Loja> listarLojas() {
        ctx.requireAdminBora();
        return lojas.findAll();
    }

    /** Provisiona uma nova loja + seu administrador (sem SQL manual). */
    @PostMapping("/lojas")
    @Transactional
    public Map<String, Object> criarLoja(@RequestBody NovaLojaRequest req) {
        ctx.requireAdminBora();
        if (req.nomeLoja() == null || req.nomeLoja().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome da loja é obrigatório");
        }
        if (req.adminEmail() == null || req.adminEmail().isBlank()
                || req.adminSenha() == null || req.adminSenha().length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "E-mail e senha (mín. 6) do admin são obrigatórios");
        }
        String email = req.adminEmail().trim().toLowerCase();
        usuarios.findByEmail(email).ifPresent(u -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "E-mail já cadastrado");
        });

        Loja loja = new Loja();
        loja.setNome(req.nomeLoja());
        loja.setDocumento(req.documento());
        loja.setPlano(parsePlano(req.plano()));
        loja = lojas.save(loja);

        Usuario admin = new Usuario();
        admin.setLojaId(loja.getId());
        admin.setNome(req.adminNome() == null || req.adminNome().isBlank() ? "Administrador" : req.adminNome());
        admin.setEmail(email);
        admin.setSenhaHash(encoder.encode(req.adminSenha()));
        admin.setPapel(Papel.ADMINISTRADOR_LOJA);
        admin = usuarios.save(admin);

        br.com.bora.entity.UsuarioLoja v = new br.com.bora.entity.UsuarioLoja();
        v.setUsuarioId(admin.getId());
        v.setLojaId(loja.getId());
        vinculos.save(v);

        return Map.of("lojaId", loja.getId(), "plano", loja.getPlano().name(), "adminEmail", email);
    }

    /** Libera/revoga o Módulo IA (add-on pago) de uma loja — SÓ o ADMINISTRADOR_BORA. */
    @PutMapping("/lojas/{lojaId}/modulo-ia")
    public Map<String, Object> definirModuloIa(@PathVariable Long lojaId, @RequestBody Map<String, Boolean> body) {
        ctx.requireAdminBora();
        Loja loja = lojas.findById(lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
        loja.moduloIa = body != null && Boolean.TRUE.equals(body.get("habilitado"));
        lojas.save(loja);
        return Map.of("lojaId", lojaId, "moduloIa", loja.moduloIa);
    }

    /** Configurações globais da plataforma — restrito ao ADMINISTRADOR_BORA. */
    @GetMapping("/config")
    public Map<String, String> configuracoes() {
        ctx.requireAdminBora();
        Map<String, String> out = new java.util.LinkedHashMap<>();
        configs.findAll().forEach(c -> out.put(c.getChave(), c.getValor()));
        return out;
    }

    /** Liga/desliga o módulo fiscal (NFC-e) globalmente. Corpo: { "habilitado": true|false }. */
    @PutMapping("/config/fiscal")
    public Map<String, String> definirFiscal(@RequestBody Map<String, Boolean> body) {
        ctx.requireAdminBora();
        boolean habilitado = body != null && Boolean.TRUE.equals(body.get("habilitado"));
        br.com.bora.entity.ConfigPlataforma c = configs.findById("fiscal.habilitado")
                .orElseGet(() -> {
                    br.com.bora.entity.ConfigPlataforma nova = new br.com.bora.entity.ConfigPlataforma();
                    nova.setChave("fiscal.habilitado");
                    return nova;
                });
        c.setValor(String.valueOf(habilitado));
        c.setAtualizadoEm(java.time.OffsetDateTime.now());
        configs.save(c);
        return Map.of("fiscal.habilitado", c.getValor());
    }

    private Plano parsePlano(String plano) {
        if (plano == null || plano.isBlank()) return Plano.UNICO;
        try {
            return Plano.valueOf(plano.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Plano inválido (use UNICO)");
        }
    }
}
