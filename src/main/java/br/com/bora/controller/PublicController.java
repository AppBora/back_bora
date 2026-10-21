package br.com.bora.controller;

import br.com.bora.entity.IntegracaoCanal;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Pedido;
import br.com.bora.entity.PedidoItem;
import br.com.bora.entity.Produto;
import br.com.bora.entity.StatusPedido;
import br.com.bora.repository.IntegracaoCanalRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.PedidoItemRepository;
import br.com.bora.repository.PedidoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.service.ComplementoService;
import br.com.bora.service.DescontoService;
import br.com.bora.service.RepetirPedidoService;
import br.com.bora.service.PixService;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@RestController
@RequestMapping("/public")
public class PublicController {

    private final LojaRepository lojas;
    private final ProdutoRepository produtos;
    private final PedidoRepository pedidos;
    private final PedidoItemRepository itens;
    private final IntegracaoCanalRepository integracoes;
    private final PixService pix;
    private final br.com.bora.service.FidelidadeService fidelidade;
    private final br.com.bora.repository.ComplementoGrupoRepository compGrupos;
    private final br.com.bora.repository.ComplementoItemRepository compItens;
    private final ComplementoService complementoService;
    private final br.com.bora.repository.CupomRepository cupons;
    private final DescontoService descontos;
    private final br.com.bora.security.FreioDoCardapio freio;
    private final br.com.bora.service.OperacaoService operacao;
    private final br.com.bora.repository.TaxaEntregaRepository taxas;
    private final br.com.bora.service.InsumoService insumos;
    private final br.com.bora.service.EstornoDaVendaService devolucaoDeEstoque;
    private final br.com.bora.security.RegraDeAcesso regra;
    private final br.com.bora.repository.ConfiguracaoLojaRepository configuracoes;
    private final boolean respeitarHorario;
    /** Transação explícita: o pedido grava em passos curtos, com a chamada ao Asaas FORA deles. */
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final br.com.bora.service.RepetirPedidoService repetir;

    public PublicController(LojaRepository lojas, ProdutoRepository produtos, PedidoRepository pedidos,
                            PedidoItemRepository itens, IntegracaoCanalRepository integracoes, PixService pix,
                            br.com.bora.repository.ComplementoGrupoRepository compGrupos,
                            br.com.bora.repository.ComplementoItemRepository compItens,
                            ComplementoService complementoService,
                            br.com.bora.repository.CupomRepository cupons,
                            DescontoService descontos,
                            br.com.bora.security.FreioDoCardapio freio,
                            br.com.bora.service.FidelidadeService fidelidade,
                            br.com.bora.service.OperacaoService operacao,
                            br.com.bora.repository.TaxaEntregaRepository taxas,
                            br.com.bora.service.InsumoService insumos,
                            br.com.bora.service.EstornoDaVendaService devolucaoDeEstoque,
                            br.com.bora.security.RegraDeAcesso regra,
                            br.com.bora.repository.ConfiguracaoLojaRepository configuracoes,
                            br.com.bora.service.RepetirPedidoService repetir,
                            org.springframework.transaction.PlatformTransactionManager gerenciadorDeTransacao,
                            @org.springframework.beans.factory.annotation.Value("${bora.cardapio.respeitar-horario:false}") boolean respeitarHorario) {
        this.respeitarHorario = respeitarHorario;
        this.repetir = repetir;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(gerenciadorDeTransacao);
        this.taxas = taxas;
        this.insumos = insumos;
        this.devolucaoDeEstoque = devolucaoDeEstoque;
        this.regra = regra;
        this.configuracoes = configuracoes;
        this.operacao = operacao;
        this.lojas = lojas;
        this.produtos = produtos;
        this.pedidos = pedidos;
        this.itens = itens;
        this.integracoes = integracoes;
        this.pix = pix;
        this.fidelidade = fidelidade;
        this.compGrupos = compGrupos;
        this.compItens = compItens;
        this.complementoService = complementoService;
        this.cupons = cupons;
        this.descontos = descontos;
        this.freio = freio;
    }


    /**
     * A marca que o cliente final enxerga: nome, logo e cores DA LOJA.
     *
     * <p>Tudo isso ja existia no cadastro e morria la: o cardapio publico e a tela de acompanhamento
     * mostravam o roxo do Bora e um sorvete fixo para qualquer loja. O site promete o contrario em
     * dois lugares ("logo, cores e nome da sua loja no cardapio digital" e "Meus clientes vao ver a
     * marca do BoraHapp? Nao"), entao isto era promessa quebrada, nao enfeite.</p>
     */
    private Map<String, Object> marcaDaLoja(Loja loja) {
        var cfg = configuracoes.findByLojaId(loja.id).orElse(null);
        String nome = cfg != null && cfg.nomeExibicao != null && !cfg.nomeExibicao.isBlank()
                ? cfg.nomeExibicao : (loja.nome == null ? "Cardápio" : loja.nome);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nome", nome);
        // Logo e banner tambem vinham embutidos em texto dentro do JSON. O logo da Zira sozinho era
        // 34 KB em TODA abertura do cardapio e da tela de acompanhar. Como endereco, o navegador
        // guarda em cache e nao baixa de novo.
        m.put("logoUrl", enderecoDaMarca(loja.id, "logo", cfg == null ? null : cfg.logoUrl));
        m.put("bannerUrl", enderecoDaMarca(loja.id, "banner", cfg == null ? null : cfg.bannerUrl));
        // Sem cor escolhida, fica o roxo que o cardapio ja usava — nao queremos loja sem identidade
        // nenhuma por causa de um cadastro em branco.
        m.put("corPrimaria", cfg == null || cfg.corPrimaria == null || cfg.corPrimaria.isBlank()
                ? "#7c3aed" : cfg.corPrimaria);
        m.put("corSecundaria", cfg == null || cfg.corSecundaria == null || cfg.corSecundaria.isBlank()
                ? "#22c55e" : cfg.corSecundaria);
        // O lojista decide se o rodape diz que o sistema e BoraHapp.
        m.put("mostrarMarcaBora", cfg == null || !Boolean.FALSE.equals(cfg.mostrarMarcaBora));
        return m;
    }

