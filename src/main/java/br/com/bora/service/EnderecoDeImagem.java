package br.com.bora.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * UM lugar que decide o que é um endereço de imagem válido — a metade da GRAVAÇÃO do conserto do XSS.
 *
 * <h2>O defeito, e por que escapar a tela não bastava</h2>
 *
 * <p>Em 18/11/2026 o cardápio público montava {@code <img src="${p.imagem}">} <b>sem escapar</b>, e a
 * cadeia inteira deixava passar qualquer texto: {@code ProdutoService.atualizar} gravava
 * {@code imagemUrl} sem validar nada, e o {@code PublicController} devolvia o valor cru quando ele
 * não começava com {@code data:}. Um GERENTE de qualquer loja gravava a carga num produto dele e, ao
 * mandar o link do próprio cardápio para o suporte, levava o token de {@code ADMINISTRADOR_BORA} —
 * acesso a todas as lojas. A CSP tem {@code script-src 'unsafe-inline'}, o painel e o cardápio são a
 * <b>mesma origem</b>, e o token mora em {@code localStorage}.</p>
 *
 * <p><b>A tela já foi consertada</b> (o {@code cardapio.js} escapa a foto, e há teste que cai se
 * alguém tirar o escape). Esta classe fecha a outra ponta: <b>o dado ruim não entra</b>.</p>
 *
 * <h2>Por que as DUAS metades, se uma resolve</h2>
 *
 * <p>Porque elas falham por motivos diferentes. O escape protege <b>a tela que existe hoje</b>; no
 * dia em que alguém montar uma segunda tela com o mesmo campo — uma folha impressa, um PDF, um
 * e-mail de confirmação, um painel novo — o escape daquela tela é uma decisão nova, e o valor
 * malicioso já estaria no banco esperando. Validar na gravação é o que impede o banco de guardar
 * arma carregada.</p>
 *
 * <h2>O que é aceito, e por que exatamente isto</h2>
 *
 * <p>Medido no dump de produção de 18/11/2026, campo a campo — é por isso que a regra é esta e não
 * uma mais estreita:</p>
 *
 * <ul>
 *   <li><b>{@code data:image/...;base64,...}</b> — o caminho normal deste sistema: 200 produtos em
 *       {@code data:image/jpeg}, 31 em {@code webp}, 1 em {@code png}, e <b>todos</b> os logos das
 *       lojas em {@code data:image/jpeg} ou {@code png};</li>
 *   <li><b>{@code https://...}</b> — existe 1 banner assim em produção (loja 1);</li>
 *   <li><b>caminho da própria origem</b>, começando por {@code /} — é a forma que o
 *       {@code PublicController} devolve o logo ({@code /public/loja/1/logo}), e gravar isso de volta
 *       não pode ser recusado;</li>
 *   <li><b>vazio ou nulo</b> — "sem imagem" é estado legítimo.</li>
 * </ul>
 *
 * <p>Qualquer outra coisa é recusada com <b>400</b>, e isso inclui o que o ataque precisa:
 * {@code javascript:}, {@code data:text/html}, e <b>qualquer</b> aspa, {@code <}, {@code >}, crase ou
 * espaço — os caracteres com que se sai de dentro de um atributo HTML.</p>
 *
 * <p><b>Medido antes de ligar:</b> no dump de 18/11 havia <b>zero</b> produtos, logos e banners com
 * aspas, {@code <}, {@code >}, {@code onerror}, {@code script} ou {@code javascript:}. Ou seja: o
 * buraco esteve aberto e <b>não foi usado</b>, e nenhum cliente perde imagem com esta validação —
 * ela é restritiva para o que vem, não para o que está.</p>
 *
 * <h2>Lista de permissão, nunca lista de proibição</h2>
 *
 * <p>A regra diz <b>o que pode</b>. Lista de proibição ({@code "recusar javascript:"}) perde para
 * {@code JaVaScRiPt:}, para {@code java\tscript:}, para a próxima ideia de quem está tentando. Lista
 * de permissão erra para o lado de recusar imagem legítima — que o lojista reclama e a gente
 * conserta — em vez de errar para o lado de executar script no navegador de quem administra tudo.</p>
 */
public final class EnderecoDeImagem {

    private EnderecoDeImagem() {}

    /** Os tipos de imagem embutida que o sistema aceita. Nada de {@code data:text/html}. */
    private static final java.util.Set<String> TIPOS_EMBUTIDOS =
            java.util.Set.of("jpeg", "jpg", "png", "webp", "gif", "avif");

    /**
     * Devolve o endereço como ele deve ser gravado, ou estoura 400.
     *
     * @param valor o que chegou da tela; {@code null} e vazio devolvem {@code null} ("sem imagem")
     * @param campo o nome que o lojista vê na mensagem de erro ("a foto do produto", "o logo"...)
     */
    public static String exigirValido(String valor, String campo) {
        if (valor == null) return null;
        String v = valor.trim();
        if (v.isEmpty()) return null;

        // Primeiro o que vale para qualquer forma: nada que saia de dentro de um atributo HTML.
        // Esta checagem vem ANTES da do esquema de propósito -- um `https://` com aspa no meio
        // tambem tem de cair aqui.
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\'' || c == '<' || c == '>' || c == '`' || c == '\\'
                    || Character.isWhitespace(c) || c < 0x20 || c == 0x7F) {
                throw recusar(campo, "ele tem um caractere que não pode aparecer em endereço de imagem");
            }
        }

        if (v.regionMatches(true, 0, "data:", 0, 5)) {
            return exigirEmbutidaValida(v, campo);
        }
        if (v.regionMatches(true, 0, "https://", 0, 8)) {
            if (v.length() <= "https://".length()) {
                throw recusar(campo, "o endereço https está incompleto");
            }
            return v;
        }
        if (v.startsWith("/")) {
            // Caminho da propria origem. `//outro.site/x` NAO e caminho: e URL de protocolo herdado.
            if (v.startsWith("//")) {
                throw recusar(campo, "endereço começando com // aponta para outro site");
            }
            return v;
        }
        throw recusar(campo, "ele precisa ser uma imagem embutida (data:image/...), um endereço "
                + "https:// ou um caminho do próprio sistema começando com /");
    }

    /** {@code data:image/<tipo>;base64,<conteudo>} e mais nada. */
    private static String exigirEmbutidaValida(String v, String campo) {
        int virgula = v.indexOf(',');
        if (virgula < 0) throw recusar(campo, "a imagem embutida está sem o conteúdo depois da vírgula");

        String cabecalho = v.substring(5, virgula).toLowerCase(java.util.Locale.ROOT); // sem "data:"
        if (!cabecalho.startsWith("image/")) {
            throw recusar(campo, "só imagem embutida é aceita aqui (data:image/...), e este valor "
                    + "declara outro tipo de conteúdo");
        }
        if (!cabecalho.endsWith(";base64")) {
            throw recusar(campo, "a imagem embutida precisa ser base64");
        }
        String tipo = cabecalho.substring("image/".length(), cabecalho.length() - ";base64".length());
        if (!TIPOS_EMBUTIDOS.contains(tipo)) {
            throw recusar(campo, "o formato de imagem \"" + tipo + "\" não é aceito. Use JPEG, PNG, "
                    + "WebP, GIF ou AVIF");
        }
        if (virgula + 1 >= v.length()) {
            throw recusar(campo, "a imagem embutida está vazia");
        }
        // O conteudo base64: a checagem de caractere lá em cima já barrou aspas, `<` e espaço. Aqui
        // sobra garantir que é mesmo alfabeto base64, para não guardar texto arbitrário.
        for (int i = virgula + 1; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=';
            if (!ok) throw recusar(campo, "o conteúdo da imagem embutida não é base64 válido");
        }
        return v;
    }

    private static ResponseStatusException recusar(String campo, String porque) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Não consegui salvar " + campo + ": " + porque + ".");
    }
}
