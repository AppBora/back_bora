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
    private final AuthContext ctx;

    public InsumoService(InsumoRepository insumos, ProdutoInsumoRepository fichas, ProdutoRepository produtos, AuthContext ctx) {
        this.insumos = insumos;
        this.fichas = fichas;
        this.produtos = produtos;
        this.ctx = ctx;
    }

    // ---- CRUD insumos ----
    public List<Insumo> listar() { return insumos.findByLojaIdOrderByNomeAsc(ctx.lojaId()); }

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
        insumos.delete(alvo);
    }

    // ---- Ficha técnica ----
    public List<Map<String, Object>> ficha(Long produtoId) {
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
            pi.quantidade = new BigDecimal(String.valueOf(it.getOrDefault("quantidade", "0")));
            fichas.save(pi);
        }
        return ficha(produtoId);
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
            insumos.mexerNoEstoque(in.id, lojaId, qtdUnit.multiply(BigDecimal.valueOf(qtdPedido)));
        }
        return true;
    }

    private ResponseStatusException nf(String o) { return new ResponseStatusException(HttpStatus.NOT_FOUND, o + " não encontrado"); }
}
