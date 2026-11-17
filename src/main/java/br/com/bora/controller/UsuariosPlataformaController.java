package br.com.bora.controller;

import br.com.bora.service.UsuariosDaPlataformaService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Usuários de toda a plataforma — só o {@code ADMINISTRADOR_BORA}.
 *
 * <p>Controller separado do {@link PlataformaController} de propósito: aquele já recebe 19
 * dependências no construtor, e empurrar mais quatro para dentro dele por causa de um assunto novo
 * é como um arquivo de 1.100 linhas vira um de 1.400. Aqui o assunto é um só.</p>
 *
 * <p>Não confundir com {@code /api/usuarios} ({@code UsuarioController}): aquele é a visão de UMA
 * loja, com isolamento multi-tenant — alvo de outra loja responde 404. Este é a visão da
 * plataforma. A autorização dos dois é checada no serviço, não aqui.</p>
 *
 * <p><b>Por que {@code /admin-bora/usuarios} e não {@code /api/usuarios/todos}:</b> o prefixo
 * {@code /admin-bora} já é o espaço das rotas cross-tenant, e o {@code BloqueioPorAssinaturaFilter}
 * e o {@code JwtAuthFilter} já tratam esse prefixo como plataforma. Pendurar rota global debaixo de
 * {@code /api/usuarios} misturaria os dois escopos no mesmo caminho.</p>
 */
@RestController
@RequestMapping("/admin-bora/usuarios")
public class UsuariosPlataformaController {

    private final UsuariosDaPlataformaService service;

    public UsuariosPlataformaController(UsuariosDaPlataformaService service) {
        this.service = service;
    }

    /**
     * Todos os usuários da plataforma.
     *
     * @param q      busca por nome ou e-mail (contém, sem distinguir maiúscula)
     * @param loja   restringe a uma loja — pela coluna {@code loja_id} ou por vínculo de rede
     * @param ativos {@code true} esconde os inativos. O padrão mostra tudo: esconder inativo é o
     *               jeito mais rápido de alguém concluir que "esse usuário não existe"
     */
    @GetMapping
    public List<Map<String, Object>> listar(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) Long loja,
                                            @RequestParam(required = false, defaultValue = "false") boolean ativos) {
        return service.listar(q, loja, ativos);
    }

    /**
     * Cria usuário em qualquer loja — ou outro administrador de plataforma, com {@code lojaId} nulo.
     * E-mail que já existe é recusado com 409, nunca promovido.
     */
    @PostMapping
    public Map<String, Object> criar(@RequestBody Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return service.criar(
                texto(b.get("nome")),
                texto(b.get("email")),
                texto(b.get("senha")),
                texto(b.get("papel")),
                numero(b.get("lojaId")));
    }

    /** Liga/desliga. Vale no request seguinte — o filtro reconsulta {@code ativo} a cada requisição. */
    @PutMapping("/{id}/ativo")
    public Map<String, Object> ativo(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        boolean ativo = body != null && Boolean.parseBoolean(String.valueOf(body.get("ativo")));
        return service.definirAtivo(id, ativo);
    }

    /**
     * O que a exclusão destrói, sem destruir nada. A tela chama isto antes de confirmar, para dizer
     * ao dono quantas linhas de histórico perdem o nome.
     */
    @GetMapping("/{id}/previa-exclusao")
    public Map<String, Object> previaExclusao(@PathVariable Long id) {
        return service.previaDaExclusao(id);
    }

    /** Apaga de verdade. Irreversível — a prévia acima existe para ser lida antes. */
    @DeleteMapping("/{id}")
    public Map<String, Object> excluir(@PathVariable Long id) {
        return service.excluir(id);
    }

    private static String texto(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** Aceita número e string: JSON de tela manda {@code "12"} tão fácil quanto {@code 12}. */
    private static Long numero(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || "null".equals(s)) return null;
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "lojaId inválido: \"" + s + "\"");
        }
    }
}
