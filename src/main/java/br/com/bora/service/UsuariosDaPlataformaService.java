package br.com.bora.service;

import br.com.bora.entity.Loja;
import br.com.bora.entity.Papel;
import br.com.bora.entity.Usuario;
import br.com.bora.entity.UsuarioLoja;
import br.com.bora.repository.LogStatusRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioLojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Usuários da PLATAFORMA INTEIRA, para o administrador master.
 *
 * <h2>Por que isto existe</h2>
 * <p>A tela de Usuários de uma loja lista {@code daLoja(lojaId)} — quem tem {@code loja_id} dessa
 * loja ou vínculo com ela. O {@code ADMINISTRADOR_BORA} tem {@code loja_id} nulo por desenho
 * (papel global), então <b>conta de plataforma não aparece em lista de loja nenhuma</b>. E o dono
 * da plataforma não tinha nenhuma tela que mostrasse todo mundo de uma vez: para achar um usuário
 * de outra loja, tinha de entrar em cada loja pelo acesso de suporte e olhar uma por uma.</p>
 *
 * <h2>O que este serviço faz de diferente do {@link UsuarioService}</h2>
 * <p>O {@code UsuarioService} é a visão de UMA loja e tranca tudo nela: o {@code buscar} responde
 * 404 para alvo de outra loja, de propósito — é o isolamento multi-tenant. Aqui o escopo é a
 * plataforma, então o isolamento não se aplica; o que entra no lugar dele são as travas de
 * integridade do capítulo abaixo.</p>
 *
 * <h2>As travas, e o que cada uma evita</h2>
 * <ol>
 *   <li><b>Ninguém mexe na própria conta</b> — desativar ou excluir a si mesmo tira o acesso de
 *       quem está operando, no request seguinte, sem caminho de volta.</li>
 *   <li><b>Nunca o último {@code ADMINISTRADOR_BORA} ativo</b> — a plataforma ficaria sem dono, e
 *       o único caminho de volta seria mexer no banco. A contagem é feita com a lista travada
 *       ({@code SELECT ... FOR UPDATE}): sem o lock, dois pedidos simultâneos leem "o outro ainda
 *       está ativo" antes de qualquer commit e os dois passam.</li>
 *   <li><b>Nunca o último {@code ADMINISTRADOR_LOJA} ativo de uma loja</b> — e esta não é teoria:
 *       {@code UsuarioRepository.donosDaLoja} é o que informa o e-mail do lojista na cobrança do
 *       Asaas. Loja sem dono ativo gera cobrança <b>sem e-mail nenhum</b>, e o lojista nunca recebe
 *       a fatura. Já aconteceu.</li>
 * </ol>
 *
 * <h2>Excluir ≠ desativar, e a diferença é irreversível</h2>
 * <p>Medido no schema em 17/11/2026, apontam para {@code usuario}:</p>
 * <ul>
 *   <li>{@code usuario_loja.usuario_id} — FK <b>sem {@code ON DELETE}</b>: o DELETE direto falha
 *       com violação de integridade. Por isso os vínculos são apagados antes, na mesma transação.</li>
 *   <li>{@code pedido_de_senha.usuario_id} — {@code ON DELETE CASCADE}: o banco resolve.</li>
 *   <li>{@code log_status.usuario_id} — <b>sem FK</b>. Não impede nada e não é limpo: o histórico de
 *       status dos pedidos fica com um id que não resolve mais para nome nenhum. <b>É perda de
 *       auditoria, e é por isso que a prévia da exclusão conta quantas linhas perdem o nome.</b></li>
 *   <li>{@code ia_analise_rede.usuario_id} — sem FK; sobra linha órfã no teto de custo diário da IA,
 *       sem efeito prático.</li>
 * </ul>
 *
 * <p><b>Desativar é o caminho normal:</b> é reversível, mantém o histórico legível e corta o acesso
 * no request seguinte, porque o {@code JwtAuthFilter} reconsulta {@code ativo} a cada requisição —
 * não espera o token de 24h vencer. Excluir existe para conta criada por engano e para teste.</p>
 */
