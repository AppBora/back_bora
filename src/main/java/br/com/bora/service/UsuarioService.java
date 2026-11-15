package br.com.bora.service;

import br.com.bora.dto.NovoUsuario;
import br.com.bora.dto.UsuarioView;
import br.com.bora.entity.Papel;
import br.com.bora.entity.Usuario;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class UsuarioService {

    private final UsuarioRepository repo;
    private final PlanoService planos;
    private final PasswordEncoder encoder;
    private final AuthContext ctx;
    private final br.com.bora.repository.UsuarioLojaRepository vinculos;
    private final PedidoDeSenhaService pedidosDeSenha;

    /** Formato minimo: tem um @, tem ponto depois dele, e nao tem espaco. */
    private static final java.util.regex.Pattern EMAIL_ACEITAVEL =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");

    public UsuarioService(UsuarioRepository repo, PlanoService planos, PasswordEncoder encoder, AuthContext ctx,
                          br.com.bora.repository.UsuarioLojaRepository vinculos,
                          PedidoDeSenhaService pedidosDeSenha) {
        this.pedidosDeSenha = pedidosDeSenha;
        this.repo = repo;
        this.planos = planos;
        this.encoder = encoder;
        this.ctx = ctx;
        this.vinculos = vinculos;
    }

    public List<UsuarioView> listar() {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        // Por vinculo tambem: so pela coluna loja_id, a tela das lojas de rede aparecia vazia.
        return repo.daLoja(ctx.lojaId()).stream().map(this::view).toList();
    }

    public UsuarioView criar(NovoUsuario n) {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        Long lojaId = ctx.lojaId();
        Papel papel = parsePapel(n.papel());
        if (n.email() == null || n.email().isBlank() || n.senha() == null || n.senha().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "E-mail e senha (mín. 8) são obrigatórios");
        }
        String email = n.email().trim().toLowerCase();
        repo.findByEmail(email).ifPresent(u -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "E-mail já cadastrado");
        });
        planos.checarLimiteUsuarios(lojaId); // RN09

        Usuario u = new Usuario();
        u.setLojaId(lojaId);
        u.setNome(n.nome());
        u.setEmail(email);
        u.setSenhaHash(encoder.encode(n.senha()));
        u.setPapel(papel);
        u = repo.save(u);
        br.com.bora.entity.UsuarioLoja v = new br.com.bora.entity.UsuarioLoja();
        v.setUsuarioId(u.getId());
        v.setLojaId(lojaId);
        vinculos.save(v);
        return view(u);
    }

    public UsuarioView atualizarPapel(Long id, String papel) {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        impedirAlvoProprio(id, "Você não pode alterar o seu próprio papel");
        Usuario u = buscar(id);
        u.setPapel(parsePapel(papel));
        return view(repo.save(u));
    }

    /** O que ainda nao foi atendido na loja de quem esta olhando. So o admin: e ele quem resolve. */
    public java.util.List<java.util.Map<String, Object>> pedidosDeSenhaEmAberto() {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        return pedidosDeSenha.emAberto(ctx.lojaId()).stream().map(p -> {
            Usuario u = repo.findById(p.usuarioId).orElse(null);
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("usuarioId", p.usuarioId);
            m.put("nome", u == null ? "(usuário removido)" : u.getNome());
            m.put("email", u == null ? null : u.getEmail());
            m.put("criadoEm", p.criadoEm);
            // Diz se o WhatsApp saiu de fato. Hoje sai nulo em toda loja: nenhuma tem credencial
            // valida da Meta. Mentir aqui faria o dono achar que a pessoa ja foi avisada.
            m.put("avisadoPorWhatsapp", p.avisadoEm != null);
            return m;
        }).toList();
    }

    /** Redefine a senha de um usuário da loja. Só o admin — é ele quem responde pelos acessos. */
    public UsuarioView definirSenha(Long id, String nova) {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        if (nova == null || nova.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A nova senha precisa ter ao menos 8 caracteres");
        }
        Usuario u = buscar(id);
        u.setSenhaHash(encoder.encode(nova));
        UsuarioView v = view(repo.save(u));
        // O aviso de "esqueci minha senha" some pela ACAO que resolve. Um botao separado de "marcar
        // como resolvido" so acumularia aviso velho na tela ate ninguem mais olhar para ela.
        pedidosDeSenha.atendido(id);
        return v;
    }

    public UsuarioView definirAtivo(Long id, boolean ativo) {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        impedirAlvoProprio(id, "Você não pode desativar a si mesmo");
        Usuario u = buscar(id);
        u.setAtivo(ativo);
        return view(repo.save(u));
    }

    /**
     * Troca o e-mail de acesso de alguem da loja — o login provisorio vira o definitivo.
     *
     * <p><b>Por que isto existe (15/11/2026):</b> nao havia jeito nenhum. O {@code setEmail} do
     * servico so rodava na criacao, e a tela de Usuarios desabilitava o campo na edicao. Uma loja
     * aberta com e-mail provisorio ficava presa a ele para sempre.</p>
     *
     * <p><b>O detalhe que tranca a pessoa para fora:</b> o login procura por
     * {@code findByEmail(email.trim().toLowerCase())}. Gravar aqui com maiuscula ou com espaco
     * sobrando NAO da erro nenhum — a tela diz "salvo" e no proximo login o e-mail simplesmente
     * nao casa. Por isso normaliza antes de gravar, igual a criacao faz.</p>
     *
     * <p><b>Mudar o PROPRIO e-mail e permitido, de proposito.</b> O caso que motivou isto e
     * exatamente o do unico administrador com login provisorio: barrar alvo proprio (como
     * {@code papel} e {@code ativo} fazem) deixaria o recurso inutil para quem mais precisa dele.
     * O token identifica por id ({@code subject}), nao por e-mail, entao a sessao aberta continua
     * valendo; so o claim fica velho ate o proximo login.</p>
     */
    public UsuarioView definirEmail(Long id, String novo) {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        if (novo == null || novo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o novo e-mail");
        }
        String email = novo.trim().toLowerCase();
        if (!EMAIL_ACEITAVEL.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "E-mail invalido: \"" + novo.trim() + "\"");
        }
        Usuario u = buscar(id); // 404 fora da loja de quem pediu -- isolamento
        repo.findByEmail(email).ifPresent(outro -> {
            if (!outro.getId().equals(u.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "E-mail ja cadastrado");
            }
        });
        u.setEmail(email);
        return view(repo.save(u));
    }

    private void impedirAlvoProprio(Long id, String msg) {
        if (id != null && id.equals(ctx.atual().userId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        }
    }

    private Papel parsePapel(String papel) {
        try {
            Papel p = Papel.valueOf(papel);
            if (p == Papel.ADMINISTRADOR_BORA) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Papel ADMINISTRADOR_BORA não pode ser atribuído pela loja");
            }
            return p;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Papel inválido");
        }
    }

    private Usuario buscar(Long id) {
        Usuario u = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));
        if (!ctx.lojaId().equals(u.getLojaId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado");
        }
        return u;
    }

    private UsuarioView view(Usuario u) {
        return new UsuarioView(u.getId(), u.getNome(), u.getEmail(), u.getPapel().name(), u.getAtivo(), u.getLojaId());
    }
}
