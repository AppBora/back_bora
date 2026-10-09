package br.com.bora.service;

import br.com.bora.entity.ComplementoGrupo;
import br.com.bora.entity.ComplementoItem;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.Produto;
import br.com.bora.repository.ComplementoGrupoRepository;
import br.com.bora.repository.ComplementoItemRepository;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.repository.ProdutoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * "Repetir o ultimo pedido": remonta no carrinho o que o cliente ja pediu.
 *
 * <h2>Por que o link e assinado</h2>
 * <p>O pedido tem o que a pessoa comeu, e os ids sao sequenciais. Um link que aceitasse so
 * {@code ?pedido=105} deixaria qualquer um varrer os numeros e ler o pedido dos outros. Por isso o
 * link leva uma assinatura derivada do segredo do servidor: sem ela, 404.</p>
 *
 * <h2>Por que nao reaproveita o ComplementoService.aplicar</h2>
 * <p>Aquele metodo <b>lanca 400</b> quando um complemento nao existe mais ou quando o grupo ficou
 * fora do minimo/maximo — o que e certo na hora de criar o pedido, e errado aqui: o cardapio de
 * hoje nao e o de um mes atras, e o link nao pode quebrar porque a loja mexeu no cardapio. Aqui a
 * validacao e por fora e <b>degrada</b>: o que da para remontar vai para o carrinho, o resto vira
 * aviso na tela.</p>
 *
 * <h2>O que este servico nunca faz</h2>
 * <p>Nao cria pedido e nao cobra nada. Ele devolve uma sugestao de carrinho; quem confirma e paga e
 * o cliente, na tela do cardapio, como em qualquer pedido.</p>
 */
@Slf4j
@Service
public class RepetirPedidoService {

    private final PedidoRepository pedidos;
    private final PedidoItemRepository itens;
    private final ProdutoRepository produtos;
    private final ComplementoGrupoRepository grupos;
    private final ComplementoItemRepository complementos;
    private final byte[] chave;