@Service
public class UsuariosDaPlataformaService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(UsuariosDaPlataformaService.class);

    /** Mesmo formato mínimo do UsuarioService: tem um @, tem ponto depois dele, e não tem espaço. */
    private static final Pattern EMAIL_ACEITAVEL =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");

    private final UsuarioRepository usuarios;
    private final UsuarioLojaRepository vinculos;
    private final LojaRepository lojas;
    private final LogStatusRepository logs;
    private final PasswordEncoder encoder;
    private final PlanoService planos;
    private final AuthContext ctx;

    public UsuariosDaPlataformaService(UsuarioRepository usuarios, UsuarioLojaRepository vinculos,
                                       LojaRepository lojas, LogStatusRepository logs,
                                       PasswordEncoder encoder, PlanoService planos, AuthContext ctx) {
        this.usuarios = usuarios;
        this.vinculos = vinculos;
        this.lojas = lojas;
        this.logs = logs;
        this.encoder = encoder;
        this.planos = planos;
        this.ctx = ctx;
    }

    // ------------------------------------------------------------------ listar

    /**
     * Todo mundo da plataforma. Administradores de plataforma primeiro (eles não têm loja e
     * desapareceriam no fim de uma ordenação por nome de loja), depois por loja e nome.
     *
     * @param busca    filtra por nome ou e-mail (contém, sem distinguir maiúscula). Nulo = tudo.
     * @param lojaId   restringe a uma loja (pela coluna ou por vínculo). Nulo = todas.
     * @param somenteAtivos true mostra só ativos. O padrão é <b>false</b>: esconder inativo é o
     *                      jeito mais rápido de alguém concluir que "o usuário não existe".
     */
    public List<Map<String, Object>> listar(String busca, Long lojaId, boolean somenteAtivos) {
        ctx.requireAdminBora();
        Map<Long, String> nomeDaLoja = nomesDasLojas();
        Map<Long, List<Long>> vinculosPorUsuario = vinculos.findAll().stream()
                .collect(Collectors.groupingBy(UsuarioLoja::getUsuarioId,
                        Collectors.mapping(UsuarioLoja::getLojaId, Collectors.toList())));
        String q = busca == null ? "" : busca.trim().toLowerCase();
        Long eu = ctx.atual().userId();

        // Plataforma primeiro (não tem loja, e numa ordenação por nome de loja cairia no fim),
        // depois loja, depois nome. Em variável e com tipos explícitos: a mesma cadeia escrita
        // dentro do sorted() depende de inferência e quebra feio quando alguém mexe num degrau.
        Comparator<Usuario> ordem = Comparator
                .comparingInt((Usuario u) -> u.getPapel() == Papel.ADMINISTRADOR_BORA ? 0 : 1)
                .thenComparing(u -> nomeDaLoja.getOrDefault(u.getLojaId(), ""),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(u -> u.getNome() == null ? "" : u.getNome(),
                        String.CASE_INSENSITIVE_ORDER);

        return usuarios.findAll().stream()
                .filter(u -> {
                    if (somenteAtivos && !Boolean.TRUE.equals(u.getAtivo())) return false;
                    if (!q.isEmpty()) {
                        String nome = u.getNome() == null ? "" : u.getNome().toLowerCase();
                        String mail = u.getEmail() == null ? "" : u.getEmail().toLowerCase();
                        if (!nome.contains(q) && !mail.contains(q)) return false;
                    }
                    if (lojaId != null) {
                        boolean daColuna = lojaId.equals(u.getLojaId());
                        boolean porVinculo = vinculosPorUsuario
                                .getOrDefault(u.getId(), List.of()).contains(lojaId);
                        if (!daColuna && !porVinculo) return false;
                    }
                    return true;
                })
                .sorted(ordem)
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", u.getId());
                    m.put("nome", u.getNome());
                    m.put("email", u.getEmail());
                    m.put("papel", u.getPapel() == null ? null : u.getPapel().name());
                    m.put("ativo", Boolean.TRUE.equals(u.getAtivo()));
                    m.put("lojaId", u.getLojaId());
                    m.put("lojaNome", u.getLojaId() == null
                            ? null : nomeDaLoja.getOrDefault(u.getLojaId(), "(loja #" + u.getLojaId() + ")"));
                    // As lojas que ele alcança além da própria — é o que explica por que um dono de
                    // rede aparece numa loja em que a coluna loja_id não aponta.
                    m.put("outrasLojas", vinculosPorUsuario.getOrDefault(u.getId(), List.of()).stream()
                            .filter(l -> !l.equals(u.getLojaId()))
                            .map(l -> Map.<String, Object>of("id", l,
                                    "nome", nomeDaLoja.getOrDefault(l, "(loja #" + l + ")")))
                            .toList());
                    m.put("criadoEm", u.getCriadoEm());
                    m.put("euMesmo", u.getId().equals(eu));
                    return m;
                })
                .toList();
    }

    // ------------------------------------------------------------------ incluir

    /**
     * Cria usuário em qualquer loja, ou outro administrador de plataforma ({@code lojaId} nulo).
     *
     * <p><b>E-mail que já existe é RECUSADO, nunca promovido.</b> Promover em silêncio transformaria
     * "cadastrei um colega" em escalar o operador de uma loja a dono da plataforma inteira — é a
     * mesma regra que o {@code criarAdmin} já aplicava, e vale aqui pelo mesmo motivo.</p>
     *
     * <p><b>O limite de usuários do plano NÃO bloqueia aqui, de propósito.</b> Quem vende o plano é
     * quem está clicando: travar o dono da plataforma no limite comercial do cliente dele seria
     * inverter os papéis. Mas passar do limite em silêncio é furo de receita — então a resposta
     * devolve {@code aviso} dizendo que passou, com a contagem.</p>
     */
    @Transactional
    public Map<String, Object> criar(String nome, String email, String senha, String papelStr, Long lojaId) {
        ctx.requireAdminBora();
        if (nome == null || nome.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o nome");
        }
        if (senha == null || senha.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A senha precisa ter ao menos 8 caracteres");
        }
        String limpo = email == null ? "" : email.trim().toLowerCase();
        if (!EMAIL_ACEITAVEL.matcher(limpo).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "E-mail inválido: \"" + (email == null ? "" : email.trim()) + "\"");
        }
        if (usuarios.findByEmail(limpo).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este e-mail já existe no sistema. Use um e-mail que ainda não tenha conta.");
        }
        Papel papel = parsePapel(papelStr);

        if (papel == Papel.ADMINISTRADOR_BORA) {
            if (lojaId != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Administrador da plataforma é papel global: não pertence a loja nenhuma. "
                                + "Deixe a loja em branco.");
            }
        } else {
            if (lojaId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Escolha a loja. Só o administrador da plataforma fica sem loja.");
            }
            if (lojas.findById(lojaId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loja #" + lojaId + " não existe");
            }
        }

        Usuario u = new Usuario();
        u.setNome(nome.trim());
        u.setEmail(limpo);
        u.setSenhaHash(encoder.encode(senha));
        u.setPapel(papel);
        u.setLojaId(lojaId);
        u.setAtivo(true);
        u = usuarios.save(u);

        if (lojaId != null) {
            // O vínculo é o que faz a pessoa aparecer na tela de Usuários da loja e contar no
            // limite do plano: a consulta daLoja lê coluna OU vínculo.
            UsuarioLoja v = new UsuarioLoja();
            v.setUsuarioId(u.getId());
            v.setLojaId(lojaId);
            vinculos.save(v);
        }

        auditar("CRIOU", u, lojaId == null ? "plataforma" : "loja " + lojaId);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", u.getId());
        r.put("nome", u.getNome());
        r.put("email", u.getEmail());
        r.put("papel", u.getPapel().name());
        r.put("lojaId", u.getLojaId());
        r.put("ativo", true);
        r.put("avisoSenha",
                "Peça para a pessoa trocar a senha no primeiro acesso, em Configurações › Minha senha.");
        if (lojaId != null) {
            long ativos = usuarios.contarAtivosDaLoja(lojaId);
            int teto = planos.plano(lojaId).maxUsuarios;
            r.put("usuariosAtivosNaLoja", ativos);
            r.put("tetoDoPlano", teto);
            if (ativos > teto) {
                r.put("aviso", "Esta loja passou o limite do plano: " + ativos + " usuários ativos, "
                        + "teto de " + teto + ". O cadastro foi feito — decida se cobra upgrade.");
            }
        }
        return r; // nunca o hash
    }

    // ------------------------------------------------------------------ ativar / desativar

    /**
     * Liga ou desliga qualquer usuário da plataforma. Vale no request seguinte: o
     * {@code JwtAuthFilter} reconsulta {@code ativo} a cada requisição.
     */
    @Transactional
    public Map<String, Object> definirAtivo(Long id, boolean ativo) {
        ctx.requireAdminBora();
        Usuario alvo = buscar(id);
        if (!ativo) {
            impedirAlvoProprio(alvo, "desativar");
            impedirUltimoAdminDaPlataforma(alvo, "desativar");
            impedirDeixarLojaSemDono(alvo, "Desativar");
        }
        alvo.setAtivo(ativo);
        usuarios.save(alvo);
        auditar(ativo ? "REATIVOU" : "DESATIVOU", alvo, null);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", alvo.getId());
        r.put("ativo", ativo);
        r.put("efeito", ativo
                ? "A pessoa volta a entrar no próximo login."
                : "O acesso cai no próximo clique que ela der, sem esperar o login vencer.");
        return r;
    }

    // ------------------------------------------------------------------ excluir

    /**
     * O que a exclusão destrói, para a tela poder confirmar com número em vez de "tem certeza?".
     * Não altera nada.
     */
    public Map<String, Object> previaDaExclusao(Long id) {
        ctx.requireAdminBora();
        Usuario alvo = buscar(id);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", alvo.getId());
        r.put("nome", alvo.getNome());
        r.put("email", alvo.getEmail());
        r.put("papel", alvo.getPapel() == null ? null : alvo.getPapel().name());
        r.put("ativo", Boolean.TRUE.equals(alvo.getAtivo()));
        r.put("vinculosDeLoja", vinculos.countByUsuarioId(alvo.getId()));
        long mexeuEmPedidos = logs.countByUsuarioId(alvo.getId());
        r.put("linhasDeHistoricoQuePerdemONome", mexeuEmPedidos);
        List<String> impedimentos = new ArrayList<>();
        try { impedirAlvoProprio(alvo, "excluir"); } catch (ResponseStatusException e) { impedimentos.add(e.getReason()); }
        // SemTravar: a previa roda fora de transacao. Ver o javadoc do metodo.
        try { impedirUltimoAdminDaPlataformaSemTravar(alvo, "excluir"); } catch (ResponseStatusException e) { impedimentos.add(e.getReason()); }
        try { impedirDeixarLojaSemDono(alvo, "Excluir"); } catch (ResponseStatusException e) { impedimentos.add(e.getReason()); }
        r.put("impedimentos", impedimentos);
        r.put("podeExcluir", impedimentos.isEmpty());
        r.put("recomendacao", mexeuEmPedidos > 0
                ? "Esta conta tem " + mexeuEmPedidos + " registro(s) no histórico de pedidos. "
                  + "Excluir deixa esse histórico sem o nome de quem mexeu. Prefira DESATIVAR: corta "
                  + "o acesso do mesmo jeito e o histórico continua legível."
                : "Esta conta não tem histórico de pedidos. Excluir aqui não apaga nada que alguém leia depois.");
        return r;
    }

    /**
     * Apaga de verdade. Irreversível.
     *
     * <p>Ordem obrigatória: <b>vínculos primeiro</b>. A FK {@code usuario_loja.usuario_id} não tem
     * {@code ON DELETE}, então apagar o usuário com vínculo vivo falha com violação de integridade —
     * e, numa transação do Postgres, a recusa desfaz a transação inteira.</p>
     *
     * <p>O {@code pedido_de_senha} cai por {@code ON DELETE CASCADE} do próprio banco. O
     * {@code log_status} <b>não</b> é tocado: ele guarda o histórico dos pedidos, e apagar linha de
     * histórico para limpar um usuário seria apagar o que o lojista pode precisar ler. O id fica
     * órfão — por isso o AUDITORIA abaixo registra nome e e-mail antes de o registro sumir, que é a
     * única coisa que resta depois.</p>
     */
    @Transactional
    public Map<String, Object> excluir(Long id) {
        ctx.requireAdminBora();
        Usuario alvo = buscar(id);
        impedirAlvoProprio(alvo, "excluir");
        impedirUltimoAdminDaPlataforma(alvo, "excluir");
        impedirDeixarLojaSemDono(alvo, "Excluir");

        long historico = logs.countByUsuarioId(alvo.getId());
        List<UsuarioLoja> dele = vinculos.findByUsuarioId(alvo.getId());
        String nome = alvo.getNome();
        String email = alvo.getEmail();
        Papel papel = alvo.getPapel();

        if (!dele.isEmpty()) {
            vinculos.deleteAll(dele); // ANTES do usuário: a FK não tem ON DELETE
        }
        usuarios.delete(alvo);

        Usuario quem = usuarios.findById(ctx.atual().userId()).orElse(null);
        log.warn("AUDITORIA plataforma: usuario {} ({}) EXCLUIU DEFINITIVAMENTE o usuario {} ({}, papel {}), "
                        + "{} vinculo(s) de loja removido(s), {} linha(s) de log_status ficaram sem nome",
                quem == null ? "?" : quem.getId(), quem == null ? "?" : quem.getEmail(),
                id, email, papel, dele.size(), historico);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("excluido", true);
        r.put("id", id);
        r.put("nome", nome);
        r.put("email", email);
        r.put("vinculosRemovidos", dele.size());
        r.put("linhasDeHistoricoSemNome", historico);
        return r;
    }

    // ------------------------------------------------------------------ travas

    private Usuario buscar(Long id) {
        // Sem checagem de loja: o escopo aqui é a plataforma. O que protege são as travas abaixo.
        return usuarios.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));
    }

    private void impedirAlvoProprio(Usuario alvo, String acao) {
        if (alvo.getId().equals(ctx.atual().userId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Você não pode " + acao + " a sua própria conta.");
        }
    }

    /**
     * Trava a lista de administradores de plataforma com {@code FOR UPDATE} <b>antes</b> de decidir.
     * Sem o lock, duas chamadas simultâneas contam "o outro ainda está ativo" e as duas passam —
     * resultado: plataforma sem dono.
     *
     * <p><b>Só para quem ALTERA</b> ({@code definirAtivo}, {@code excluir}), que roda dentro de
     * {@code @Transactional}: consulta com trava pessimista <b>exige transação aberta</b>.</p>
     */
    private void impedirUltimoAdminDaPlataforma(Usuario alvo, String acao) {
        if (alvo.getPapel() != Papel.ADMINISTRADOR_BORA) return;
        conferirSeSobraAdmin(alvo, acao, usuarios.findByPapelParaAtualizar(Papel.ADMINISTRADOR_BORA));
    }

    /**
     * A MESMA regra, sem o {@code FOR UPDATE} — para a prévia, que não altera nada.
     *
     * <h2>O defeito que isto conserta (17/11/2026, achado em produção pelo dono)</h2>
     * <p>A prévia chamava a versão travada e estourava
     * <i>"Query requires transaction be in progress, but no transaction is known to be in
     * progress"</i>: ela não tem {@code @Transactional}, e trava pessimista sem transação não roda.
     * O botão Excluir ficava inútil — mas <b>só quando o alvo era ADMINISTRADOR_BORA</b>, porque é
     * o único papel que chega a executar esta consulta. A bateria tinha previsto exclusão de
     * operador e de admin de loja, que saem da função antes dela. <b>O caminho nunca foi pisado.</b></p>
     *
     * <p><b>Por que não foi só pôr {@code @Transactional(readOnly = true)}:</b> a prévia é leitura e
     * não tem por que segurar linha do banco. Abrir transação só para satisfazer uma trava que ela
     * não precisa seria carregar o custo e o risco de contenção numa consulta que só informa.</p>
     */
    private void impedirUltimoAdminDaPlataformaSemTravar(Usuario alvo, String acao) {
        if (alvo.getPapel() != Papel.ADMINISTRADOR_BORA) return;
        conferirSeSobraAdmin(alvo, acao, usuarios.findByPapel(Papel.ADMINISTRADOR_BORA));
    }

    /** A conta em si, igual nos dois caminhos. Quem decide travar ou não é quem chama. */
    private void conferirSeSobraAdmin(Usuario alvo, String acao, List<Usuario> daPlataforma) {
        long ativosDepois = daPlataforma.stream()
                .filter(u -> Boolean.TRUE.equals(u.getAtivo()) && !u.getId().equals(alvo.getId()))
                .count();
        if (ativosDepois == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Este é o último administrador ativo da plataforma. Crie outro antes de "
                            + acao + " este.");
        }
    }

    /**
     * Loja não pode ficar sem {@code ADMINISTRADOR_LOJA} ativo.
     *
     * <p>Não é zelo abstrato: {@code donosDaLoja} é a consulta que informa o e-mail do lojista na
     * cobrança do Asaas, e ela filtra {@code ativo = true}. Loja sem dono ativo passa a gerar
     * cobrança sem e-mail, e o lojista não recebe a fatura.</p>
     *
     * <p>Considera as lojas que a pessoa alcança pela coluna <b>e</b> por vínculo — o dono de rede
     * tem uma conta só, ligada às outras lojas por {@code usuario_loja}.</p>
     */
    private void impedirDeixarLojaSemDono(Usuario alvo, String acao) {
        if (alvo.getPapel() != Papel.ADMINISTRADOR_LOJA) return;
        if (!Boolean.TRUE.equals(alvo.getAtivo())) return; // já inativo: a loja já está sem ele
        Set<Long> lojasDele = new LinkedHashSet<>();
        if (alvo.getLojaId() != null) lojasDele.add(alvo.getLojaId());
        vinculos.findByUsuarioId(alvo.getId()).forEach(v -> lojasDele.add(v.getLojaId()));

        Map<Long, String> nomes = nomesDasLojas();
        List<String> ficariamSemDono = new ArrayList<>();
        for (Long l : lojasDele) {
            List<Usuario> donos = usuarios.donosDaLoja(l);
            boolean soEle = donos.size() == 1 && donos.get(0).getId().equals(alvo.getId());
            if (soEle) ficariamSemDono.add(nomes.getOrDefault(l, "loja #" + l) + " (#" + l + ")");
        }
        if (!ficariamSemDono.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    acao + " esta conta deixaria sem administrador: " + String.join(", ", ficariamSemDono)
                            + ". Loja sem dono ativo gera cobrança sem e-mail e o lojista não recebe a "
                            + "fatura. Promova outra pessoa a Administrador da loja primeiro — em "
                            + "Plataforma › \"Entrar na loja\", tela Usuários. Para encerrar o cliente, "
                            + "use suspender/arquivar a loja, não apagar o dono.");
        }
    }

    // ------------------------------------------------------------------ apoio

    private Map<Long, String> nomesDasLojas() {
        Map<Long, String> m = new LinkedHashMap<>();
        for (Loja l : lojas.findAll()) m.put(l.getId(), l.getNome());
        return m;
    }

    private Papel parsePapel(String papel) {
        try {
            return Papel.valueOf(papel == null ? "" : papel.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Papel inválido. Use OPERADOR, GERENTE, ADMINISTRADOR_LOJA ou ADMINISTRADOR_BORA.");
        }
    }

    private void auditar(String acao, Usuario alvo, String onde) {
        Usuario quem = usuarios.findById(ctx.atual().userId()).orElse(null);
        log.warn("AUDITORIA plataforma: usuario {} ({}) {} o usuario {} ({}, papel {}){}",
                quem == null ? "?" : quem.getId(), quem == null ? "?" : quem.getEmail(),
                acao, alvo.getId(), alvo.getEmail(), alvo.getPapel(),
                onde == null ? "" : " em " + onde);
    }
}
