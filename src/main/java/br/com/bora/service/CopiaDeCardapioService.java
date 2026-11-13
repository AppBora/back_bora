package br.com.bora.service;

import br.com.bora.entity.CategoriaOrdem;
import br.com.bora.entity.ComplementoGrupo;
import br.com.bora.entity.ComplementoItem;
import br.com.bora.entity.ConfiguracaoLoja;
import br.com.bora.entity.Insumo;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Produto;
import br.com.bora.entity.ProdutoInsumo;
import br.com.bora.entity.TaxaEntrega;
import br.com.bora.repository.CategoriaOrdemRepository;
import br.com.bora.repository.ComplementoGrupoRepository;
import br.com.bora.repository.ComplementoItemRepository;
import br.com.bora.repository.ConfiguracaoLojaRepository;
import br.com.bora.repository.InsumoRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.ProdutoInsumoRepository;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.repository.TaxaEntregaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copia o cardapio de uma unidade para outra da MESMA empresa.
 *
 * <h2>Por que isto existe (13/11/2026)</h2>
 * <p>Abrir a 6a unidade de um cliente que ja tem cardapio pronto era digitar tudo de novo. Medido
 * na loja 18 (Zira Montreal) no dia em que este servico nasceu: <b>44 produtos em 13 categorias,
 * 59 grupos de complemento, 639 itens de complemento, 43 insumos e 6 taxas de entrega</b>. Os 639
 * itens sao o numero que decide: a mao, isso nao e tarefa, e uma semana de erro de digitacao. E a
 * 99Food <b>analisa o cardapio por pessoa</b> e exige que o preco seja o mesmo do balcao -- o
 * cardapio da unidade nova precisa sair <b>identico</b> ao da irma, nao "parecido".</p>
 *
 * <h2>A regra que faz este servico ser seguro: nenhum id da origem atravessa</h2>
 * <p>Quase toda linha copiada aponta para outra linha: o grupo de complemento aponta para o
 * produto, o item aponta para o grupo, e o item e a ficha tecnica apontam para o <b>insumo</b>.
 * Copiar o id cru faria o cardapio da loja nova apontar para o insumo da loja velha -- e aí uma
 * venda na unidade nova <b>baixaria o estoque da outra unidade</b>, calada. Por isso cada tabela e
 * copiada atraves de um <b>mapa id-da-origem -> id-do-destino</b>, e o id so cruza por esse mapa.
 * Chave que falta no mapa (ponteiro solto que ja existia na origem) NAO e copiada: entra no
 * relatorio como ignorada, nunca como id da origem.</p>
 *
 * <h2>O que NAO e copiado, de proposito</h2>
 * <ul>
 *   <li><b>Quantidade de estoque.</b> O cadastro vem; o saldo nasce zero. Copiar "12 kg de acai"
 *       para uma loja que nao tem acai nenhum e inventar inventario. A <b>decisao</b> de controlar
 *       estoque e preservada: coluna nula na origem continua nula no destino (nulo = nao controla;
 *       zero = controla e esta zerado -- os dois nao sao a mesma coisa).</li>
 *   <li><b>Subdominio</b> (e unico por loja), <b>nome de exibicao</b> (a unidade nova tem o seu) e
 *       <b>custo fixo mensal</b> (e numero daquela unidade, nao da rede).</li>
 *   <li><b>Horarios, formas de pagamento, cupons, promocoes, clientes, pedidos e integracoes.</b>
 *       Horario e forma de pagamento a loja nova ja recebe semeados do
 *       {@link ProvisionamentoService}; o resto e historia de outra loja.</li>
 * </ul>
 *
 * <h2>Uma vez so</h2>
 * <p>O destino tem de estar <b>vazio</b> de cardapio. Copiar em cima do que existe duplicaria o
 * cardapio inteiro e nao ha como desfazer pela tela -- e recusa com a lista do que ja esta la, que
 * e informacao, em vez de um "nao posso".</p>
 */
@Service
public class CopiaDeCardapioService {

    private static final org.slf4j.Logger AUDITORIA = org.slf4j.LoggerFactory.getLogger("AUDITORIA");

    private final LojaRepository lojas;
    private final ProdutoRepository produtos;
    private final ComplementoGrupoRepository grupos;
    private final ComplementoItemRepository itens;
    private final InsumoRepository insumos;
    private final ProdutoInsumoRepository fichas;
    private final CategoriaOrdemRepository categorias;
    private final TaxaEntregaRepository taxas;
    private final ConfiguracaoLojaRepository configs;

    public CopiaDeCardapioService(LojaRepository lojas, ProdutoRepository produtos,
                                  ComplementoGrupoRepository grupos, ComplementoItemRepository itens,
                                  InsumoRepository insumos, ProdutoInsumoRepository fichas,
                                  CategoriaOrdemRepository categorias, TaxaEntregaRepository taxas,
                                  ConfiguracaoLojaRepository configs) {
        this.lojas = lojas;
        this.produtos = produtos;
        this.grupos = grupos;
        this.itens = itens;
        this.insumos = insumos;
        this.fichas = fichas;
        this.categorias = categorias;
        this.taxas = taxas;
        this.configs = configs;
    }

