package br.com.bora.service;

import br.com.bora.entity.CategoriaOrdem;
import br.com.bora.repository.CategoriaOrdemRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * A ordem em que as secoes do cardapio aparecem para o cliente e para o balconista.
 *
 * <h2>O que isto resolve</h2>
 * <p>Categoria neste sistema e um campo de texto dentro de cada produto, e as duas telas que a
 * mostram ordenavam em A-Z. O cardapio de papel de uma acaiteria nao abre no alfabeto: abre no
 * carro-chefe. Ver a V64 para a medicao que originou isto.</p>
 *
 * <h2>A regra, em uma frase</h2>
 * <p>Quem esta na tabela sai na posicao gravada; <b>quem nao esta sai no fim, em ordem
 * alfabetica</b>. Loja que nunca mexeu na ordem continua exatamente como era antes.</p>
 */
@Service
public class CategoriaOrdemService {

    /** Teto de seguranca para a lista que a tela manda. A maior loja da base tem 14 categorias. */
    static final int MAXIMO_DE_CATEGORIAS = 200;
    /** A mesma largura de {@code produto.categoria}. Ver V64. */
    static final int TAMANHO_MAXIMO_DO_NOME = 80;

    private final CategoriaOrdemRepository repo;
    private final ProdutoRepository produtos;
    private final AuthContext ctx;

    public CategoriaOrdemService(CategoriaOrdemRepository repo, ProdutoRepository produtos, AuthContext ctx) {
        this.repo = repo;
        this.produtos = produtos;
        this.ctx = ctx;
    }

    /**
     * As categorias que a loja realmente tem, ja na ordem em que o cliente as ve.
     *
     * <p>A lista vem dos PRODUTOS, nao da tabela de ordem: e o produto que define quais categorias
     * existem. A tabela so diz em que ordem elas saem. Por isso categoria que ficou sem nenhum
     * produto some daqui sozinha, sem precisar de limpeza.</p>
     */
    public List<Map<String, Object>> listar() {
        Long lojaId = ctx.lojaId();
        Map<String, Integer> quantos = new LinkedHashMap<>();
        for (Object[] r : produtos.resumoDaLoja(lojaId)) {
            String c = rotulo((String) r[2]);
            quantos.merge(c, 1, Integer::sum);
        }
        Map<String, Integer> posicoes = posicoesDaLoja(lojaId);
        List<String> nomes = new ArrayList<>(quantos.keySet());
        nomes.sort(comparador(posicoes));
        List<Map<String, Object>> saida = new ArrayList<>();
        for (int i = 0; i < nomes.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nome", nomes.get(i));
            m.put("produtos", quantos.get(nomes.get(i)));
            m.put("posicao", i);
            // Para a tela poder dizer "esta loja nunca escolheu uma ordem" sem adivinhar.
            m.put("gravada", posicoes.containsKey(nomes.get(i)));
            saida.add(m);
        }
        return saida;
    }

    /**
     * Grava a ordem inteira de uma vez: apaga e recria, como o salvar dos complementos.
     *
     * <p><b>Por que a lista inteira, e nao "sobe um/desce um":</b> mover uma categoria muda a
     * posicao de todas as outras. Mandar so a que se moveu obrigaria o servidor a adivinhar o
     * estado da tela, e duas abas abertas acabariam com duas categorias na mesma posicao.</p>
     */
    @org.springframework.transaction.annotation.Transactional
    public List<Map<String, Object>> salvar(List<String> nomes) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        Long lojaId = ctx.lojaId();
        if (nomes == null) nomes = List.of();
        if (nomes.size() > MAXIMO_DE_CATEGORIAS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ordem com categorias demais (" + nomes.size() + "). O limite é " + MAXIMO_DE_CATEGORIAS + ".");
        }
        // LinkedHashSet: mantem a ordem que a tela mandou e descarta repetido sem erro -- repetir
        // um nome e um escorregao da tela, nao um pedido do lojista, e nao vale derrubar o salvar.
        LinkedHashSet<String> limpos = new LinkedHashSet<>();
        for (String n : nomes) {
            String c = rotulo(n);
            if (c.length() > TAMANHO_MAXIMO_DO_NOME) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Nome de categoria longo demais: \"" + c + "\".");
            }
            limpos.add(c);
        }
        repo.deleteByLojaId(lojaId);
        // flush antes de inserir: o indice unico (loja_id, nome) recusaria o INSERT de um nome que
        // o DELETE desta mesma transacao ainda nao tivesse mandado ao banco.
        repo.flush();
        int i = 0;
        for (String c : limpos) {
            CategoriaOrdem o = new CategoriaOrdem();
            o.lojaId = lojaId;
            o.nome = c;
            o.posicao = i++;
            repo.save(o);
        }
        return listar();
    }

    /** A posicao de cada categoria desta loja. Vazio quando a loja nunca escolheu uma ordem. */
    public Map<String, Integer> posicoesDaLoja(Long lojaId) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (CategoriaOrdem o : repo.findByLojaIdOrderByPosicaoAsc(lojaId)) {
            m.put(rotulo(o.nome), o.posicao == null ? 0 : o.posicao);
        }
        return m;
    }

    /**
     * Quem tem posicao sai primeiro, na posicao gravada; quem nao tem sai no fim, em A-Z.
     *
     * <p>O desempate alfabetico usa {@code pt-BR} via {@link java.text.Collator}: com
     * {@code compareTo} seco, "Água" cairia depois de "Bebidas" porque o acento tem codigo maior
     * que o de qualquer letra sem acento -- e o cliente leria o cardapio fora de ordem.</p>
     */
    public static Comparator<String> comparador(Map<String, Integer> posicoes) {
        java.text.Collator alfabeto = java.text.Collator.getInstance(new java.util.Locale("pt", "BR"));
        return Comparator
                .comparingInt((String c) -> posicoes.getOrDefault(c, Integer.MAX_VALUE))
                .thenComparing(c -> c, alfabeto::compare);
    }

    /** Categoria vazia aparece no cardapio como "Outros" -- e esse e o nome que o cliente le. */
    public static String rotulo(String c) {
        String s = c == null ? "" : c.trim();
        return s.isEmpty() ? "Outros" : s;
    }
}