    /** So a marca — a tela de acompanhamento do pedido precisa dela sem baixar o cardapio inteiro. */
    @GetMapping("/loja/{lojaId}/marca")
    public Map<String, Object> marca(@PathVariable Long lojaId) {
        return marcaDaLoja(lojaAtiva(lojaId));
    }

    /** Manifesto PWA da loja: o cliente instala o "app" com o nome/cara da loja (white-label). */
    @GetMapping(value = "/loja/{lojaId}/manifest", produces = "application/manifest+json")
    public Map<String, Object> manifest(@PathVariable Long lojaId) {
        Loja loja = lojaAtiva(lojaId);
        Map<String, Object> marca = marcaDaLoja(loja);
        String nome = String.valueOf(marca.get("nome"));
        Object logo = marca.get("logoUrl");
        // O cliente instala o "app" DA LOJA: o icone e o nome na tela inicial do celular dele eram do
        // Bora, com o nosso roxo, em qualquer loja. Agora so caem no nosso icone quem nao subiu logo.
        boolean temLogo = logo != null && !String.valueOf(logo).isBlank();
        Map<String, Object> icone = temLogo
                ? Map.of("src", String.valueOf(logo), "sizes", "any", "purpose", "any")
                : Map.of("src", "/assets/img/icone-bora.svg", "sizes", "any",
                        "type", "image/svg+xml", "purpose", "any");
        return Map.of(
                "name", nome + " · Pedidos",
                "short_name", nome.length() > 12 ? nome.substring(0, 12) : nome,
                "start_url", "/cardapio.html?loja=" + lojaId,
                "scope", "/",
                "display", "standalone",
                "background_color", "#f6f7fb",
                "theme_color", String.valueOf(marca.get("corPrimaria")),
                "icons", List.of(icone));
    }

    /**
     * Valida um codigo de desconto para exibir no checkout (nao reserva nada).
     *
     * <p>Atende cupom E promocao: a tela de Promocoes e o botao de promocao relampago do Dashboard
     * gravam na tabela {@code promocao}, que este endpoint nao consultava — o lojista divulgava o
     * codigo e o cliente recebia "cupom invalido". Ver {@link DescontoService}.</p>
     *
     * <p>O total vai no parametro porque promocao e cupom percentual dependem dele. Sem total, usamos
     * R$ 100 so para a tela poder mostrar o percentual; o valor que vale e sempre o recalculado na
     * criacao do pedido.</p>
     */
    @GetMapping("/loja/{lojaId}/cupom/{codigo}")
    public Map<String, Object> validarCupom(@PathVariable Long lojaId, @PathVariable String codigo,
                                            @RequestParam(required = false) java.math.BigDecimal total,
                                            jakarta.servlet.http.HttpServletRequest http) {
        freio.codigoDeDesconto(http); // sem teto, isto virava adivinhador de cupom
        lojaAtiva(lojaId);
        java.math.BigDecimal base = total != null && total.signum() > 0 ? total : new java.math.BigDecimal("100");
        var d = descontos.resolver(lojaId, codigo, base)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cupom inválido ou vencido"));
        return Map.of("codigo", d.codigo(), "tipo", d.tipo(), "valor", d.valorConfigurado(),
                "desconto", d.valorAbatido());
    }

