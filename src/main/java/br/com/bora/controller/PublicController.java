package br.com.bora.controller;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.Produto;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.service.PixService;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Endpoints públicos (sem autenticação) — cardápio digital por QR Code + pedido online com PIX. */
@RestController
@RequestMapping("/public")
public class PublicController {

    private final LojaRepository lojas;
    private final ProdutoRepository produtos;
    private final PedidoRepository pedidos;
    private final PedidoItemRepository itens;
    private final IntegracaoCanalRepository integracoes;
    private final PixService pix;
    private final br.com.bora.repository.ComplementoGrupoRepository compGrupos;
    private final br.com.bora.repository.ComplementoItemRepository compItens;
    private final br.com.bora.repository.CupomRepository cupons;

    public PublicController(LojaRepository lojas, ProdutoRepository produtos, PedidoRepository pedidos,
                            PedidoItemRepository itens, IntegracaoCanalRepository integracoes, PixService pix,
                            br.com.bora.repository.ComplementoGrupoRepository compGrupos,
                            br.com.bora.repository.ComplementoItemRepository compItens,
                            br.com.bora.repository.CupomRepository cupons) {
        this.lojas = lojas;
        this.produtos = produtos;
        this.pedidos = pedidos;
        this.itens = itens;
        this.integracoes = integracoes;
        this.pix = pix;
        this.compGrupos = compGrupos;
        this.compItens = compItens;
        this.cupons = cupons;
    }

    /** Manifesto PWA da loja: o cliente instala o "app" com o nome/cara da loja (white-label). */
    @GetMapping(value = "/loja/{lojaId}/manifest", produces = "application/manifest+json")
    public Map<String, Object> manifest(@PathVariable Long lojaId) {
        Loja loja = lojaAtiva(lojaId);
        String nome = loja.nome == null ? "Cardápio" : loja.nome;
        return Map.of(
                "name", nome + " · Pedidos",
                "short_name", nome.length() > 12 ? nome.substring(0, 12) : nome,
                "start_url", "/cardapio.html?loja=" + lojaId,
                "scope", "/",
                "display", "standalone",
                "background_color", "#f6f7fb",
                "theme_color", "#7c3aed",
                "icons", List.of(Map.of(
                        "src", "/assets/img/icone-bora.svg",
                        "sizes", "any",
                        "type", "image/svg+xml",
                        "purpose", "any")));
    }

    /** Valida um cupom para exibir o desconto no checkout (não reserva nada). */
    @GetMapping("/loja/{lojaId}/cupom/{codigo}")
    public Map<String, Object> validarCupom(@PathVariable Long lojaId, @PathVariable String codigo) {
        lojaAtiva(lojaId);
        br.com.bora.entity.Cupom c = cupons.findByLojaIdAndCodigoIgnoreCase(lojaId, codigo.trim())
                .filter(br.com.bora.entity.Cupom::valido)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cupom inválido ou vencido"));
        return Map.of("codigo", c.codigo, "tipo", c.tipo, "valor", c.valor);
    }

