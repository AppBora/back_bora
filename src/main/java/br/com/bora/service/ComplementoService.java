package br.com.bora.service;

import br.com.bora.entity.ComplementoGrupo;
import br.com.bora.entity.ComplementoItem;
import br.com.bora.entity.Produto;
import br.com.bora.repository.ComplementoGrupoRepository;
import br.com.bora.repository.ComplementoItemRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Regra dos complementos, compartilhada pelos dois canais que criam pedido.
 *
 * <p>Antes ela existia só no cardápio público: o painel aceitava pedido de produto com grupo
 * obrigatório sem escolha nenhuma, salvando o item sem os adicionais e com preço abaixo do real —
 * numa açaiteria, onde metade do valor está nos adicionais, isso é prejuízo silencioso.</p>
 */
@Service
public class ComplementoService {

    private final ComplementoGrupoRepository grupos;
    private final ComplementoItemRepository itens;

    public ComplementoService(ComplementoGrupoRepository grupos, ComplementoItemRepository itens) {
        this.grupos = grupos;
        this.itens = itens;
    }

    /**
     * Um complemento escolhido, com o nome e o preço <b>do momento do pedido</b>.
     *
     * <p>O nome vai junto porque o id não sobrevive: {@code ComplementoController.salvar} apaga e
     * recria os grupos e itens do produto a cada gravação, com ids novos da sequência. Guardar só o
     * id faria o histórico apontar para o nada na primeira vez que o lojista corrigisse um preço.</p>
     */
    public record Escolhido(Long id, String nome, BigDecimal preco) {}

    /** Acréscimo de preço e complementos escolhidos, já validados contra os grupos. */
    public record Escolha(BigDecimal acrescimo, List<Escolhido> escolhidos) {
        public List<String> nomes() { return escolhidos.stream().map(Escolhido::nome).toList(); }

        public String descricao(String nomeProduto) {
            return escolhidos.isEmpty() ? nomeProduto
                    : nomeProduto + " (" + String.join(", ", nomes()) + ")";
        }
    }

    /**
     * Os padrões do grupo quando o cadastro não diz: nada obrigatório, uma escolha no máximo.
     *
     * <p>Moram aqui porque quem repete um pedido antigo precisa aplicar exatamente a mesma régua.
     * Enquanto estavam copiados nos dois lugares, mudar um e esquecer o outro faria o carrinho
     * remontado aceitar o que o pedido recusaria no fim.</p>
     */
    public static int minimoDe(ComplementoGrupo g) { return g.minimo == null ? 0 : g.minimo; }

    public static int maximoDe(ComplementoGrupo g) { return g.maximo == null ? 1 : g.maximo; }

    /**
     * Valida a escolha do cliente contra os grupos do produto e devolve o que somar ao preço.
     *
     * @throws ResponseStatusException 400 quando o complemento não é do produto, ou quando a
     *         quantidade escolhida fica fora do mínimo/máximo do grupo.
     */
    public Escolha aplicar(Long lojaId, Produto produto, List<Long> escolhidos) {
        List<ComplementoGrupo> gs = grupos.findByLojaIdAndProdutoIdOrderById(lojaId, produto.id);
        List<Long> ids = escolhidos == null ? List.of() : escolhidos;

        if (gs.isEmpty()) {
            if (!ids.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        produto.nome + " não tem complementos");
            }
            return new Escolha(BigDecimal.ZERO, List.of());
        }

        Map<Long, ComplementoItem> catalogo = new HashMap<>();
        itens.findByLojaIdAndGrupoIdInOrderById(lojaId, gs.stream().map(g -> g.id).toList())
                .forEach(ci -> catalogo.put(ci.id, ci));

        Map<Long, List<ComplementoItem>> porGrupo = new HashMap<>();
        for (Long id : ids) {
            ComplementoItem ci = catalogo.get(id);
            if (ci == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Complemento inválido em " + produto.nome);
            }
            porGrupo.computeIfAbsent(ci.grupoId, k -> new ArrayList<>()).add(ci);
        }

        BigDecimal acrescimo = BigDecimal.ZERO;
        List<Escolhido> resultado = new ArrayList<>();
        for (ComplementoGrupo g : gs) {
            List<ComplementoItem> sel = porGrupo.getOrDefault(g.id, List.of());
            int min = minimoDe(g);
            int max = maximoDe(g);
            if (sel.size() < min || sel.size() > max) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Escolha entre " + min + " e " + max + " em \"" + g.nome + "\" de " + produto.nome);
            }
            for (ComplementoItem ci : sel) {
                BigDecimal preco = ci.preco == null ? BigDecimal.ZERO : ci.preco;
                acrescimo = acrescimo.add(preco);
                resultado.add(new Escolhido(ci.id, ci.nome, preco));
            }
        }
        return new Escolha(acrescimo, resultado);
    }
}