    /** Cardápio público de uma loja: nome + produtos ativos (somente leitura). */
    @GetMapping("/loja/{lojaId}/cardapio")
    public Map<String, Object> cardapio(@PathVariable Long lojaId) {
        Loja loja = lojaAtiva(lojaId);
        List<Produto> lista = produtos.findByLojaIdAndAtivoTrueOrderByCategoriaAscNomeAsc(lojaId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("loja", Map.of("id", loja.id, "nome", loja.nome == null ? "Cardápio" : loja.nome));
        resp.put("marca", marcaDaLoja(loja));
        resp.put("pixDisponivel", integracaoPix(lojaId).isPresent() || subcontaRecebendo(loja));
        // A tela precisa saber ANTES: descobrir que a loja esta fechada so ao confirmar o pedido,
        // depois de montar o carrinho inteiro, e a pior forma de contar isso ao cliente.
        resp.put("aberta", !respeitarHorario || operacao.abertaAgora(lojaId));
        // Bairros atendidos e quanto custa entregar em cada um. Sem isto a tela nao tinha como
        // perguntar o bairro, e TODA entrega pelo cardapio saia sem frete.
        resp.put("bairros", taxas.findByLojaIdAndAtivoTrueOrderByBairroAsc(lojaId).stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bairro", t.bairro);
            m.put("taxa", t.taxa == null ? BigDecimal.ZERO : t.taxa);
            m.put("tempoMin", t.tempoMin);
            return m;
        }).toList());
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
            m.put("imagem", enderecoDaImagem(lojaId, p.id, p.imagemUrl));
            m.put("complementos", gruposPorProduto.getOrDefault(p.id, List.of()));
            // Esgotado JA NO MENU. Sem isto o cliente montava o carrinho inteiro e so descobria no
            // "confirmar pedido" -- a pior forma de contar isso a ele, e a mesma razao pela qual o
            // cardapio ja avisa antes que a loja esta fechada.
            //
            // E uma FOTO, nao uma reserva: entre abrir o cardapio e confirmar, alguem pode comprar o
            // ultimo. Quem garante de verdade e a baixa no banco, na hora de gravar o pedido. Produto
            // que nao controla estoque (estoque nulo) nunca aparece esgotado -- e o caso de todos os
            // 123 produtos da producao hoje.
            boolean esgotado = (p.estoque != null && p.estoque < 1)
                    || !insumos.fichaDaParaFazer(lojaId, p.id, 1);
            m.put("esgotado", esgotado);
            return m;
        }).toList());
        return resp;
    }

    /**
     * O que o cliente do cardapio le quando o item acabou.
     *
     * <p>Diz o PRODUTO e diz o que fazer. Nao diz "insumo", nao diz "estoque negativo" e nao diz
     * quanto ainda tem -- saldo da loja nao e assunto de quem esta comprando.</p>
     */
    private static String esgotou(String produto) {
        return "Acabou o estoque de \"" + (produto == null ? "um item" : produto)
                + "\". Tire do carrinho para continuar, ou diminua a quantidade.";
    }

    private String enderecoDaMarca(Long lojaId, String qual, String valor) {
        if (valor == null || valor.isBlank()) return null;
        if (!valor.startsWith("data:")) return valor;
        return "/public/loja/" + lojaId + "/" + qual;
    }

    /** Logo ou banner da loja, a partir do que está gravado, com cache no navegador. */
    @GetMapping("/loja/{lojaId}/{qual:logo|banner}")
    public org.springframework.http.ResponseEntity<byte[]> imagemDaMarca(
            @PathVariable Long lojaId, @PathVariable String qual,
            @RequestHeader(value = "If-None-Match", required = false) String etagRecebida) {
        var cfg = configuracoes.findByLojaId(lojaId).orElse(null);
        String dado = cfg == null ? null : ("logo".equals(qual) ? cfg.logoUrl : cfg.bannerUrl);
        return servirImagem(dado, etagRecebida);
    }

    /**
     * Foto do produto: endereço, não a foto inteira dentro do JSON.
     *
     * <p>As fotos vinham embutidas no cardápio em base64. Na Zirá isso era <b>89% do peso</b>: 452 KB
     * de foto num cardápio de 510 KB, baixados de novo a cada abertura, no 4G do cliente — inclusive
     * as fotos de produtos que ele nunca vai rolar até ver. Como endereço, o navegador baixa só o que
     * aparece, guarda em cache e não repete.</p>
     *
     * <p>Quem já tiver um endereço de verdade gravado (http...) continua como está.</p>
     */
    private String enderecoDaImagem(Long lojaId, Long produtoId, String valor) {
        if (valor == null || valor.isBlank()) return null;
        if (!valor.startsWith("data:")) return valor;
        return "/public/loja/" + lojaId + "/produto/" + produtoId + "/imagem";
    }

    /** Serve a foto do produto a partir do que está gravado, com cache no navegador. */
    @GetMapping("/loja/{lojaId}/produto/{produtoId}/imagem")
    public org.springframework.http.ResponseEntity<byte[]> imagemDoProduto(
            @PathVariable Long lojaId, @PathVariable Long produtoId,
            @RequestHeader(value = "If-None-Match", required = false) String etagRecebida) {
        return servirImagem(produtos.findByIdAndLojaId(produtoId, lojaId)
                .map(pr -> pr.imagemUrl).orElse(null), etagRecebida);
    }

    /**
     * Os unicos tipos que este servidor entrega como imagem.
     *
     * <p>Sem esta lista, o tipo saia do proprio {@code data:} que o lojista gravou. Dava para salvar
     * {@code data:text/html;base64,...} numa foto de produto e pedir a vitima para abrir o endereco da
     * imagem: o navegador executava aquilo como PAGINA, no mesmo dominio do painel, e o script lia o
     * token do {@code localStorage}. Se a vitima fosse o suporte da plataforma, o atacante levava
     * acesso a todas as lojas. SVG fica de fora de proposito: SVG executa script.</p>
     */
    private static final java.util.Set<String> IMAGENS_PERMITIDAS = java.util.Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif", "image/avif");

    /** Decodifica o "data:..." gravado e entrega como imagem de verdade, com cache e etiqueta. */
    private org.springframework.http.ResponseEntity<byte[]> servirImagem(String dado, String etagRecebida) {
        if (dado == null || !dado.startsWith("data:")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sem imagem");
        }
        int virgula = dado.indexOf(',');
        String tipo = dado.substring(5, virgula < 0 ? dado.length() : virgula).split(";")[0]
                .trim().toLowerCase();
        if (!IMAGENS_PERMITIDAS.contains(tipo)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sem imagem");
        }
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(virgula < 0 ? "" : dado.substring(virgula + 1));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Imagem ilegível");
        }
        // A etiqueta muda quando a imagem muda: o lojista troca a foto e o cliente vê a nova.
        String etag = "\"" + Integer.toHexString(dado.hashCode()) + "\"";
        if (etag.equals(etagRecebida)) {
            return org.springframework.http.ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "public, max-age=600")
                // Cinto e suspensorio: mesmo com a lista acima, o navegador nao deve adivinhar o tipo
                // nem executar nada que venha daqui.
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .eTag(etag)
                .contentType(org.springframework.http.MediaType.parseMediaType(tipo))
                .body(bytes);
    }

    /**
     * Pedido online do cardápio digital. Preço SEMPRE recalculado no servidor.
     * formaPagamento "PIX" cria a cobrança na conta Asaas do lojista e devolve o QR Code.
     */
    @PostMapping("/loja/{lojaId}/pedido")
    // TRANSACAO, e nao e detalhe: este metodo grava o pedido, depois os itens num laco, e o laco pode
    // recusar no meio ("Produto indisponivel", "Quantidade invalida" e agora "acabou o estoque"). Sem
    // transacao, a recusa no terceiro item deixava o pedido gravado, os dois primeiros itens gravados,
    // e o estoque dos dois ja baixado -- lixo no painel e estoque errado por um pedido que o cliente
    // nunca recebeu. Com transacao, recusar nao deixa rastro.
    @Transactional
    public Map<String, Object> pedirOnline(@PathVariable Long lojaId, @RequestBody Map<String, Object> body,
                                           jakarta.servlet.http.HttpServletRequest http) {
        // Teto por origem, ANTES de qualquer efeito: este endpoint e publico e cada chamada cria
        // pedido, baixa estoque e pode abrir cobranca no Asaas da loja. Ver FreioDoCardapio.
        freio.pedido(http);
        Loja loja = lojaAtiva(lojaId);
        // O horario que o lojista configura existia so na tela: abertaAgora() nao tinha um unico
        // chamador, e o cardapio aceitava pedido as 3 da manha com a loja fechada.
        //
        // Nasce DESLIGADO de proposito: toda loja e semeada com 18h-23h todo dia, e na pratica esse
        // horario quase nunca foi revisado pelo lojista. Ligar sem conferir loja por loja recusaria
        // venda de verdade - o oposto do que queremos. Ligue com BORA_CARDAPIO_RESPEITAR_HORARIO=true
        // depois de confirmar o horario de cada loja.
        if (respeitarHorario && !operacao.abertaAgora(lojaId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A loja esta fechada agora. Confira o horario de funcionamento e volte depois.");
        }
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
        boolean retirada = "RETIRADA".equalsIgnoreCase(str(body.get("tipoEntrega")));
        String bairro = str(body.get("bairro"));

        // Checagens do PIX ANTES de qualquer escrita: se a loja nao aceita PIX ou o CPF nao presta,
        // nao faz sentido nascer pedido nenhum para depois ser desfeito.
        final boolean comPix = "PIX".equals(forma);
        final String cpfLimpo = cpf == null ? "" : cpf.replaceAll("[^0-9]", "");
        final IntegracaoCanal integPix;
        if (comPix) {
            boolean subconta = subcontaRecebendo(loja);
            integPix = integracaoPix(lojaId).orElse(null);
            if (!subconta && integPix == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Esta loja não aceita PIX online");
            }
            if (cpfLimpo.length() < 11) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe um CPF válido para pagar com PIX");
            }
        } else {
            integPix = null;
        }

        // ---- Passo 1: só banco, transação curta. Nenhuma chamada externa aqui dentro. ----
        Rascunho rascunho = tx.execute(st -> {

            // Frete do cardapio. Ate aqui a taxa ficava SEMPRE zero aqui, enquanto o balcao cobrava a
            // tabela do bairro: toda entrega feita pelo cardapio saia de graca, e o acerto do motoboy
            // ainda somava R$ 0 por ela. Quando a loja nao tem bairro cadastrado, nada muda.
            var bairrosAtendidos = taxas.findByLojaIdAndAtivoTrueOrderByBairroAsc(lojaId);
            BigDecimal taxaEntrega = BigDecimal.ZERO;
            if (!retirada && !bairrosAtendidos.isEmpty()) {
                if (bairro == null || bairro.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Escolha o bairro da entrega para calcularmos a taxa.");
                }
                var tabela = taxas.findFirstByLojaIdAndBairroIgnoreCaseAndAtivoTrue(lojaId, bairro.trim())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Ainda nao entregamos nesse bairro. Escolha um da lista ou retire no balcao."));
                taxaEntrega = tabela.taxa == null ? BigDecimal.ZERO : tabela.taxa;
            }
            if (nome == null || nome.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe seu nome");
            }
            // Entrega SEM ENDERECO e SEM TELEFONE passava: so o nome era exigido. A loja recebia um
            // pedido para entregar e nao tinha para onde ir nem como ligar — e o cliente so descobria
            // o problema quando o pedido nao chegava. O endereco nem tinha asterisco na tela.
            //
            // Retirada nao precisa de endereco (o cliente vem buscar), mas o telefone continua
            // exigido: e como a loja avisa que esta pronto.
            if (!retirada && (endereco == null || endereco.trim().length() < 8)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Informe o endereco da entrega, com rua e numero.");
            }
            String telDigitos = telefone == null ? "" : telefone.replaceAll("[^0-9]", "");
            if (telDigitos.length() < 10 || telDigitos.length() > 13) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Informe um telefone com DDD para a loja falar com voce.");
            }

            Pedido p = new Pedido();
            p.lojaId = lojaId;
            p.origem = "Cardápio Digital";
            p.formaPagamento = "PIX".equals(forma) ? "PIX (aguardando)" : "Na entrega";
            if (telefone != null && !telefone.isBlank()) p.clienteTelefone = telefone.replaceAll("[^0-9]", "");
            StringBuilder ob = new StringBuilder("Cliente: ").append(nome.trim());
            if (telefone != null && !telefone.isBlank()) ob.append(" | Tel: ").append(telefone.trim());
            if (retirada) ob.append(" | RETIRADA NO BALCAO");
            if (endereco != null && !endereco.isBlank()) ob.append(" | End: ").append(endereco.trim());
            if (!retirada && bairro != null && !bairro.isBlank()) ob.append(" | Bairro: ").append(bairro.trim());

            // E em COLUNA, nao so no texto. O texto continua para quem ja esta acostumado a ler a
            // observacao, mas quem entrega passa a ler o campo: o card do painel mostrava
            // `cliente.endereco`, que no cliente recorrente e o endereco antigo dele. Ver V48.
            p.tipoEntrega = retirada ? "RETIRADA" : "ENTREGA";
            if (!retirada) {
                if (endereco != null && !endereco.isBlank()) p.enderecoEntrega = endereco.trim();
                if (bairro != null && !bairro.isBlank()) p.bairroEntrega = bairro.trim();
            }
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

                // Complementos escolhidos: a regra (posse, mínimo/máximo por grupo, preço) é a mesma do
                // painel — mora no ComplementoService para os dois canais não divergirem.
                List<Long> escolhidos = new java.util.ArrayList<>();
                Object escRaw = it.get("complementos");
                if (escRaw instanceof List<?> ls) for (Object o : ls) { try { escolhidos.add(Long.valueOf(String.valueOf(o))); } catch (Exception e) {} }
                ComplementoService.Escolha escolha = complementoService.aplicar(lojaId, prod, escolhidos);
                BigDecimal extra = escolha.acrescimo();
                String nomeItem = escolha.descricao(prod.nome);

                PedidoItem item = new PedidoItem();
                item.setLojaId(lojaId);
                item.setPedidoId(p.id);
                item.setProdutoId(prod.id);
                item.setDescricao(nomeItem);
                // Nome e preco junto, nao so o id: ver ComplementosDoItem. Lista vazia grava "[]",
                // que diz "nao tinha adicional" — diferente de nulo, que diz "nao sei".
                item.setComplementos(br.com.bora.service.ComplementosDoItem.escrever(escolha.escolhidos()));
                item.setQuantidade(qtd);
                BigDecimal unit = (prod.preco == null ? BigDecimal.ZERO : prod.preco).add(extra);
                item.setPrecoUnitario(unit);
                // Mesma regra do balcao: com ficha tecnica, consome os insumos e usa o custo da ficha;
                // sem ficha, baixa o estoque do proprio produto. O cardapio nao fazia nem um nem outro,
                // entao estoque e CMV ficavam errados justamente no canal que mais vende.
                // Aqui o canal RECUSA quando falta, diferente do balcao e do marketplace.
                //
                // O motivo e quem esta com o produto na mao. No balcao o operador esta, e travar a
                // venda com o cliente na frente e pior que o saldo negativo -- que pelo menos denuncia
                // que a contagem esta errada. No marketplace o pedido ja foi aceito do outro lado.
                // Aqui o cliente nao tem nada na mao: vender e depois cancelar e pior que dizer
                // "acabou" agora. E era por aqui que uma unica chamada levava um produto a -4950
                // (50 itens x 99).
                //
                // Quem recusa e o BANCO, nao uma conferencia antes: duas vendas ao mesmo tempo leriam
                // o mesmo saldo e as duas passariam. Medido num Postgres 16 com duas sessoes -- o
                // estoque de 1 unidade com duas vendas simultaneas termina em 0, nao em -1.
                BigDecimal custoFicha;
                try {
                    custoFicha = insumos.consumirFicha(lojaId, prod.id, qtd, true);
                } catch (br.com.bora.service.InsumoService.SemInsumo e) {
                    // O nome do INSUMO fica no log, para o lojista. O cliente le o nome do PRODUTO:
                    // "Polpa de Acai Organico" e vocabulario de dentro da loja.
                    log.warn("Loja {}: pedido do cardapio recusado por falta do insumo \"{}\" na ficha "
                            + "de \"{}\" ({} un). Reponha o insumo ou tire o produto do cardapio.",
                            lojaId, e.insumo, prod.nome, qtd);
                    throw new ResponseStatusException(HttpStatus.CONFLICT, esgotou(prod.nome));
                }
                item.setCustoUnitario(custoFicha != null ? custoFicha : prod.custo);
                // Fica gravado no item qual dos dois caminhos rodou. Quem cancelar o pedido devolve
                // exatamente isso, sem depender da ficha que o lojista tiver no dia do cancelamento.
                item.setConsumiuFicha(custoFicha != null);
                if (custoFicha == null && produtos.baixarEstoqueSeTiver(prod.id, lojaId, qtd) == 0) {
                    log.warn("Loja {}: pedido do cardapio recusado por falta de estoque de \"{}\" "
                            + "({} un pedidas).", lojaId, prod.nome, qtd);
                    throw new ResponseStatusException(HttpStatus.CONFLICT, esgotou(prod.nome));
                }
                BigDecimal sub = unit.multiply(BigDecimal.valueOf(qtd));
                item.setSubtotal(sub);
                itens.save(item);
                total = total.add(sub);
            }
            if (total.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pedido sem valor");
            }

            // Codigo de desconto (recalculado no servidor; nunca zera o pedido). Cupom OU promocao:
            // ver DescontoService. O uso da promocao so e contabilizado depois que o pedido existe.
            BigDecimal descontoAplicado = BigDecimal.ZERO;
            DescontoService.Desconto descontoUsado = null;
            String codCupom = str(body.get("cupom"));
            if (codCupom != null && !codCupom.isBlank()) {
                DescontoService.Desconto d = descontos.resolver(lojaId, codCupom, total)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cupom inválido ou vencido"));
                descontoAplicado = d.valorAbatido();
                if (descontoAplicado.compareTo(total) >= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O cupom não pode zerar o pedido");
                }
                total = total.subtract(descontoAplicado);
                descontoUsado = d;
                p.observacao = p.observacao + " | Cupom " + d.codigo() + " (-R$ " + descontoAplicado + ")";
            }

            // Fidelidade: no cardápio o cliente é identificado pelo telefone — não há cadastro nem login.
            Long clienteId = fidelidade.identificarPeloTelefone(lojaId, nome, telefone, endereco);
            p.clienteId = clienteId;

            BigDecimal resgate = BigDecimal.ZERO;
            if (Boolean.TRUE.equals(body.get("usarCashback")) || "true".equals(str(body.get("usarCashback")))) {
                // O saldo e de quem o acumulou. Telefone de cliente nao e segredo, entao exigimos que o
                // nome informado no checkout bata com o do cadastro antes de gastar o cashback dele.
                if (!fidelidade.ehOMesmoCliente(lojaId, clienteId, nome)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Para usar o cashback, informe o mesmo nome do seu cadastro.");
                }
                resgate = fidelidade.resgatePossivel(lojaId, clienteId, total);
                if (resgate.signum() > 0) {
                    total = total.subtract(resgate);
                    p.cashbackUsado = resgate; // em campo próprio: o texto da observação não serve para contas
                    p.observacao = p.observacao + " | Cashback usado (-R$ " + resgate + ")";
                }
            }

            total = total.add(taxaEntrega);
            p.taxaEntrega = taxaEntrega;
            p.valorTotal = total;
            p.codigo = "CD-" + p.id;

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("pedidoId", p.id);
            resp.put("codigo", p.codigo);
            // Sem isto o cliente nao tem como acompanhar o proprio pedido: o status passou a exigir
            // assinatura (ver statusPedido).
            resp.put("acompanharToken", repetir.token(lojaId, p.id));
            resp.put("valorTotal", total);
            resp.put("desconto", descontoAplicado);
            resp.put("cashbackUsado", resgate);
            resp.put("taxaEntrega", taxaEntrega);
            resp.put("retirada", retirada);

            p.aguardandoPagamento = comPix; // decidido aqui, nao depende da resposta do Asaas
            p.atualizadoEm = OffsetDateTime.now();
            pedidos.save(p);

            // Cashback só depois que o dinheiro entra. Creditar na hora do pedido deixava qualquer
            // pessoa fabricar saldo: bastava gerar dez PIX de R$ 50 e nunca pagar. No PIX pendente,
            // quem credita é o webhook do pagamento.
            //
            // O DÉBITO, porém, é na hora, sempre. O desconto já saiu do total que o cliente vai pagar;
            // se o saldo só fosse baixado depois, o mesmo cashback valeria em quantos pedidos PIX ele
            // abrisse ao mesmo tempo. Se o PIX não for pago, o cobrador devolve.
            if (!comPix) {
                fidelidade.registrar(lojaId, clienteId, p.valorTotal, resgate);
            } else {
                fidelidade.consumir(lojaId, clienteId, resgate);
            }
            resp.put("cashbackNovo", fidelidade.saldo(lojaId, clienteId));
            resp.put("aguardandoPagamento", comPix);
            return new Rascunho(p, resp, clienteId, resgate, descontoUsado);
        });

        Map<String, Object> resp = rascunho.resposta();
        if (!comPix) {
            // Pedido sem PIX ja esta de pe: o uso da promocao conta agora. No caminho com PIX a
            // contagem espera o passo 3, porque o Asaas ainda pode falhar e desfazer o pedido.
            descontos.contabilizarUso(rascunho.desconto());
            return resp;
        }

        // ---- Passo 2: o Asaas FORA da transação. A conexão do banco já voltou para o pool. ----
        Map<String, Object> cobranca;
        try {
            cobranca = pix.criarCobranca(integPix, loja, rascunho.pedido(), nome, cpfLimpo);
        } catch (Exception e) {
            // O pedido já existe e já consumiu cashback. Sem o PIX ele não tem como ser pago:
            // cancelamos e devolvemos o saldo, do mesmo jeito que o cobrador faz com PIX abandonado.
            desfazerPedidoSemPix(rascunho);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Falha ao gerar o PIX: " + e.getMessage());
        }

        // ---- Passo 3: guarda a cobrança, outra transação curta ----
        tx.executeWithoutResult(st -> pedidos.findById(rascunho.pedido().id).ifPresent(atual -> {
            atual.canalExterno = "PIX_ASAAS";
            atual.idExterno = (String) cobranca.get("paymentId");
            atual.atualizadoEm = OffsetDateTime.now();
            pedidos.save(atual);
        }));
        descontos.contabilizarUso(rascunho.desconto()); // o pedido sobreviveu ao Asaas
        resp.put("pix", cobranca);
        return resp;
    }

    /** O que o passo 1 produziu, para os passos seguintes trabalharem sem reabrir transação. */
    private record Rascunho(Pedido pedido, Map<String, Object> resposta, Long clienteId,
                            BigDecimal resgate, DescontoService.Desconto desconto) {}

    /** PIX que não nasceu: cancela o pedido e devolve o cashback que ele já tinha consumido. */
    private void desfazerPedidoSemPix(Rascunho r) {
        tx.executeWithoutResult(st -> pedidos.findById(r.pedido().id).ifPresent(p -> {
            StatusPedido anterior = p.status;
            p.status = StatusPedido.CANCELADO;
            p.canceladoEm = OffsetDateTime.now();
            p.atualizadoEm = OffsetDateTime.now();
            p.aguardandoPagamento = false;
            p.motivoCancelamento = "Não foi possível gerar o PIX";
            pedidos.save(p);
            // O pedido chegou a baixar estoque e a debitar o cashback antes de o PIX falhar; devolve
            // tudo. O estorno do cashback vive no EstornoDaVendaService, junto com o do estoque — era
            // uma chamada solta aqui, e os outros caminhos de cancelamento esqueciam dela.
            // false: este PIX nunca foi pago, entao a venda nao entrou no historico nem gerou cashback.
            devolucaoDeEstoque.devolver(p, anterior, false);
        }));
    }
    /**
     * Saldo de cashback de quem está no checkout. O telefone é a identificação do cliente no
     * cardápio; devolve só o valor, nunca nome ou histórico.
     */
    @GetMapping("/loja/{lojaId}/cashback")
    public Map<String, Object> cashback(@PathVariable Long lojaId, @RequestParam String telefone,
                                        @RequestParam(required = false) String nome,
                                        jakarta.servlet.http.HttpServletRequest http) {
        freio.consultaDeSaldo(http); // so telefone + primeiro nome protegem este saldo
        lojaAtiva(lojaId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("percentual", fidelidade.percentual(lojaId));
        // Exige o nome: so com o telefone, qualquer um consultava o saldo alheio.
        m.put("saldo", fidelidade.saldoPeloTelefone(lojaId, telefone, nome));
        return m;
    }

    /**
     * O carrinho sugerido a partir de um pedido antigo, para o cliente repetir o que ja pediu.
     *
     * <p>Exige a assinatura do link ({@code t}), conferida dentro do servico. Assinatura errada e
     * pedido inexistente devolvem o <b>mesmo</b> 404: um 403 separado confirmaria que o pedido
     * existe, que e metade do que alguem varrendo numeros quer saber.</p>
     *
     * <p>{@code lojaAtiva} vem depois da assinatura, e nao antes, para o endpoint nao virar oraculo
     * de quais lojas existem.</p>
     */
    @GetMapping("/loja/{lojaId}/pedido/{pedidoId}/repetir")
    public RepetirPedidoService.CarrinhoSugerido repetirPedido(
            @PathVariable Long lojaId, @PathVariable Long pedidoId,
            @RequestParam(name = "t", required = false) String t) {
        var carrinho = repetir.abrir(lojaId, pedidoId, t)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado"));
        lojaAtiva(lojaId); // loja suspensa ou vencida nao serve link antigo, igual ao cardapio
        return carrinho;
    }

    /** Status público do pedido (para a tela de acompanhamento do QR PIX). */
    /**
     * Status do pedido para a tela de acompanhamento do cliente, protegido por assinatura.
     *
     * <p><b>O furo que isto fecha:</b> este endpoint nao pedia nada. Os ids de pedido sao sequenciais e
     * globais, entao quem varresse {@code 1..N} lia o codigo, o status, o horario e o
     * {@code motivoCancelamento} — texto livre digitado pela equipe — de QUALQUER pedido de QUALQUER
     * loja. Dava para medir o movimento de um concorrente contando pedidos por dia.</p>
     *
     * <p>A assinatura e a mesma do "repetir pedido" ({@link RepetirPedidoService#token}), que ja existia
     * no projeto: HMAC curto, comparado em tempo constante. Token errado devolve o MESMO 404 de pedido
     * inexistente, para a resposta nao dizer se o pedido existe.</p>
     *
     * <p><b>Consequencia conhecida:</b> link de acompanhamento que o cliente recebeu ANTES deste deploy
     * nao tem o {@code t} e para de funcionar. O link so serve enquanto o pedido esta em andamento
     * (horas), entao o estrago e pequeno e preferivel a deixar a varredura aberta.</p>
     */
    @GetMapping("/loja/{lojaId}/pedido/{pedidoId}/status")
    public Map<String, Object> statusPedido(@PathVariable Long lojaId, @PathVariable Long pedidoId,
                                            @RequestParam(name = "t", required = false) String token) {
        if (!repetir.tokenValido(lojaId, pedidoId, token)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado");
        }
        Pedido p = pedidos.findByIdAndLojaId(pedidoId, lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codigo", p.codigo == null ? String.valueOf(p.id) : p.codigo);
        m.put("status", p.status.name());
        // `pagoEm` e nao o texto do formaPagamento: medido em 21/10/2026, no Pedido essa coluna e
        // gravada em dois lugares, e os dois sao "o PIX entrou" (este webhook e o
        // CobradorDePixService). Comparar o texto deixava a tela do cliente -- que pergunta por este
        // campo a cada 5 segundos -- girando para sempre quando o pagamento chegava DEPOIS do
        // cancelamento, porque ai o texto e "PIX (pago APOS o cancelamento)". O dinheiro entrou, o
        // pedido reabriu e foi para a cozinha, e so o cliente nao sabia.
        m.put("pago", p.pagoEm != null);
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
        // Dois caminhos de recebimento convivem: a subconta white-label (token na própria loja)
        // e a integração PIX legada (lojista colou a chave dele). Basta um dos dois autenticar.
        Loja loja = lojas.findById(lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
        IntegracaoCanal integ = integracoes.findByLojaIdAndCanal(lojaId, "PIX").orElse(null);
        boolean viaSubconta = token != null && loja.asaasWebhookToken != null
                && token.equals(loja.asaasWebhookToken);
        boolean viaLegado = token != null && integ != null && token.equals(integ.webhookToken);
        if (!viaSubconta && !viaLegado) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token inválido");
        }
        String event = str(body.get("event"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payment = (Map<String, Object>) body.get("payment");
        String paymentId = payment == null ? null : str(payment.get("id"));
        if (paymentId != null && ("PAYMENT_RECEIVED".equals(event) || "PAYMENT_CONFIRMED".equals(event))) {
            pedidos.findFirstByLojaIdAndCanalExternoAndIdExterno(lojaId, "PIX_ASAAS", paymentId).ifPresent(p -> {
                boolean primeiraConfirmacao = p.pagoEm == null;
                boolean jaCancelado = p.status == StatusPedido.CANCELADO;
                p.formaPagamento = jaCancelado ? "PIX (pago APOS o cancelamento)" : "PIX (pago)";
                p.aguardandoPagamento = false;
                p.pagoEm = OffsetDateTime.now();
                p.atualizadoEm = OffsetDateTime.now();
                if (jaCancelado) {
                    // O PEDIDO VOLTA A VALER, e isto e uma mudanca de comportamento deliberada
                    // (21/10/2026, varredura 2.7).
                    //
                    // Antes, o pedido ficava CANCELADO: a cozinha nunca via, o estoque nao saia, e a
                    // unica pista era este texto -- que o lojista le so se abrir a aba de cancelados.
                    // O cliente PAGOU e esta esperando comida. Deixar a venda escondida e pior que
                    // qualquer problema de estoque.
                    //
                    // Por que NAO bastava "rebaixar o estoque de novo", que era o conserto obvio: o
                    // pedido continuaria em estado final, o `alterarStatus` recusa sair de CANCELADO,
                    // e o lojista que quisesse preparar teria que lancar OUTRO pedido -- que baixa o
                    // estoque por conta propria. Rebaixar aqui tiraria em dobro.
                    //
                    // Reabrindo, tudo se resolve pelos caminhos que ja existem: o quadro mostra, a
                    // baixa de estoque acontece aqui (espelhando o reconsumo do cashback logo abaixo),
                    // e se o lojista nao puder atender ele CANCELA de novo -- e o cancelamento devolve
                    // estoque e cashback certo.
                    p.status = StatusPedido.RECEBIDO;
                    p.canceladoEm = null;
                    p.motivoCancelamento = null;
                    // O historico nao se perde: fica na observacao, que e o que a cozinha e o
                    // entregador leem no card.
                    p.observacao = (p.observacao == null ? "" : p.observacao + " | ")
                            + "REABERTO: o PIX foi pago depois do cancelamento e o dinheiro entrou. "
                            + "Se nao for atender, cancele de novo e devolva ao cliente.";
                    log.warn("Loja {}: pagamento {} chegou para o pedido {}, que estava CANCELADO. "
                                    + "O pedido foi REABERTO como RECEBIDO e o estoque foi baixado de "
                                    + "novo -- o cliente pagou e esta esperando. Se nao der para "
                                    + "atender, cancele de novo: o cancelamento devolve estoque e "
                                    + "cashback.",
                            lojaId, paymentId, p.id);
                }
                pedidos.save(p);
                // O Asaas reenvia o mesmo aviso quando nao recebe 200: sem esta guarda, o cliente
                // ganharia cashback de novo a cada reenvio.
                if (jaCancelado) {
                    // O cancelamento DEVOLVEU o estoque. O pedido voltou a valer, entao a baixa tem
                    // que acontecer de novo -- senao a comida sai e o estoque segue contando. E o
                    // espelho exato do reconsumo do cashback logo abaixo.
                    //
                    // `true` no ultimo argumento: a venda foi paga, entao nao e PIX pendente.
                    devolucaoDeEstoque.rebaixarDeNovo(p);
                }
                if (primeiraConfirmacao && p.clienteId != null) {
                    if (jaCancelado) {
                        // O cancelamento devolveu o cashback ao cliente. Como ele acabou pagando o valor
                        // ja descontado, o saldo volta a ser consumido — senao o desconto valeria duas
                        // vezes. Cashback NOVO so para venda que de fato aconteceu.
                        fidelidade.consumir(lojaId, p.clienteId, p.cashbackUsado);
                    } else {
                        fidelidade.registrar(lojaId, p.clienteId, p.valorTotal, java.math.BigDecimal.ZERO);
                    }
                }
            });
            if (integ != null) {
                integ.ultimaSync = OffsetDateTime.now();
                integracoes.save(integ);
            }
        }
        return Map.of("received", "true");
    }

    private Loja lojaAtiva(Long lojaId) {
        Loja loja = lojas.findById(lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
        if (Boolean.FALSE.equals(loja.ativo)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja indisponível");
        }
        // Prazo de acesso vencido fecha tambem o cardapio. Deixar o cardapio no ar com o painel
        // trancado seria pior para o cliente final: o pedido entraria e ninguem estaria olhando.
        if (regra.cortePorAssinaturaLigado() && loja.acessoVencido()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja indisponível");
        }
        return loja;
    }

    /**
     * Subconta do lojista pronta para receber. Exige o KYC aprovado (asaasStatus ATIVO): conta
     * criada mas em analise nao libera o dinheiro, e oferecer PIX ali deixaria o cliente pagando
     * para uma conta que nao pode sacar. A tela de Integracoes reconfere isso no Asaas.
     */
    private boolean subcontaRecebendo(Loja loja) {
        return loja != null && loja.asaasApiKey != null && !loja.asaasApiKey.isBlank()
                && "ATIVO".equals(loja.asaasStatus);
    }

    private java.util.Optional<IntegracaoCanal> integracaoPix(Long lojaId) {
        return integracoes.findByLojaIdAndCanal(lojaId, "PIX")
                .filter(i -> Boolean.TRUE.equals(i.ativo) && i.clientSecret != null && !i.clientSecret.isBlank());
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
}
