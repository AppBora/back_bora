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
        alvo.custo = i.custo == null ? BigDecimal.ZERO : i.custo; alvo.estoque = i.estoque; alvo.estoqueMinimo = i.estoqueMinimo;
        if (alvo.nome == null || alvo.nome.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do insumo é obrigatório");
        return insumos.save(alvo);
    }

    public void excluir(Long id) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        insumos.delete(insumos.findByIdAndLojaId(id, ctx.lojaId()).orElseThrow(() -> nf("Insumo"))); }

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
        for (ProdutoInsumo pi : ficha) {
            Insumo in = insumos.findByIdAndLojaId(pi.insumoId, lojaId).orElse(null);
            if (in == null) continue;
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
