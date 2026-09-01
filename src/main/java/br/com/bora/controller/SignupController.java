package br.com.bora.controller;

import br.com.bora.dto.SignupRequest;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Papel;
import br.com.bora.entity.Plano;
import br.com.bora.entity.Usuario;
import br.com.bora.entity.UsuarioLoja;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.repository.UsuarioRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Cadastro self-service de nova loja (público, sem autenticação).
 * Cria a loja no plano ÚNICO + o usuário administrador dela.
 * Se o e-mail já existir E a senha conferir, a nova loja é VINCULADA à conta
 * existente (dono de rede) em vez de dar erro — cada loja tem sua assinatura.
 * URL: POST /public/signup
 */
@RestController
@RequestMapping("/public/signup")
public class SignupController {

    private final LojaRepository lojas;
    private final UsuarioRepository usuarios;
    private final UsuarioLojaRepository vinculos;
    private final PasswordEncoder encoder;
    private final br.com.bora.service.ProvisionamentoService provisionamento;
    private final br.com.bora.service.EmpresaService empresas;

    public SignupController(LojaRepository lojas, UsuarioRepository usuarios,
                            UsuarioLojaRepository vinculos, PasswordEncoder encoder,
                            br.com.bora.service.ProvisionamentoService provisionamento,
                            br.com.bora.service.EmpresaService empresas) {
        this.empresas = empresas;
        this.lojas = lojas;
        this.usuarios = usuarios;
        this.vinculos = vinculos;
        this.encoder = encoder;
        this.provisionamento = provisionamento;
    }

    @PostMapping
    @Transactional
    public Map<String, Object> cadastrar(@RequestBody SignupRequest req) {
        if (req.nomeLoja() == null || req.nomeLoja().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome da loja é obrigatório");
        }
        if (req.adminEmail() == null || !req.adminEmail().contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "E-mail inválido");
        }
        if (req.adminSenha() == null || req.adminSenha().length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A senha deve ter ao menos 6 caracteres");
        }
        String email = req.adminEmail().trim().toLowerCase();

        Usuario existente = usuarios.findByEmail(email).orElse(null);
        if (existente != null) {
            // Conta já existe: só vincula se a senha conferir e a conta for de administrador ativo.
            if (!Boolean.TRUE.equals(existente.getAtivo())
                    || existente.getPapel() != Papel.ADMINISTRADOR_LOJA
                    || !encoder.matches(req.adminSenha(), existente.getSenhaHash())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "E-mail já cadastrado");
            }
            Loja loja = novaLoja(req);
            UsuarioLoja v = new UsuarioLoja();
            v.setUsuarioId(existente.getId());
            v.setLojaId(loja.getId());
            vinculos.save(v);
            return Map.of(
                    "lojaId", loja.getId(),
                    "plano", loja.getPlano().name(),
                    "adminEmail", email,
                    "vinculada", true,
                    "mensagem", "Nova loja vinculada à sua conta! Entre e use o seletor de loja para alternar.");
        }

        Loja loja = novaLoja(req);

        Usuario admin = new Usuario();
        admin.setLojaId(loja.getId());
        admin.setNome(req.adminNome() == null || req.adminNome().isBlank() ? "Administrador" : req.adminNome().trim());
        admin.setEmail(email);
        admin.setSenhaHash(encoder.encode(req.adminSenha()));
        admin.setPapel(Papel.ADMINISTRADOR_LOJA);
        admin = usuarios.save(admin);

        UsuarioLoja v = new UsuarioLoja();
        v.setUsuarioId(admin.getId());
        v.setLojaId(loja.getId());
        vinculos.save(v);

        return Map.of(
                "lojaId", loja.getId(),
                "plano", loja.getPlano().name(),
                "adminEmail", email,
                "vinculada", false,
                "mensagem", "Loja criada com sucesso! Faça login para começar.");
    }

    private Loja novaLoja(SignupRequest req) {
        Loja loja = new Loja();
        loja.setNome(req.nomeLoja().trim());
        loja.setDocumento(req.documento());
        loja.setPlano(Plano.UNICO); // plano único: R$ 299/mês por loja
        loja.empresaId = empresas.paraDocumento(req.documento(), req.nomeLoja()).getId();
        loja = lojas.save(loja);
        provisionamento.semear(loja.getId(), loja.getNome()); // nasce operável (defaults)
        return loja;
    }
}
