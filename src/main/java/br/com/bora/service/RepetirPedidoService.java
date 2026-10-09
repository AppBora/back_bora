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
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * "Repetir o ultimo pedido": remonta no carrinho o que o cliente ja pediu.
 *
 * <h2>Por que o link e assinado</h2>
 * <p>O pedido tem o que a pessoa comeu, e os ids sao sequenciais. Um link que aceitasse so
 * {@code ?pedido=105} deixaria qualquer um varrer os numeros e ler o pedido dos outros. A
 * assinatura e conferida <b>aqui dentro</b>, antes de qualquer acesso ao banco: deixar isso no
 * controller ja permitiu que uma mutacao removesse a checagem sem nenhum teste reclamar.</p>
 *
 * <h2>Por que nao reaproveita o ComplementoService.aplicar</h2>
 * <p>Aquele metodo <b>lanca 400</b> quando um complemento nao existe mais ou quando o grupo ficou
 * fora do minimo/maximo — o que e certo na hora de criar o pedido, e errado aqui: o cardapio de
 * hoje nao e o de um mes atras, e o link nao pode quebrar porque a loja mexeu no cardapio. A
 * validacao aqui <b>degrada</b>: o que da para remontar vai para o carrinho, o resto vira aviso.
 * Os limites do grupo saem de {@link ComplementoService#minimoDe} e {@link ComplementoService#maximoDe},
 * para a regra nao divergir entre criar e repetir.</p>
 *
 * <h2>Por que casa por nome quando o id some</h2>
 * <p>{@code ComplementoController.salvar} apaga e recria os complementos do produto a cada
 * gravacao, com ids novos. Casar so por id faria o repetir perder os adicionais na primeira vez que
 * o lojista corrigisse um preco. Como o item do pedido guarda o <b>nome</b>
 * (ver {@link ComplementosDoItem}), da para reencontrar o mesmo adicional depois do cadastro ser
 * refeito.</p>
 *
 * <h2>O que este servico nunca faz</h2>
 * <p>Nao cria pedido e nao cobra nada. Devolve uma sugestao de carrinho; quem confirma e paga e o
 * cliente, na tela do cardapio, como em qualquer pedido.</p>
 */
@Slf4j
@Service
public class RepetirPedidoService {

    /** 12 bytes = 96 bits de assinatura: inquebravel na pratica e cabe numa URL de WhatsApp. */
    private static final int BYTES_DA_ASSINATURA = 12;
    private static final String ALGORITMO = "HmacSHA256";

    private final PedidoRepository pedidos;
    private final PedidoItemRepository itens;
    private final ProdutoRepository produtos;
    private final ComplementoGrupoRepository grupos;
    private final ComplementoItemRepository complementos;
    private final SecretKeySpec chave;

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
        this.chave = new SecretKeySpec(("repetir-pedido|" + segredo).getBytes(StandardCharsets.UTF_8), ALGORITMO);
        if (segredo.startsWith("troque-")) {
            log.warn("ATENCAO: bora.jwt.secret esta no valor padrao do repositorio. "
                    + "Qualquer pessoa consegue forjar o link de repetir pedido.");
        }
    }

    // ---------------------------------------------------------------- o que sai daqui

    public record ItemSugerido(Long produtoId, String nome, int quantidade, List<Long> complementos) {}

    public record CarrinhoSugerido(String codigo, OffsetDateTime feitoEm,
                                   List<ItemSugerido> itens, List<String> avisos) {}

    // ---------------------------------------------------------------- assinatura

    /** Assinatura curta deste pedido, para entrar na URL. */
    public String token(Long lojaId, Long pedidoId) {
        try {
            Mac mac = Mac.getInstance(ALGORITMO); // Mac nao e thread-safe: um por chamada e o certo
            mac.init(chave);
            byte[] h = mac.doFinal((lojaId + ":" + pedidoId).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(java.util.Arrays.copyOf(h, BYTES_DA_ASSINATURA));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("falha ao assinar o link de repeticao", e);
        }
    }

    /** Compara em tempo constante: comparar com equals vaza o tamanho do prefixo correto. */
    public boolean tokenValido(Long lojaId, Long pedidoId, String recebido) {
        if (recebido == null || recebido.isBlank()) return false;
        return MessageDigest.isEqual(token(lojaId, pedidoId).getBytes(StandardCharsets.UTF_8),
                recebido.getBytes(StandardCharsets.UTF_8));
    }

    /** Os links de "repetir" de todos os clientes da loja que tem pedido repetivel, numa consulta. */
    public Map<Long, String> linksDosUltimosPedidos(String base, Long lojaId) {
        Map<Long, String> m = new LinkedHashMap<>();
        for (Object[] linha : pedidos.ultimoPedidoRepetivelPorCliente(lojaId)) {
            Long clienteId = (Long) linha[0], pedidoId = (Long) linha[1];
            if (clienteId == null || pedidoId == null) continue;
            m.put(clienteId, link(base, lojaId, pedidoId));
        }
        return m;
    }

    private String link(String base, Long lojaId, Long pedidoId) {
        return base + "/cardapio.html?loja=" + lojaId + "&repetir=" + pedidoId
                + "&t=" + token(lojaId, pedidoId);
    }

    // ---------------------------------------------------------------- remontagem

    /**
     * O carrinho sugerido a partir de um pedido antigo, se a assinatura conferir.
     *
     * <p>Vazio tanto para assinatura errada quanto para pedido inexistente: quem chama devolve o
     * mesmo 404 nos dois casos, e um 403 separado confirmaria que o pedido existe.</p>
     */
    @Transactional(readOnly = true)
    public Optional<CarrinhoSugerido> abrir(Long lojaId, Long pedidoId, String token) {
        if (!tokenValido(lojaId, pedidoId, token)) return Optional.empty();
        return pedidos.findByIdAndLojaId(pedidoId, lojaId).map(p -> montar(lojaId, p));
    }

    private CarrinhoSugerido montar(Long lojaId, Pedido pedido) {
        List<PedidoItem> linhas = itens.findByLojaIdAndPedidoIdOrderById(lojaId, pedido.id);
        Cardapio cardapio = cardapioDe(lojaId, linhas);

        List<ItemSugerido> carrinho = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        for (PedidoItem li : linhas) remontarLinha(lojaId, li, cardapio, carrinho, avisos);

        return new CarrinhoSugerido(pedido.codigo == null ? String.valueOf(pedido.id) : pedido.codigo,
                pedido.criadoEm, carrinho, avisos);
    }

    private void remontarLinha(Long lojaId, PedidoItem li, Cardapio cardapio,
                               List<ItemSugerido> carrinho, List<String> avisos) {
        String descricao = li.getDescricao() == null ? "Item" : li.getDescricao();
        if (li.getProdutoId() == null) {
            // Pedido de marketplace: os itens nao guardam produto_id, entao nao ha o que remontar.
            avisos.add(nomeCurto(descricao) + " veio de aplicativo e não pode ser repetido por aqui");
            return;
        }
        Produto prod = cardapio.produtos.get(li.getProdutoId());
        if (prod == null || !Boolean.TRUE.equals(prod.ativo)) {
            avisos.add(nomeCurto(descricao) + " saiu do cardápio");
            return;
        }

        List<ComplementoService.Escolhido> guardados = ComplementosDoItem.ler(li.getComplementos());
        List<ComplementoGrupo> gs = cardapio.gruposDe(prod.id);

        if (guardados == null) {
            // Item criado antes de o pedido registrar os complementos. Nao se sabe o que foi
            // escolhido — e um produto com grupo nenhum nao tinha o que escolher.
            if (!gs.isEmpty()) {
                avisos.add(prod.nome + ": escolha os adicionais de novo, esse pedido é antigo");
                return;
            }
            carrinho.add(new ItemSugerido(prod.id, prod.nome, quantidadeDe(li), List.of()));
            return;
        }

        Reencontro r = reencontrar(guardados, gs, cardapio);
        if (r.faltouObrigatorio) {
            avisos.add(prod.nome + ": escolha os adicionais de novo, o cardápio mudou");
            return;
        }
        if (!r.sumiram.isEmpty()) {
            avisos.add(prod.nome + ": " + String.join(", ", r.sumiram)
                    + (r.sumiram.size() == 1 ? " não está mais no cardápio" : " não estão mais no cardápio"));
        }

        BigDecimal agora = (prod.preco == null ? BigDecimal.ZERO : prod.preco).add(r.acrescimo);
        BigDecimal pago = li.getPrecoUnitario();
        // So avisa de preco quando NADA sumiu: se um adicional saiu, o valor muda por causa disso,
        // e dizer "mudou de preco" junto seria culpar o reajuste por uma coisa que nao houve.
        if (r.sumiram.isEmpty() && pago != null && pago.compareTo(agora) != 0) {
            avisos.add(prod.nome + " mudou de preço");
        }

        carrinho.add(new ItemSugerido(prod.id, prod.nome, quantidadeDe(li), r.ids));
    }

    private static int quantidadeDe(PedidoItem li) {
        Integer q = li.getQuantidade();
        return q == null || q < 1 ? 1 : q;
    }

    // ---------------------------------------------------------------- cardapio de hoje, em lote

    /** O que o cardapio de hoje tem dos produtos deste pedido. Tres consultas, nao tres por item. */
    private record Cardapio(Map<Long, Produto> produtos,
                            Map<Long, List<ComplementoGrupo>> gruposPorProduto,
                            Map<Long, ComplementoItem> itensPorId,
                            Map<Long, Map<String, ComplementoItem>> itensPorNome) {
        List<ComplementoGrupo> gruposDe(Long produtoId) {
            return gruposPorProduto.getOrDefault(produtoId, List.of());
        }
    }

    private Cardapio cardapioDe(Long lojaId, List<PedidoItem> linhas) {
        List<Long> produtoIds = linhas.stream().map(PedidoItem::getProdutoId).filter(java.util.Objects::nonNull).distinct().toList();
        if (produtoIds.isEmpty()) return new Cardapio(Map.of(), Map.of(), Map.of(), Map.of());

        Map<Long, Produto> porId = new HashMap<>();
        produtos.findByLojaIdAndIdIn(lojaId, produtoIds).forEach(p -> porId.put(p.id, p));

        Map<Long, List<ComplementoGrupo>> porProduto = new HashMap<>();
        List<ComplementoGrupo> todosGrupos = grupos.findByLojaIdAndProdutoIdInOrderById(lojaId, produtoIds);
        todosGrupos.forEach(g -> porProduto.computeIfAbsent(g.produtoId, k -> new ArrayList<>()).add(g));

        Map<Long, ComplementoItem> porItemId = new HashMap<>();
        Map<Long, Map<String, ComplementoItem>> porNome = new HashMap<>();
        if (!todosGrupos.isEmpty()) {
            complementos.findByLojaIdAndGrupoIdInOrderById(lojaId, todosGrupos.stream().map(g -> g.id).toList())
                    .forEach(ci -> {
                        porItemId.put(ci.id, ci);
                        porNome.computeIfAbsent(ci.grupoId, k -> new HashMap<>()).put(chave(ci.nome), ci);
                    });
        }
        return new Cardapio(porId, porProduto, porItemId, porNome);
    }

    /** Nome normalizado, para "Leite Ninho" e "leite ninho " serem o mesmo adicional. */
    private static String chave(String nome) {
        return nome == null ? "" : nome.trim().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- reencontrar os adicionais

    private record Reencontro(List<Long> ids, BigDecimal acrescimo, List<String> sumiram, boolean faltouObrigatorio) {}

    /**
     * Procura no cardapio de hoje cada adicional que o pedido guardou: primeiro pelo id, depois pelo
     * nome dentro do mesmo grupo. O segundo passo e o que faz o repetir sobreviver a uma regravacao
     * do cardapio, que troca todos os ids.
     */
    private Reencontro reencontrar(List<ComplementoService.Escolhido> guardados,
                                   List<ComplementoGrupo> gs, Cardapio cardapio) {
        if (gs.isEmpty()) {
            // O produto nao tem mais grupo nenhum: nao ha onde encaixar o que foi escolhido, mas
            // isso nao impede repetir o produto.
            List<String> sumiram = guardados.stream().map(ComplementoService.Escolhido::nome).toList();
            return new Reencontro(List.of(), BigDecimal.ZERO, sumiram, false);
        }

        List<Long> ids = new ArrayList<>();
        List<String> sumiram = new ArrayList<>();
        Map<Long, Integer> porGrupo = new HashMap<>();
        BigDecimal acrescimo = BigDecimal.ZERO;

        for (ComplementoService.Escolhido g : guardados) {
            ComplementoItem achado = cardapio.itensPorId.get(g.id());
            if (achado == null || !pertence(achado, gs)) achado = porNomeNosGrupos(g.nome(), gs, cardapio);
            if (achado == null) { sumiram.add(g.nome()); continue; }
            ids.add(achado.id);
            porGrupo.merge(achado.grupoId, 1, Integer::sum);
            acrescimo = acrescimo.add(achado.preco == null ? BigDecimal.ZERO : achado.preco);
        }

        for (ComplementoGrupo grupo : gs) {
            int tem = porGrupo.getOrDefault(grupo.id, 0);
            // Abaixo do minimo: faltou algo obrigatorio. Acima do maximo: a loja apertou a regra
            // depois. Nos dois casos o cliente escolhe de novo, em vez de a gente escolher por ele.
            if (tem < ComplementoService.minimoDe(grupo) || tem > ComplementoService.maximoDe(grupo)) {
                return new Reencontro(List.of(), BigDecimal.ZERO, sumiram, true);
            }
        }
        return new Reencontro(ids, acrescimo, sumiram, false);
    }

    private static boolean pertence(ComplementoItem ci, List<ComplementoGrupo> gs) {
        return gs.stream().anyMatch(g -> g.id.equals(ci.grupoId));
    }

    private static ComplementoItem porNomeNosGrupos(String nome, List<ComplementoGrupo> gs, Cardapio cardapio) {
        for (ComplementoGrupo g : gs) {
            ComplementoItem ci = cardapio.itensPorNome.getOrDefault(g.id, Map.of()).get(chave(nome));
            if (ci != null) return ci;
        }
        return null;
    }

    /** "Copo 500ml (Granola)" -> "Copo 500ml": o aviso fala do produto, nao da escolha antiga. */
    private static String nomeCurto(String descricao) {
        int i = descricao.indexOf(" (");
        return i > 0 ? descricao.substring(0, i) : descricao;
    }
}