    /**
     * Copia o cardapio da {@code origemId} para a {@code destinoId}, numa transacao so.
     *
     * @return o que foi copiado, tabela por tabela -- numero que o operador confere contra a tela
     *         da loja origem, em vez de um "pronto" sem conta.
     */
    @Transactional
    public Map<String, Object> copiar(Long origemId, Long destinoId) {
        if (origemId == null || destinoId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe a loja de origem e a de destino.");
        }
        if (origemId.equals(destinoId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A loja de origem e a de destino sao a mesma (#" + origemId + ").");
        }
        Loja origem = lojas.findById(origemId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Loja de origem #" + origemId + " nao existe."));
        Loja destino = lojas.findById(destinoId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Loja de destino #" + destinoId + " nao existe."));

        // A MESMA EMPRESA e a unica autorizacao que vale aqui. Sem esta linha, "copiar cardapio"
        // seria um jeito de o administrador da plataforma mover o cardapio de um cliente para o
        // concorrente dele -- com dois cliques e sem deixar nada na tela de ninguem.
        if (origem.empresaId == null || destino.empresaId == null
                || !origem.empresaId.equals(destino.empresaId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "As duas lojas tem de ser do MESMO cliente (mesmo CNPJ). A #" + origemId
                            + " e a #" + destinoId + " estao em empresas diferentes.");
        }

        List<String> jaTem = new ArrayList<>();
        long temProdutos = produtos.countByLojaId(destinoId);
        if (temProdutos > 0) jaTem.add(temProdutos + " produto(s)");
        int temInsumos = insumos.findByLojaIdOrderByNomeAsc(destinoId).size();
        if (temInsumos > 0) jaTem.add(temInsumos + " insumo(s)");
        int temTaxas = taxas.findByLojaIdOrderByBairroAsc(destinoId).size();
        if (temTaxas > 0) jaTem.add(temTaxas + " taxa(s) de entrega");
        if (!jaTem.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A loja #" + destinoId + " ja tem " + String.join(", ", jaTem)
                            + ". A copia so roda em loja com o cardapio vazio, senao duplica tudo.");
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("origem", origemId);
        r.put("destino", destinoId);

        // 1. INSUMOS primeiro: produto, ficha tecnica e complemento todos apontam para eles.
        Map<Long, Long> mapaInsumo = new HashMap<>();
        for (Insumo o : insumos.findByLojaIdOrderByNomeAsc(origemId)) {
            Insumo n = new Insumo();
            n.lojaId = destinoId;
            n.nome = o.nome;
            n.unidade = o.unidade;
            n.custo = o.custo;
            n.estoque = o.estoque == null ? null : BigDecimal.ZERO;  // cadastro sim, saldo nao
            n.estoqueMinimo = o.estoqueMinimo;
            mapaInsumo.put(o.id, insumos.save(n).id);
        }
        r.put("insumos", mapaInsumo.size());

        // 2. PRODUTOS.
        Map<Long, Long> mapaProduto = new HashMap<>();
        List<Produto> daOrigem = produtos.findByLojaIdOrderByNomeAsc(origemId);
        for (Produto o : daOrigem) {
            Produto n = new Produto();
            n.lojaId = destinoId;
            n.nome = o.nome;
            n.categoria = o.categoria;
            n.preco = o.preco;
            n.custo = o.custo;
            n.estoque = o.estoque == null ? null : 0;               // idem: a decisao, nao o saldo
            n.estoqueMinimo = o.estoqueMinimo;
            n.imagemUrl = o.imagemUrl;
            n.ativo = o.ativo;
            // O codigo externo e o codigo DESTE produto no iFood/99 -- e por ele que o pedido do
            // marketplace acha o produto. Ele vale por loja: copiado, o pedido da unidade nova
            // poderia casar com o produto da unidade velha. Nasce vazio, e o sistema cai no
            // casamento por nome ate alguem cadastrar o codigo de la.
            n.codigoExterno = null;
            mapaProduto.put(o.id, produtos.save(n).id);
        }
        r.put("produtos", mapaProduto.size());

        // 3. FICHA TECNICA (produto_insumo): remapeia produto E insumo.
        int fichasCopiadas = 0, fichasIgnoradas = 0;
        for (ProdutoInsumo o : fichas.findByLojaIdAndProdutoIdIn(origemId, mapaProduto.keySet())) {
            Long produtoNovo = mapaProduto.get(o.produtoId);
            Long insumoNovo = o.insumoId == null ? null : mapaInsumo.get(o.insumoId);
            if (produtoNovo == null || insumoNovo == null) { fichasIgnoradas++; continue; }
            ProdutoInsumo n = new ProdutoInsumo();
            n.lojaId = destinoId;
            n.produtoId = produtoNovo;
            n.insumoId = insumoNovo;
            n.quantidade = o.quantidade;
            fichas.save(n);
            fichasCopiadas++;
        }
        r.put("fichasTecnicas", fichasCopiadas);
        if (fichasIgnoradas > 0) r.put("fichasIgnoradas", fichasIgnoradas);

        // 4. GRUPOS de complemento: remapeia produto.
        Map<Long, Long> mapaGrupo = new HashMap<>();
        for (ComplementoGrupo o : grupos.findByLojaIdAndProdutoIdInOrderById(origemId, mapaProduto.keySet())) {
            Long produtoNovo = mapaProduto.get(o.produtoId);
            if (produtoNovo == null) continue;
            ComplementoGrupo n = new ComplementoGrupo();
            n.lojaId = destinoId;
            n.produtoId = produtoNovo;
            n.nome = o.nome;
            n.minimo = o.minimo;
            n.maximo = o.maximo;
            mapaGrupo.put(o.id, grupos.save(n).id);
        }
        r.put("gruposDeComplemento", mapaGrupo.size());

        // 5. ITENS de complemento: remapeia grupo E insumo.
        int itensCopiados = 0, itensSemInsumo = 0;
        if (!mapaGrupo.isEmpty()) {
            for (ComplementoItem o : itens.findByLojaIdAndGrupoIdInOrderById(origemId, mapaGrupo.keySet())) {
                Long grupoNovo = mapaGrupo.get(o.grupoId);
                if (grupoNovo == null) continue;
                ComplementoItem n = new ComplementoItem();
                n.lojaId = destinoId;
                n.grupoId = grupoNovo;
                n.nome = o.nome;
                n.preco = o.preco;
                if (o.insumoId == null) {
                    n.insumoId = null;
                    n.quantidade = null;
                } else {
                    Long insumoNovo = mapaInsumo.get(o.insumoId);
                    if (insumoNovo == null) {
                        // Ponteiro solto que ja existia na origem. O adicional vem (o cliente
                        // precisa poder pedir "com granola"), so sem baixar estoque -- que e
                        // exatamente o que ele fazia na origem, apontando para insumo inexistente.
                        n.insumoId = null;
                        n.quantidade = null;
                        itensSemInsumo++;
                    } else {
                        n.insumoId = insumoNovo;
                        n.quantidade = o.quantidade;
                    }
                }
                itens.save(n);
                itensCopiados++;
            }
        }
        r.put("itensDeComplemento", itensCopiados);
        if (itensSemInsumo > 0) r.put("itensQueComiamInsumoInexistente", itensSemInsumo);

        // 6. ORDEM das categorias no cardapio.
        int ordens = 0;
        for (CategoriaOrdem o : categorias.findByLojaIdOrderByPosicaoAsc(origemId)) {
            CategoriaOrdem n = new CategoriaOrdem();
            n.lojaId = destinoId;
            n.nome = o.nome;
            n.posicao = o.posicao;
            categorias.save(n);
            ordens++;
        }
        r.put("ordemDeCategorias", ordens);

        // 7. TAXAS de entrega. Bairro da unidade nova e outro -- mas a lista da irma e um ponto de
        // partida honesto, e desde a v160 bairro fora da lista RECUSA o pedido em vez de cobrar
        // R$ 0,00 calado: unidade nova sem taxa nenhuma nao fecha pedido de entrega.
        int taxasCopiadas = 0;
        for (TaxaEntrega o : taxas.findByLojaIdOrderByBairroAsc(origemId)) {
            TaxaEntrega n = new TaxaEntrega();
            n.lojaId = destinoId;
            n.bairro = o.bairro;
            n.taxa = o.taxa;
            n.tempoMin = o.tempoMin;
            n.ativo = o.ativo;
            taxas.save(n);
            taxasCopiadas++;
        }
        r.put("taxasDeEntrega", taxasCopiadas);

        // 8. APARENCIA. Unidade de rede tem de nascer com a marca do cliente, nao com o azul
        // padrao do Bora: o cardapio publico da unidade nova e a mesma marca para o comprador.
        r.put("aparencia", copiarAparencia(origemId, destinoId));

        AUDITORIA.info("Cardapio copiado da loja {} para a loja {} (empresa {}): {}",
                origemId, destinoId, destino.empresaId, r);
        return r;
    }

    /** Marca e numeros de cardapio. Deixa de fora o que e identidade da unidade. */
    private String copiarAparencia(Long origemId, Long destinoId) {
        ConfiguracaoLoja o = configs.findByLojaId(origemId).orElse(null);
        if (o == null) return "origem sem configuracao — nada copiado";
        ConfiguracaoLoja d = configs.findByLojaId(destinoId).orElse(null);
        if (d == null) return "destino sem configuracao — nada copiado";
        d.nomeSistema = o.nomeSistema;
        d.logoUrl = o.logoUrl;
        d.bannerUrl = o.bannerUrl;
        d.corPrimaria = o.corPrimaria;
        d.corSecundaria = o.corSecundaria;
        d.mostrarMarcaBora = o.mostrarMarcaBora;
        d.cashbackPercentual = o.cashbackPercentual;
        d.aliquotaImposto = o.aliquotaImposto;
        configs.save(d);
        // Dito em vez de subentendido: estes tres sao os que NAO vao, e o operador precisa saber.
        return "marca, cores, cashback e aliquota copiados; nome de exibicao, subdominio e custo "
                + "fixo mensal ficaram os da loja nova";
    }
}