    /** Cardápio público de uma loja: nome + produtos ativos (somente leitura). */
    @GetMapping("/loja/{lojaId}/cardapio")
    public Map<String, Object> cardapio(@PathVariable Long lojaId) {
        Loja loja = lojaAtiva(lojaId);
        List<Produto> lista = produtos.findByLojaIdAndAtivoTrueOrderByCategoriaAscNomeAsc(lojaId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("loja", Map.of("id", loja.id, "nome", loja.nome == null ? "Cardápio" : loja.nome));
        resp.put("pixDisponivel", integracaoPix(lojaId).isPresent());
        // complementos por produto (1 consulta para grupos + 1 para itens)
        List<br.com.bora.entity.ComplementoGrupo> gs = lista.isEmpty() ? List.of()
                : compGrupos.findByLojaIdAndProdutoIdInOrderById(lojaId, lista.stream().map(p -> p.id).toList());
        Map<Long, List<Map<String, Object>>> itensPorGrupo = new java.util.HashMap<>();
        if (!gs.isEmpty()) {
            compItens.findByLojaIdAndGrupoIdInOrderById(lojaId, gs.stream().map(g -> g.id).toList()).forEach(i -> {
                Map<String, Object> mi = new LinkedHashMap<>();
                mi.put("id", i.id); mi.put("nome", i.nome); mi.put("preco", i.preco);
                itensPorGrupo.computeIfAbsent(i.grupoId, k -> new java.util.ArrayList<>()).add(mi);
            });
        }
        Map<Long, List<Map<String, Object>>> gruposPorProduto = new java.util.HashMap<>();
        for (br.com.bora.entity.ComplementoGrupo g : gs) {
            Map<String, Object> mg = new LinkedHashMap<>();
            mg.put("id", g.id); mg.put("nome", g.nome); mg.put("minimo", g.minimo); mg.put("maximo", g.maximo);
            mg.put("itens", itensPorGrupo.getOrDefault(g.id, List.of()));
            gruposPorProduto.computeIfAbsent(g.produtoId, k -> new java.util.ArrayList<>()).add(mg);
        }
        resp.put("produtos", lista.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id);
            m.put("nome", p.nome);
            m.put("categoria", p.categoria == null || p.categoria.isBlank() ? "Outros" : p.categoria);
            m.put("preco", p.preco);
            m.put("imagem", p.imagemUrl);
            m.put("complementos", gruposPorProduto.getOrDefault(p.id, List.of()));
            return m;
        }).toList());
        return resp;
    }

    /**
     * Pedido online do cardápio digital. Preço SEMPRE recalculado no servidor.
     * formaPagamento "PIX" cria a cobrança na conta Asaas do lojista e devolve o QR Code.
     */
    @PostMapping("/loja/{lojaId}/pedido")
    @Transactional
    public Map<String, Object> pedirOnline(@PathVariable Long lojaId, @RequestBody Map<String, Object> body) {
        Loja loja = lojaAtiva(lojaId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pedidoItens = (List<Map<String, Object>>) body.get("itens");
        if (pedidoItens == null || pedidoItens.isEmpty() || pedidoItens.size() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Itens do pedido inválidos");
        }
        String nome = str(body.get("clienteNome"));
        String telefone = str(body.get("telefone"));
        String endereco = str(body.get("endereco"));
        String obs = str(body.get("observacao"));
        String forma = "PIX".equalsIgnoreCase(str(body.get("formaPagamento"))) ? "PIX" : "Na entrega";
        String cpf = str(body.get("cpf"));
        if (nome == null || nome.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe seu nome");
        }

        Pedido p = new Pedido();
        p.lojaId = lojaId;
        p.origem = "Cardápio Digital";
        p.formaPagamento = "PIX".equals(forma) ? "PIX (aguardando)" : "Na entrega";
        if (telefone != null && !telefone.isBlank()) p.clienteTelefone = telefone.replaceAll("\\D", "");
        StringBuilder ob = new StringBuilder("Cliente: ").append(nome.trim());
        if (telefone != null && !telefone.isBlank()) ob.append(" | Tel: ").append(telefone.trim());
        if (endereco != null && !endereco.isBlank()) ob.append(" | End: ").append(endereco.trim());
        if (obs != null && !obs.isBlank()) ob.append(" | Obs: ").append(obs.trim());
        p.observacao = ob.toString();

        BigDecimal total = BigDecimal.ZERO;
        p.valorTotal = BigDecimal.ZERO;
        p = pedidos.save(p);
        for (Map<String, Object> it : pedidoItens) {
            Long produtoId = Long.valueOf(String.valueOf(it.get("produtoId")));
            int qtd = Integer.parseInt(String.valueOf(it.getOrDefault("quantidade", 1)));
            if (qtd < 1 || qtd > 99) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantidade inválida");
            Produto prod = produtos.findById(produtoId)
                    .filter(x -> lojaId.equals(x.lojaId) && Boolean.TRUE.equals(x.ativo))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Produto indisponível"));

            // Complementos escolhidos: valida posse, min/max por grupo e soma no preço unitário.
            List<br.com.bora.entity.ComplementoGrupo> gs = compGrupos.findByLojaIdAndProdutoIdOrderById(lojaId, prod.id);
            List<Long> escolhidos = new java.util.ArrayList<>();
            Object escRaw = it.get("complementos");
            if (escRaw instanceof List<?> ls) for (Object o : ls) { try { escolhidos.add(Long.valueOf(String.valueOf(o))); } catch (Exception e) {} }
            BigDecimal extra = BigDecimal.ZERO;
            StringBuilder nomeItem = new StringBuilder(prod.nome);
            if (!gs.isEmpty()) {
                Map<Long, br.com.bora.entity.ComplementoItem> catalogo = new java.util.HashMap<>();
                compItens.findByLojaIdAndGrupoIdInOrderById(lojaId, gs.stream().map(g -> g.id).toList())
                        .forEach(ci -> catalogo.put(ci.id, ci));
                Map<Long, List<br.com.bora.entity.ComplementoItem>> porGrupo = new java.util.HashMap<>();
                for (Long idEsc : escolhidos) {
                    br.com.bora.entity.ComplementoItem ci = catalogo.get(idEsc);
                    if (ci == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Complemento inválido");
                    porGrupo.computeIfAbsent(ci.grupoId, k -> new java.util.ArrayList<>()).add(ci);
                }
                List<String> partes = new java.util.ArrayList<>();
                for (br.com.bora.entity.ComplementoGrupo g : gs) {
                    List<br.com.bora.entity.ComplementoItem> sel = porGrupo.getOrDefault(g.id, List.of());
                    if (sel.size() < g.minimo || sel.size() > g.maximo) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Escolha entre " + g.minimo + " e " + g.maximo + " em \"" + g.nome + "\" de " + prod.nome);
                    }
                    for (br.com.bora.entity.ComplementoItem ci : sel) {
                        extra = extra.add(ci.preco == null ? BigDecimal.ZERO : ci.preco);
                        partes.add(ci.nome);
                    }
                }
                if (!partes.isEmpty()) nomeItem.append(" (").append(String.join(", ", partes)).append(")");
            } else if (!escolhidos.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Produto não tem complementos");
            }

            PedidoItem item = new PedidoItem();
            item.setLojaId(lojaId);
            item.setPedidoId(p.id);
            item.setProdutoId(prod.id);
            item.setDescricao(nomeItem.toString());
            item.setQuantidade(qtd);
            BigDecimal unit = (prod.preco == null ? BigDecimal.ZERO : prod.preco).add(extra);
            item.setPrecoUnitario(unit);
            item.setCustoUnitario(prod.custo);
            BigDecimal sub = unit.multiply(BigDecimal.valueOf(qtd));
            item.setSubtotal(sub);
            itens.save(item);
            total = total.add(sub);
        }
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pedido sem valor");
        }

        // Cupom de desconto (recalculado no servidor; nunca zera o pedido)
        BigDecimal descontoAplicado = BigDecimal.ZERO;
        String codCupom = str(body.get("cupom"));
        if (codCupom != null && !codCupom.isBlank()) {
            br.com.bora.entity.Cupom c = cupons.findByLojaIdAndCodigoIgnoreCase(lojaId, codCupom.trim())
                    .filter(br.com.bora.entity.Cupom::valido)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cupom inválido ou vencido"));
            descontoAplicado = c.desconto(total);
            if (descontoAplicado.compareTo(total) >= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O cupom não pode zerar o pedido");
            }
            total = total.subtract(descontoAplicado);
            p.observacao = p.observacao + " | Cupom " + c.codigo + " (-R$ " + descontoAplicado + ")";
        }

        p.valorTotal = total;
        p.codigo = "CD-" + p.id;

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("pedidoId", p.id);
        resp.put("codigo", p.codigo);
        resp.put("valorTotal", total);
        resp.put("desconto", descontoAplicado);

        if ("PIX".equals(forma)) {
            IntegracaoCanal integ = integracaoPix(lojaId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Esta loja não aceita PIX online"));
            if (cpf == null || cpf.replaceAll("\\D", "").length() < 11) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe um CPF válido para pagar com PIX");
            }
            try {
                Map<String, Object> cobranca = pix.criarCobranca(integ, loja, p, nome, cpf.replaceAll("\\D", ""));
                p.canalExterno = "PIX_ASAAS";
                p.idExterno = (String) cobranca.get("paymentId");
                resp.put("pix", cobranca);
            } catch (ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Falha ao gerar o PIX: " + e.getMessage());
            }
        }
        p.atualizadoEm = OffsetDateTime.now();
        pedidos.save(p);
        return resp;
    }

    /** Status público do pedido (para a tela de acompanhamento do QR PIX). */
    @GetMapping("/loja/{lojaId}/pedido/{pedidoId}/status")
    public Map<String, Object> statusPedido(@PathVariable Long lojaId, @PathVariable Long pedidoId) {
        Pedido p = pedidos.findByIdAndLojaId(pedidoId, lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codigo", p.codigo == null ? String.valueOf(p.id) : p.codigo);
        m.put("status", p.status.name());
        m.put("pago", "PIX (pago)".equals(p.formaPagamento));
        m.put("loja", lojas.findById(lojaId).map(l -> l.nome).orElse(""));
        m.put("criadoEm", p.criadoEm);
        m.put("motivoCancelamento", p.motivoCancelamento);
        return m;
    }

    /** Webhook do Asaas DO LOJISTA: confirma o pagamento PIX do pedido. */
    @PostMapping("/pix-webhook/{lojaId}")
    @Transactional
    public Map<String, String> pixWebhook(@PathVariable Long lojaId,
                                          @RequestHeader(value = "asaas-access-token", required = false) String token,
                                          @RequestBody Map<String, Object> body) {
        IntegracaoCanal integ = integracoes.findByLojaIdAndCanal(lojaId, "PIX")
                .filter(i -> token != null && token.equals(i.webhookToken))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token inválido"));
        String event = str(body.get("event"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payment = (Map<String, Object>) body.get("payment");
        String paymentId = payment == null ? null : str(payment.get("id"));
        if (paymentId != null && ("PAYMENT_RECEIVED".equals(event) || "PAYMENT_CONFIRMED".equals(event))) {
            pedidos.findFirstByLojaIdAndCanalExternoAndIdExterno(lojaId, "PIX_ASAAS", paymentId).ifPresent(p -> {
                p.formaPagamento = "PIX (pago)";
                p.atualizadoEm = OffsetDateTime.now();
                pedidos.save(p);
            });
            integ.ultimaSync = OffsetDateTime.now();
            integracoes.save(integ);
        }
        return Map.of("received", "true");
    }

    private Loja lojaAtiva(Long lojaId) {
        Loja loja = lojas.findById(lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
        if (Boolean.FALSE.equals(loja.ativo)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja indisponível");
        }
        return loja;
    }

    private java.util.Optional<IntegracaoCanal> integracaoPix(Long lojaId) {
        return integracoes.findByLojaIdAndCanal(lojaId, "PIX")
                .filter(i -> Boolean.TRUE.equals(i.ativo) && i.clientSecret != null && !i.clientSecret.isBlank());
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
}
