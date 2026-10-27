package br.com.bora.service;

import br.com.bora.entity.Insumo;
import br.com.bora.entity.ProdutoInsumo;
import br.com.bora.repository.InsumoRepository;
import br.com.bora.repository.ProdutoInsumoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

/** Insumos (ingredientes) e ficha técnica dos produtos; consome insumos a cada venda. */
@Service
public class InsumoService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InsumoService.class);

    private final InsumoRepository insumos;
    private final ProdutoInsumoRepository fichas;
    private final ProdutoRepository produtos;
    private final br.com.bora.repository.ComplementoItemRepository adicionais;
    private final AuthContext ctx;

    public InsumoService(InsumoRepository insumos, ProdutoInsumoRepository fichas, ProdutoRepository produtos,
                         br.com.bora.repository.ComplementoItemRepository adicionais, AuthContext ctx) {
        this.insumos = insumos;
        this.fichas = fichas;
        this.produtos = produtos;
        this.adicionais = adicionais;
        this.ctx = ctx;
    }

    // ---- CRUD insumos ----
    /**
     * Os insumos da loja.
     *
     * <h2>Item 8.3 da varredura</h2>
     * <p>A tela de Insumos ja e GERENTE para cima no menu, mas o endpoint nao checava nada — e insumo
     * e onde o custo da loja mora de verdade. Aqui a lista INTEIRA e numero de dono (nome e unidade
     * de insumo nao servem ao balcao), entao bloqueia mesmo, em vez de omitir campo.</p>
     */
    public List<Insumo> listar() {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        return insumos.findByLojaIdOrderByNomeAsc(ctx.lojaId());
    }

    public Insumo salvar(Insumo i) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        Long loja = ctx.lojaId();
        Insumo alvo = i.id == null ? new Insumo()
                : insumos.findByIdAndLojaId(i.id, loja).orElseThrow(() -> nf("Insumo"));
        alvo.lojaId = loja; alvo.nome = i.nome; alvo.unidade = i.unidade == null ? "un" : i.unidade;
        alvo.custo = i.custo == null ? BigDecimal.ZERO : i.custo; alvo.estoqueMinimo = i.estoqueMinimo;
        // O ESTOQUE so entra na CRIACAO. Era `alvo.estoque = i.estoque` sempre, e o formulario mandava
        // o numero carregado na abertura da tela: corrigir o custo do insumo as 21h apagava o consumo
        // da noite inteira. Mesma causa do produto. Depois de criado, quem mexe e o mexerNoEstoque.
        if (i.id == null) alvo.estoque = i.estoque;
        if (alvo.nome == null || alvo.nome.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do insumo é obrigatório");
        return insumos.save(alvo);
    }

    /**
     * Apaga o insumo — mas RECUSA quando ele esta em alguma ficha tecnica.
     *
     * <h2>O defeito que isto fecha (medido em 21/10/2026)</h2>
     * <p>Apagava so o insumo. A tabela {@code produto_insumo} nao tem chave estrangeira, entao as
     * linhas da ficha ficavam apontando para um insumo que nao existe mais -- ficha fantasma. Depois,
     * no {@code consumirFicha}, a linha fantasma e pulada ({@code if (in == null) continue}) e o custo
     * sai menor do que e. Se TODOS os insumos da ficha tiverem sido apagados, nada e consumido e o
     * item ainda assim e gravado com {@code consumiuFicha = true} -- uma mentira que faz o
     * cancelamento tambem nao devolver nada. O estoque vaza nos dois sentidos, calado.</p>
     *
     * <p><b>Conferido na producao:</b> existe uma ficha fantasma hoje -- o produto "Acai 500ml
     * Atualizado" tem linha de ficha para o insumo 2, que nao existe (1 das 3 linhas de ficha da
     * base).</p>
     *
     * <h2>Por que RECUSAR e nao limpar junto</h2>
     * <p>Limpar a ficha junto muda o custo dos produtos sem o lojista saber: o produto continua sendo
     * vendido, agora com a margem errada. Recusar devolve a decisao a quem conhece a receita -- e a
     * mensagem diz EM QUAIS produtos o insumo esta, que e a informacao que falta para decidir.</p>
     */
    public void excluir(Long id) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        Long loja = ctx.lojaId();
        Insumo alvo = insumos.findByIdAndLojaId(id, loja).orElseThrow(() -> nf("Insumo"));

        var naFicha = fichas.findByLojaIdAndInsumoId(loja, id);
        if (!naFicha.isEmpty()) {
            String quais = naFicha.stream()
                    .map(pi -> produtos.findByIdAndLojaId(pi.produtoId, loja)
                            .map(pr -> pr.nome).orElse("produto #" + pi.produtoId))
                    .distinct()
                    .limit(5)
                    .collect(java.util.stream.Collectors.joining(", "));
            String sobra = naFicha.size() > 5 ? " e outros" : "";
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "\"" + alvo.nome + "\" esta na ficha tecnica de: " + quais + sobra
                            + ". Tire da ficha desses produtos antes de excluir, senao o custo deles "
                            + "fica errado e o estoque para de baixar.");
        }

        // O MESMO motivo, no segundo lugar onde um insumo e usado: desde a V55 o adicional aponta
        // para insumo. Sem esta recusa, apagar a "Granola" faria todo adicional de granola parar de
        // baixar estoque em silencio -- exatamente a ficha fantasma do item 2.5, agora no adicional.
        // A FK da V55 tambem recusaria, mas com erro de banco; aqui a mensagem diz o que fazer.
        var emAdicional = adicionais.findByLojaIdAndInsumoId(loja, id);
        if (!emAdicional.isEmpty()) {
            String quaisAd = emAdicional.stream().map(ci -> ci.nome).distinct().limit(5)
                    .collect(java.util.stream.Collectors.joining(", "));
            String sobraAd = emAdicional.size() > 5 ? " e outros" : "";
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "\"" + alvo.nome + "\" e o insumo dos adicionais: " + quaisAd + sobraAd
                            + ". Desligue o insumo desses adicionais antes de excluir, senao eles "
                            + "param de baixar estoque e o custo da venda fica errado.");
        }

        insumos.delete(alvo);
    }

    // ---- Ficha técnica ----
    public List<Map<String, Object>> ficha(Long produtoId) {
        // Item 8.3: a ficha tecnica traz o custo de cada insumo do produto. Quem monta ficha e quem
        // cuida de cardapio e de custo, e a tela ja e GERENTE para cima.
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        Long loja = ctx.lojaId();
        Map<Long, Insumo> map = new HashMap<>();
        insumos.findByLojaIdOrderByNomeAsc(loja).forEach(i -> map.put(i.id, i));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProdutoInsumo pi : fichas.findByLojaIdAndProdutoId(loja, produtoId)) {
            Insumo in = map.get(pi.insumoId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("insumoId", pi.insumoId);
            m.put("nome", in == null ? "?" : in.nome);
            m.put("unidade", in == null ? "un" : in.unidade);
            m.put("custo", in == null ? BigDecimal.ZERO : in.custo);
            m.put("quantidade", pi.quantidade);
            out.add(m);
        }
        return out;
    }

    @Transactional
    public List<Map<String, Object>> salvarFicha(Long produtoId, List<Map<String, Object>> itens) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        Long loja = ctx.lojaId();
        produtos.findByIdAndLojaId(produtoId, loja).orElseThrow(() -> nf("Produto")); // impede ficha em produto de outra loja
        fichas.deleteByLojaIdAndProdutoId(loja, produtoId);
        if (itens != null) for (Map<String, Object> it : itens) {
            if (it.get("insumoId") == null) continue;
            Long insumoId = Long.valueOf(String.valueOf(it.get("insumoId")));
            if (insumos.findByIdAndLojaId(insumoId, loja).isEmpty()) continue; // ignora insumo de outra loja
            ProdutoInsumo pi = new ProdutoInsumo();
            pi.lojaId = loja; pi.produtoId = produtoId;
            pi.insumoId = insumoId;
            pi.quantidade = quantidadeDaFicha(it.getOrDefault("quantidade", "0"));
            fichas.save(pi);
        }
        return ficha(produtoId);
    }

    /**
     * Quanto deste insumo cada unidade do produto consome — e por que isto nao aceita numero
     * negativo (achado pelo QA em 26/10/2026).
     *
     * <h2>O estrago</h2>
     * <p>A quantidade vinha do corpo do PUT direto para o {@code BigDecimal}, sem olhar o sinal. Com
     * {@code -0.5} na ficha, cada venda <b>SOMAVA</b> meio quilo ao estoque do insumo, nas duas
     * pontas: no balcao o {@code mexerNoEstoque} soma {@code precisa.negate()}, que vira positivo; no
     * cardapio o {@code consumirSeTiver} faz {@code estoque - (-0,5)}, e a condicao
     * {@code estoque >= -0,5} nunca barra nada. Vender virava entrada de mercadoria.</p>
     *
     * <p>E o custo ia junto: o CMV soma {@code custo x quantidade}, entao a linha negativa
     * <b>abatia</b> o custo do produto — podia zerar o CMV de um lanche inteiro e inflar a margem no
     * relatorio que o dono usa para decidir preco.</p>
     *
     * <p>Texto que nao e numero tambem passava reto e explodia em {@code NumberFormatException}:
     * HTTP 500 com rastro de pilha, em vez de dizer o que esta errado no campo.</p>
     *
     * <p><b>Zero continua valendo</b>, de proposito: e o que vem de uma linha que o lojista adicionou
     * e nao preencheu, e desde este commit o consumo pula a linha que nao consome nada. Recusar
     * zero transformaria um rascunho em erro de tela.</p>
     */
    private BigDecimal quantidadeDaFicha(Object valor) {
        BigDecimal q;
        try {
            q = new BigDecimal(String.valueOf(valor).trim().replace(",", "."));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Quantidade invalida na ficha tecnica: \"" + valor + "\". Use um numero, "
                            + "por exemplo 0,030 para 30 gramas.");
        }
        if (q.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A quantidade na ficha tecnica nao pode ser negativa. Ela e o quanto cada unidade "
                            + "do produto CONSOME; com numero negativo, cada venda somaria ao estoque "
                            + "e abateria o custo do produto.");
        }
        return q;
    }

    /**
     * Mexer no estoque do insumo: a operacao separada do cadastro. Espelho do
     * {@code ProdutoService.mexerNoEstoque} -- ver {@link br.com.bora.dto.MexerNoEstoqueRequest}.
     */
    @Transactional
    public Insumo mexerNoEstoque(Long id, br.com.bora.dto.MexerNoEstoqueRequest req) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        Long loja = ctx.lojaId();
        Insumo alvo = insumos.findByIdAndLojaId(id, loja).orElseThrow(() -> nf("Insumo"));
        if (req == null || req.tipo() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o tipo: DEFINIR ou AJUSTAR");
        }
        if (req.tipo() == br.com.bora.dto.MexerNoEstoqueRequest.Tipo.DEFINIR) {
            if (req.quantidade() != null && req.quantidade().signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O estoque nao pode ser negativo");
            }
            insumos.definirEstoque(id, loja, req.quantidade());
        } else {
            if (req.quantidade() == null || req.quantidade().signum() == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe quanto somar ou tirar");
            }
            if (alvo.estoque == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "\"" + alvo.nome + "\" nao controla estoque. Informe uma quantidade inicial "
                                + "antes de usar os botoes de ajuste.");
            }
            if (insumos.somarAoEstoque(id, loja, req.quantidade()) == 0) {
                BigDecimal agora = insumos.findByIdAndLojaId(id, loja).orElseThrow(() -> nf("Insumo")).estoque;
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "\"" + alvo.nome + "\" esta com " + agora + " em estoque; o ajuste deixaria o "
                                + "saldo negativo.");
            }
        }
        return insumos.findByIdAndLojaId(id, loja).orElseThrow(() -> nf("Insumo"));
    }

    /**
     * O insumo acabou. Serve para o canal que precisa RECUSAR a venda.
     *
     * <p>Carrega o nome do insumo para o lojista ler no log. O nome NAO vai para o cliente do
     * cardapio: "Polpa de Acai Organico" e vocabulario de dentro da loja, e quem esta comprando
     * precisa saber qual PRODUTO acabou, nao qual ingrediente. Quem chama monta a mensagem.</p>
     */
    public static class SemInsumo extends RuntimeException {
        public final String insumo;

        public SemInsumo(String insumo) {
            super("Sem estoque do insumo: " + insumo);
            this.insumo = insumo;
        }
    }

    /**
     * O insumo de um ADICIONAL acabou. Serve para o canal que precisa recusar a venda.
     *
     * <p>Carrega os dois nomes porque eles vao para leitores diferentes: o cliente do cardapio
     * precisa saber que a <b>granola</b> acabou -- o adicional que ele marcou na tela -- e o lojista
     * precisa saber qual <b>insumo</b> repor. Dizer "o produto acabou" seria mentira: o acai tem, o
     * que falta e o extra.</p>
     */
    public static class SemAdicional extends RuntimeException {
        public final String adicional;
        public final String insumo;

        public SemAdicional(String adicional, String insumo) {
            super("Sem estoque do insumo \"" + insumo + "\" do adicional \"" + adicional + "\"");
            this.adicional = adicional;
            this.insumo = insumo;
        }
    }

    /**
     * Consome os insumos dos ADICIONAIS escolhidos e devolve quanto eles custam por unidade vendida.
     *
     * <h2>O item 2.6 da varredura</h2>
     * <p>Adicional nao saia do estoque nem entrava no custo, porque o cadastro nao tinha onde guardar
     * isso. Numa acaiteria metade do valor da venda esta nos adicionais: o estoque de granola e de
     * leite em po ia embora sem nenhum registro, e a margem de cada venda saia melhor do que era.</p>
     *
     * <p>Quem passa aqui e a lista que vai ser <b>gravada no item do pedido</b>, nao o cadastro de
     * hoje. E o mesmo cuidado do nome e do preco: os adicionais sao apagados e recriados a cada
     * gravacao do produto, com ids novos, entao o cadastro de amanha nao diz o que saiu hoje.</p>
     *
     * @param escolhidos  o que o cliente marcou, com insumo e quantidade de quando pediu
     * @param qtdPedido   quantas unidades do item; cada uma leva os adicionais escolhidos
     * @param exigirEstoque {@code true} recusa quando falta (cardapio publico); {@code false} deixa
     *                      ir a negativo e so avisa no log (balcao: o operador esta com o produto na
     *                      mao, e travar a venda com o cliente na frente e pior que o saldo negativo)
     * @return o custo dos adicionais por UNIDADE vendida; {@code ZERO} quando nenhum tem insumo
     * @throws SemAdicional com {@code exigirEstoque} e sem saldo
     */
    @Transactional
    public BigDecimal consumirAdicionais(Long lojaId, List<ComplementoService.Escolhido> escolhidos,
                                         int qtdPedido, boolean exigirEstoque) {
        if (escolhidos == null || escolhidos.isEmpty() || qtdPedido <= 0) return BigDecimal.ZERO;
        BigDecimal custoUnit = BigDecimal.ZERO;
        for (ComplementoService.Escolhido e : escolhidos) {
            if (!e.consomeInsumo()) continue;
            Insumo in = insumos.findByIdAndLojaId(e.insumoId(), lojaId).orElse(null);
            if (in == null) {
                // Nao pode acontecer: a V55 tem chave estrangeira e o `excluir` recusa. Se acontecer,
                // e dado de antes da V55 ou alguem mexeu no banco na mao -- e o aviso vale mais que
                // um erro, porque a venda esta no meio.
                log.warn("Loja {}: o adicional \"{}\" aponta para o insumo {}, que nao existe. O "
                                + "estoque dele nao foi baixado e o custo da venda sai menor.",
                        lojaId, e.nome(), e.insumoId());
                continue;
            }
            BigDecimal porUnidade = e.quantidade();
            custoUnit = custoUnit.add((in.custo == null ? BigDecimal.ZERO : in.custo).multiply(porUnidade));
            BigDecimal precisa = porUnidade.multiply(BigDecimal.valueOf(qtdPedido));
            if (exigirEstoque) {
                // Quem recusa e o BANCO, nao uma conferencia antes: duas vendas ao mesmo tempo leriam
                // o mesmo saldo e as duas passariam.
                if (insumos.consumirSeTiver(in.id, lojaId, precisa) == 0) {
                    throw new SemAdicional(e.nome(), in.nome);
                }
            } else {
                // O aviso e aproximado de proposito: o saldo lido aqui pode mudar antes da baixa. Vale
                // como alerta de contagem errada, que e o que o lojista precisa saber -- e sem ele o
                // numero ia a negativo calado, que foi o defeito do balcao no item 2.2.
                if (in.estoque != null && in.estoque.compareTo(precisa) < 0) {
                    log.warn("Loja {}: a venda levou {} de \"{}\" pelo adicional \"{}\", e o saldo "
                                    + "era {}. O estoque vai ficar NEGATIVO -- a contagem desse insumo "
                                    + "esta errada. A venda passou de proposito.",
                            lojaId, precisa, in.nome, e.nome(), in.estoque);
                }
                insumos.mexerNoEstoque(in.id, lojaId, precisa.negate());
            }
        }
        return custoUnit;
    }

    /**
     * Desfaz o {@link #consumirAdicionais} de um item que nao vai acontecer. Espelho exato: mesma
     * lista, mesma conta, sinal trocado. Nunca recusa -- cancelar nao pode travar.
     *
     * @return quantos adicionais tiveram insumo devolvido
     */
    @Transactional
    public int devolverAdicionais(Long lojaId, List<ComplementoService.Escolhido> escolhidos, int qtdPedido) {
        if (escolhidos == null || escolhidos.isEmpty() || qtdPedido <= 0) return 0;
        int devolvidos = 0;
        for (ComplementoService.Escolhido e : escolhidos) {
            if (!e.consomeInsumo()) continue;
            Insumo in = insumos.findByIdAndLojaId(e.insumoId(), lojaId).orElse(null);
            if (in == null) continue;
            insumos.mexerNoEstoque(in.id, lojaId, e.quantidade().multiply(BigDecimal.valueOf(qtdPedido)));
            devolvidos++;
        }
        return devolvidos;
    }

    /**
     * Consome a ficha técnica de um produto numa venda: baixa o estoque dos insumos
     * e devolve o custo unitário (soma insumo.custo × quantidade). null se o produto não tem ficha.
     *
     * <p>Deixa o saldo ir a negativo. Certo no balcao e no marketplace — ver
     * {@link #consumirFicha(Long, Long, int, boolean)}.</p>
     */
    @Transactional
    public BigDecimal consumirFicha(Long lojaId, Long produtoId, int qtdPedido) {
        return consumirFicha(lojaId, produtoId, qtdPedido, false);
    }

    /**
     * O mesmo, podendo RECUSAR quando falta insumo.
     *
     * <h2>Por que os dois modos</h2>
     * <p>Depende de quem esta com o produto na mao. No <b>balcao</b> o operador esta: travar a venda
     * com o cliente na frente e pior que o saldo negativo, que pelo menos denuncia que a contagem
     * esta errada. No <b>marketplace</b> o pedido ja foi aceito do outro lado. No <b>cardapio
     * publico</b> o cliente nao tem nada na mao — vender e depois cancelar e pior que dizer "acabou"
     * na hora.</p>
     *
     * <p>Com {@code exigirEstoque}, a recusa vem do BANCO ({@code consumirSeTiver}), nao de uma
     * conferencia em Java antes: duas vendas simultaneas leriam o mesmo saldo e as duas passariam.</p>
     *
     * <p><b>Quem chama com {@code true} tem que estar dentro de uma transacao.</b> A ficha baixa
     * insumo por insumo; se o terceiro faltar, os dois primeiros ja baixaram, e o
     * {@link SemInsumo} so desfaz isso se houver transacao para desfazer.</p>
     *
     * @throws SemInsumo quando {@code exigirEstoque} e algum insumo da ficha nao tem o suficiente
     */
    @Transactional
    public BigDecimal consumirFicha(Long lojaId, Long produtoId, int qtdPedido, boolean exigirEstoque) {
        if (produtoId == null) return null;
        List<ProdutoInsumo> ficha = fichas.findByLojaIdAndProdutoId(lojaId, produtoId);
        if (ficha.isEmpty()) return null;
        BigDecimal custoUnit = BigDecimal.ZERO;
        int consumidos = 0;
        int fantasmas = 0;
        for (ProdutoInsumo pi : ficha) {
            Insumo in = insumos.findByIdAndLojaId(pi.insumoId, lojaId).orElse(null);
            if (in == null) {
                // Ficha fantasma: o insumo foi apagado e a linha da ficha ficou (nao ha chave
                // estrangeira em produto_insumo). O `excluir` agora recusa criar isso, mas base antiga
                // ja tem -- conferido na producao em 21/10/2026.
                fantasmas++;
                continue;
            }
            consumidos++;
            BigDecimal qtdUnit = pi.quantidade == null ? BigDecimal.ZERO : pi.quantidade;

            // Linha que nao consome nada nao mexe em estoque, e espelha o `fichaDaParaFazer`, que ja
            // pulava a linha com quantidade <= 0 (achado pelo QA em 26/10/2026).
            //
            // Sem isto, o cardapio OFERECIA o produto e o checkout RECUSAVA: com `qtd = 0` a condicao
            // do banco e `estoque >= 0`, e um insumo que o balcao deixou negativo (o balcao deixa, de
            // proposito) reprovava a condicao e levantava SemInsumo por causa de uma linha que nao
            // tira nada. A linha continua contando como `consumidos`: ela aponta para um insumo que
            // existe, logo a ficha existe -- o que muda e so nao mexer no saldo.
            //
            // A quantidade negativa passou a ser recusada na gravacao; aqui a guarda fica tambem
            // porque a base ja tem ficha gravada antes dessa regra.
            //
            // O CUSTO fica depois da guarda de proposito, e nao antes: o CMV e `custo x quantidade`,
            // entao a linha negativa ABATIA o custo do produto -- dava para zerar o CMV de um lanche
            // inteiro e inflar a margem no relatorio que o dono usa para decidir preco. Medido: uma
            // linha de -0,5 kg de polpa a R$ 20 tirava R$ 10 do custo do item.
            if (qtdUnit.signum() <= 0) continue;

            custoUnit = custoUnit.add((in.custo == null ? BigDecimal.ZERO : in.custo).multiply(qtdUnit));
            BigDecimal precisa = qtdUnit.multiply(BigDecimal.valueOf(qtdPedido));
            // Baixa no banco, nao em Java: duas vendas simultaneas do mesmo produto liam o mesmo
            // estoque antes de qualquer uma gravar, e uma das baixas desaparecia.
            if (exigirEstoque) {
                if (insumos.consumirSeTiver(in.id, lojaId, precisa) == 0) throw new SemInsumo(in.nome);
            } else {
                insumos.mexerNoEstoque(in.id, lojaId, precisa.negate());
            }
        }

        if (fantasmas > 0) {
            log.warn("Produto {} da loja {}: {} linha(s) da ficha tecnica aponta(m) para insumo que nao "
                            + "existe mais. O custo deste item sai MENOR do que e. Conserte a ficha em "
                            + "Produtos; o insumo apagado aparece como \"?\".",
                    produtoId, lojaId, fantasmas);
        }

        if (consumidos == 0) {
            // NADA foi consumido: a ficha existe no papel, mas todos os insumos dela foram apagados.
            // Devolver ZERO aqui era a mentira: quem chama faz `if (custoFicha == null) baixarEstoque`,
            // entao o produto NAO tinha o estoque baixado -- e o item era gravado com
            // `consumiuFicha = true`, o que fazia o cancelamento tambem nao devolver nada.
            //
            // Devolvendo null, o produto se comporta como produto SEM ficha: baixa o estoque dele
            // mesmo, grava `consumiuFicha = false`, e o cancelamento devolve a unidade certa. E a
            // melhor verdade disponivel.
            log.warn("Produto {} da loja {}: a ficha tecnica existe mas NENHUM insumo dela existe mais. "
                            + "Tratando como produto sem ficha -- o estoque do proprio produto sera "
                            + "baixado. Conserte a ficha em Produtos.",
                    produtoId, lojaId);
            return null;
        }
        return custoUnit;
    }

    /**
     * Da para fazer {@code quantas} unidades deste produto com o estoque de insumo que existe hoje?
     *
     * <p>Serve para o cardapio marcar o produto como esgotado ANTES de o cliente montar o carrinho.
     * Descobrir que faltou so no "confirmar pedido" e a pior forma de contar isso a ele — a mesma
     * razao pela qual o cardapio ja avisa que a loja esta fechada.</p>
     *
     * <p>E uma FOTO, nao uma reserva: entre a leitura e o pedido alguem pode comprar. Quem garante de
     * verdade e o {@code consumirSeTiver} no banco, na hora de gravar.</p>
     *
     * <p>Produto sem ficha devolve true: nao ha insumo para faltar.</p>
     */
    public boolean fichaDaParaFazer(Long lojaId, Long produtoId, int quantas) {
        if (produtoId == null) return true;
        List<ProdutoInsumo> ficha = fichas.findByLojaIdAndProdutoId(lojaId, produtoId);
        if (ficha.isEmpty()) return true;
        for (ProdutoInsumo pi : ficha) {
            Insumo in = insumos.findByIdAndLojaId(pi.insumoId, lojaId).orElse(null);
            // Insumo que o lojista apagou nao bloqueia venda — a ficha fica fantasma e o consumo
            // tambem pula a linha. Ver o item 2.5 da varredura, que e outro problema.
            if (in == null || in.estoque == null) continue;
            BigDecimal qtdUnit = pi.quantidade == null ? BigDecimal.ZERO : pi.quantidade;
            if (qtdUnit.signum() <= 0) continue;
            if (in.estoque.compareTo(qtdUnit.multiply(BigDecimal.valueOf(quantas))) < 0) return false;
        }
        return true;
    }

    /**
     * Desfaz o {@link #consumirFicha} de um item que nao vai acontecer.
     *
     * <p>Espelho exato do consumo: mesma ficha, mesma conta, sinal trocado. So mexe no insumo que
     * controla estoque ({@code estoque != null}), igual ao consumo — quem nao controla continua sem
     * numero nenhum, em vez de nascer com um saldo inventado no primeiro cancelamento.</p>
     *
     * <p>Devolve false quando nao havia ficha para devolver.</p>
     */
    @Transactional
    public boolean devolverFicha(Long lojaId, Long produtoId, int qtdPedido) {
        if (produtoId == null) return false;
        List<ProdutoInsumo> ficha = fichas.findByLojaIdAndProdutoId(lojaId, produtoId);
        if (ficha.isEmpty()) return false;
        for (ProdutoInsumo pi : ficha) {
            Insumo in = insumos.findByIdAndLojaId(pi.insumoId, lojaId).orElse(null);
            if (in == null) continue;
            BigDecimal qtdUnit = pi.quantidade == null ? BigDecimal.ZERO : pi.quantidade;
            // Espelho do consumo, e por isso a mesma guarda: linha que nao consumiu nada nao tem nada
            // para devolver. Com quantidade negativa gravada antes da regra de 26/10/2026, devolver
            // TIRARIA do estoque -- cancelar viraria baixa.
            if (qtdUnit.signum() <= 0) continue;
            insumos.mexerNoEstoque(in.id, lojaId, qtdUnit.multiply(BigDecimal.valueOf(qtdPedido)));
        }
        return true;
    }

    private ResponseStatusException nf(String o) { return new ResponseStatusException(HttpStatus.NOT_FOUND, o + " não encontrado"); }
}