    public RepetirPedidoService(PedidoRepository pedidos, PedidoItemRepository itens,
                                ProdutoRepository produtos, ComplementoGrupoRepository grupos,
                                ComplementoItemRepository complementos,
                                @Value("${bora.jwt.secret:troque-este-segredo-em-producao-com-no-minimo-32-bytes!!}") String segredo) {
        this.pedidos = pedidos;
        this.itens = itens;
        this.produtos = produtos;
        this.grupos = grupos;
        this.complementos = complementos;
        // Deriva uma chave propria do segredo do servidor. O prefixo separa o uso: assinatura de
        // link de repeticao nao e token de sessao, e nao deve valer uma pela outra.
        this.chave = ("repetir-pedido|" + segredo).getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- assinatura

    /** Assinatura curta deste pedido, para entrar na URL. */
    public String token(Long lojaId, Long pedidoId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(chave, "HmacSHA256"));
            byte[] h = mac.doFinal((lojaId + ":" + pedidoId).getBytes(StandardCharsets.UTF_8));
            // 12 bytes = 96 bits de assinatura: inquebravel na pratica e cabe numa URL de WhatsApp.
            return java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(java.util.Arrays.copyOf(h, 12));
        } catch (Exception e) {
            throw new IllegalStateException("falha ao assinar o link de repeticao", e);
        }
    }

    /** Compara em tempo constante: comparar com equals vaza o tamanho do prefixo correto. */
    public boolean tokenValido(Long lojaId, Long pedidoId, String recebido) {
        if (recebido == null || recebido.isBlank()) return false;
        return MessageDigest.isEqual(token(lojaId, pedidoId).getBytes(StandardCharsets.UTF_8),
                recebido.getBytes(StandardCharsets.UTF_8));
    }

    /** O link que a loja manda para o cliente, ou vazio se o cliente nunca pediu nesta loja. */
    public Optional<String> linkDoUltimoPedido(String base, Long lojaId, Long clienteId) {
        if (clienteId == null) return Optional.empty();
        return pedidos.findFirstByLojaIdAndClienteIdOrderByCriadoEmDesc(lojaId, clienteId)
                .map(p -> base + "/cardapio.html?loja=" + lojaId + "&repetir=" + p.id + "&t=" + token(lojaId, p.id));
    }

    // ---------------------------------------------------------------- remontagem

    /**
     * O carrinho sugerido a partir de um pedido antigo, mais o que mudou desde entao.
     *
     * <p>Cada item volta com {@code produtoId}, {@code quantidade} e os complementos que ainda
     * existem. Item que nao da para remontar sozinho nao entra no carrinho: entra na lista de
     * avisos, para o cliente escolher na mao em vez de receber algo diferente do que pediu.</p>
     */
    public Map<String, Object> montar(Long lojaId, Long pedidoId) {
        Pedido pedido = pedidos.findByIdAndLojaId(pedidoId, lojaId).orElse(null);
        if (pedido == null) return null;

        List<PedidoItem> linhas = itens.findByLojaIdAndPedidoIdOrderById(lojaId, pedidoId);
        List<Map<String, Object>> carrinho = new ArrayList<>();
        List<String> avisos = new ArrayList<>();

        for (PedidoItem li : linhas) {
            String nome = li.getDescricao() == null ? "Item" : li.getDescricao();
            if (li.getProdutoId() == null) {
                avisos.add(nome + " não pode ser repetido automaticamente");
                continue;
            }
            Produto prod = produtos.findByIdAndLojaId(li.getProdutoId(), lojaId).orElse(null);
            if (prod == null || !Boolean.TRUE.equals(prod.ativo)) {
                avisos.add(nomeCurto(nome) + " saiu do cardápio");
                continue;
            }

            Resultado r = complementosQueAindaValem(lojaId, prod, li.getComplementos());
            if (r.precisaEscolherDeNovo) {
                avisos.add(prod.nome + ": escolha os adicionais de novo, o cardápio mudou");
                continue;
            }
            if (r.removidos > 0) {
                avisos.add(prod.nome + ": " + r.removidos + " adicional(is) não existe(m) mais");
            }

            BigDecimal agora = (prod.preco == null ? BigDecimal.ZERO : prod.preco).add(r.acrescimo);
            if (li.getPrecoUnitario() != null && li.getPrecoUnitario().compareTo(agora) != 0) {
                avisos.add(prod.nome + " mudou de preço");
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("produtoId", prod.id);
            item.put("nome", prod.nome);
            item.put("quantidade", li.getQuantidade() == null ? 1 : li.getQuantidade());
            item.put("complementos", r.ids);
            carrinho.add(item);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("codigo", pedido.codigo == null ? String.valueOf(pedido.id) : pedido.codigo);
        resp.put("feitoEm", pedido.criadoEm);
        resp.put("itens", carrinho);
        resp.put("avisos", avisos);
        return resp;
    }

    private record Resultado(List<Long> ids, BigDecimal acrescimo, int removidos, boolean precisaEscolherDeNovo) {}

    /**
     * Filtra os complementos salvos contra o cardapio de hoje.
     *
     * <p>Marca {@code precisaEscolherDeNovo} quando o que sobrou nao satisfaz mais o minimo de um
     * grupo obrigatorio — por exemplo, o unico tamanho que o cliente escolhia foi removido. Nesse
     * caso remontar daria um item invalido, que so estouraria no fim, na hora de fechar o pedido.</p>
     */
    private Resultado complementosQueAindaValem(Long lojaId, Produto prod, String salvos) {
        List<ComplementoGrupo> gs = grupos.findByLojaIdAndProdutoIdOrderById(lojaId, prod.id);

        List<Long> pedidos = new ArrayList<>();
        if (salvos != null && !salvos.isBlank()) {
            for (String p : salvos.split(",")) {
                try { pedidos.add(Long.valueOf(p.trim())); } catch (NumberFormatException ignored) { }
            }
        }

        if (gs.isEmpty()) {
            // Produto sem complementos hoje. Se o pedido antigo tinha algum, ele simplesmente nao
            // existe mais — nao e motivo para bloquear a repeticao do produto.
            return new Resultado(List.of(), BigDecimal.ZERO, pedidos.size(), false);
        }

        Map<Long, ComplementoItem> catalogo = new HashMap<>();
        complementos.findByLojaIdAndGrupoIdInOrderById(lojaId, gs.stream().map(g -> g.id).toList())
                .forEach(ci -> catalogo.put(ci.id, ci));

        List<Long> validos = new ArrayList<>();
        Map<Long, Integer> porGrupo = new HashMap<>();
        BigDecimal acrescimo = BigDecimal.ZERO;
        int removidos = 0;
        Set<Long> jaVistos = new HashSet<>();
        for (Long id : pedidos) {
            ComplementoItem ci = catalogo.get(id);
            if (ci == null || !jaVistos.add(id)) { removidos++; continue; }
            validos.add(id);
            porGrupo.merge(ci.grupoId, 1, Integer::sum);
            acrescimo = acrescimo.add(ci.preco == null ? BigDecimal.ZERO : ci.preco);
        }

        for (ComplementoGrupo g : gs) {
            int min = g.minimo == null ? 0 : g.minimo;
            int max = g.maximo == null ? 1 : g.maximo;
            int tem = porGrupo.getOrDefault(g.id, 0);
            // Abaixo do minimo: faltou algo obrigatorio. Acima do maximo: a loja apertou a regra
            // depois. Nos dois casos o cliente tem que escolher de novo, nao a gente por ele.
            if (tem < min || tem > max) return new Resultado(List.of(), BigDecimal.ZERO, removidos, true);
        }

        return new Resultado(validos, acrescimo, removidos, false);
    }

    /** "Copo 500ml (Granola)" -> "Copo 500ml": o aviso fala do produto, nao da escolha antiga. */
    private String nomeCurto(String descricao) {
        int i = descricao.indexOf(" (");
        return i > 0 ? descricao.substring(0, i) : descricao;
    }
}
