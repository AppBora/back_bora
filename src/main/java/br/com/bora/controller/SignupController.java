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
    private final br.com.bora.security.FreioDeTentativas freio;

    public SignupController(LojaRepository lojas, UsuarioRepository usuarios,
                            UsuarioLojaRepository vinculos, PasswordEncoder encoder,
                            br.com.bora.service.ProvisionamentoService provisionamento,
                            br.com.bora.service.EmpresaService empresas,
                            br.com.bora.security.FreioDeTentativas freio) {
        this.freio = freio;
        this.empresas = empresas;
        this.lojas = lojas;
        this.usuarios = usuarios;
        this.vinculos = vinculos;
        this.encoder = encoder;
        this.provisionamento = provisionamento;
    }

    @PostMapping
    @Transactional
    public Map<String, Object> cadastrar(@RequestBody SignupRequest req,
                                        jakarta.servlet.http.HttpServletRequest http) {
        if (req.nomeLoja() == null || req.nomeLoja().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome da loja é obrigatório");
        }
        if (req.adminEmail() == null || !req.adminEmail().contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "E-mail inválido");
        }
        if (req.adminSenha() == null || req.adminSenha().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A senha deve ter ao menos 8 caracteres");
        }
        if (!Boolean.TRUE.equals(req.aceiteTermos())) {
            // Sem isto nao ha contrato aceito com ninguem - e os Termos publicados falam de pagamento,
            // suspensao, cancelamento e reembolso.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Para criar a conta e preciso aceitar os Termos de Uso e a Política de Privacidade.");
        }
        String email = req.adminEmail().trim().toLowerCase();
        // Este cadastro responde diferente para senha certa e errada de uma conta que existe, o que o
        // torna um adivinhador de senha. Enquanto o fluxo for esse, o freio é o que impede a varredura.
        String chave = br.com.bora.security.FreioDeTentativas.chave(email, br.com.bora.security.FreioDeTentativas.origem(http));
        freio.conferir(chave);

        Usuario existente = usuarios.findByEmail(email).orElse(null);
        if (existente != null) {
            // Conta já existe: só vincula se a senha conferir e a conta for de administrador ativo.
            if (!Boolean.TRUE.equals(existente.getAtivo())
                    || existente.getPapel() != Papel.ADMINISTRADOR_LOJA
                    || !encoder.matches(req.adminSenha(), existente.getSenhaHash())) {
                freio.errou(chave);
                throw new ResponseStatusException(HttpStatus.CONFLICT, "E-mail já cadastrado");
            }
            freio.acertou(chave);
            // Conta provada pela senha: é o mesmo dono, então pode entrar na empresa do CNPJ dele.
            Loja loja = novaLoja(req, true, br.com.bora.security.FreioDeTentativas.origem(http));
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

        Loja loja = novaLoja(req, false, br.com.bora.security.FreioDeTentativas.origem(http));

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

    /**
     * @param donoProvado a pessoa provou ser a dona da conta (acertou a senha de um administrador já
     *                    cadastrado). Só nesse caso a loja nova pode entrar numa empresa que já existe.
     */
    /** Data de revisao do texto publicado em /termos.html. Mudou o texto, muda isto aqui. */
    private static final String VERSAO_DOS_TERMOS = "2026-10-02";

    private Loja novaLoja(SignupRequest req, boolean donoProvado, String origemDoAceite) {
        if (!donoProvado && empresas.documentoJaUsado(req.documento())) {
            // Sem esta guarda, cadastrar com o CNPJ de um cliente colocava a loja nova na empresa dele —
            // e, pela rede multi-lojas, o painel dele ficava a um clique de distância.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esse CNPJ já tem loja no BoraHapp. Se a loja é sua, entre com a sua conta e crie a "
                            + "nova loja por dentro do sistema, ou fale com a gente no WhatsApp.");
        }
        Loja loja = new Loja();
        loja.setNome(req.nomeLoja().trim());
        loja.setDocumento(req.documento());
        loja.setPlano(Plano.UNICO); // plano único: R$ 199/mês por loja (preço de lançamento)
        loja.empresaId = empresas.paraDocumento(req.documento(), req.nomeLoja()).getId();
        loja.acessoAte = java.time.OffsetDateTime.now().plusDays(7); // os 7 dias gratis do site
        loja.termosAceitosEm = java.time.OffsetDateTime.now();
        loja.termosVersao = VERSAO_DOS_TERMOS;
        loja.termosAceitosDe = origemDoAceite;
        loja = lojas.save(loja);
        provisionamento.semear(loja.getId(), loja.getNome()); // nasce operável (defaults)
        return loja;
    }
}
