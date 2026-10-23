package br.com.bora.service;

import br.com.bora.entity.Produto;
import br.com.bora.repository.ProdutoRepository;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class ProdutoService {

    private final ProdutoRepository repo;
    private final br.com.bora.repository.ProdutoInsumoRepository fichas;
    private final br.com.bora.repository.ComplementoGrupoRepository grupos;
    private final br.com.bora.repository.ComplementoItemRepository opcoes;
    private final AuthContext ctx;

    public ProdutoService(ProdutoRepository repo,
                          br.com.bora.repository.ProdutoInsumoRepository fichas,
                          br.com.bora.repository.ComplementoGrupoRepository grupos,
                          br.com.bora.repository.ComplementoItemRepository opcoes,
                          AuthContext ctx) {
        this.repo = repo;
        this.fichas = fichas;
        this.grupos = grupos;
        this.opcoes = opcoes;
        this.ctx = ctx;
    }

    /**
     * A lista do painel, sem a foto em base64. Ver {@link br.com.bora.dto.ProdutoResumo}.
     *
     * <p>Quem precisa da foto de um produto so pede a dela, pela URL que vem no {@code imagemUrl} —
     * e o navegador cacheia. O caminho de SALVAR nao muda: a tela so manda {@code imagemUrl} quando o
     * lojista escolhe foto nova, e o {@code atualizar} so mexe na coluna quando o campo vem.</p>
     */
    public List<br.com.bora.dto.ProdutoResumo> listar() {
        Long lojaId = ctx.lojaId();
        // Item 8.3: o custo so vai para quem pode ver numero de dono. A consulta continua aberta --
        // a Frente de Caixa e do balconista e precisa de nome, preco e estoque.
        boolean comCusto = ctx.podeVerNumeroDeDono();
        return repo.resumoDaLoja(lojaId).stream()
                .map(r -> br.com.bora.dto.ProdutoResumo.de(
                        (Long) r[0], (String) r[1], (String) r[2],
                        (java.math.BigDecimal) r[3], (java.math.BigDecimal) r[4],
                        (Integer) r[5], (Integer) r[6], (Boolean) r[7],
                        lojaId, Boolean.TRUE.equals(r[8]), (String) r[9]))
                .map(p -> comCusto ? p : p.semCusto())
                .toList();
    }

    public Produto criar(Produto p) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        validarNome(p.nome);
        p.lojaId = ctx.lojaId();
        p.id = null;
        p.codigoExterno = codigoLimpo(p.codigoExterno);
        recusarCodigoRepetido(p.lojaId, p.codigoExterno, null);
        return repo.save(p);
    }

    public Produto atualizar(Long id, Produto dados) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        validarNome(dados.nome);
        Produto p = buscar(id);
        p.nome = dados.nome;
        p.categoria = dados.categoria;
        p.preco = dados.preco;
        p.custo = dados.custo;
        // O ESTOQUE NAO ENTRA AQUI. Era `p.estoque = dados.estoque`, sem condicao, e o formulario
        // mandava o numero que a tela tinha carregado -- talvez horas antes. O lojista abria Produtos
        // as 18h, a loja vendia 12 durante a noite, ele corrigia so o PRECO as 21h, e as 12 baixas
        // desapareciam, sem log e sem aviso.
        //
        // Quem mexe em estoque agora e o `mexerNoEstoque`, que e uma operacao propria e explicita.
        // Isso faz o apagamento ser IMPOSSIVEL, em vez de depender de o formulario mandar o numero
        // certo. Ver MexerNoEstoqueRequest.
        p.estoqueMinimo = dados.estoqueMinimo;
        if (dados.imagemUrl != null) p.imagemUrl = dados.imagemUrl.isBlank() ? null : dados.imagemUrl;
        if (dados.ativo != null) p.ativo = dados.ativo;
        p.codigoExterno = codigoLimpo(dados.codigoExterno);
        recusarCodigoRepetido(p.lojaId, p.codigoExterno, p.id);
        return repo.save(p);
    }

    /**
     * O codigo do marketplace como ele vai para o banco.
     *
     * <p>Vazio tem que virar NULO, nao string vazia: o indice unico
     * ({@code ux_produto_codigo_externo}) vale por loja para os valores preenchidos, entao o segundo
     * produto salvo com {@code ""} seria recusado pelo banco e o lojista levaria um erro sem causa
     * visivel. Nulo fica fora do indice.</p>
     */
    /**
     * Dois produtos da mesma loja nao podem ter o mesmo codigo do marketplace.
     *
     * <p>Quem garante isso de verdade e o banco ({@code ux_produto_codigo_externo}) — e tem que ser
     * ele, porque duas abas salvando ao mesmo tempo passariam por esta conferencia juntas. O que esta
     * conferencia faz e TRADUZIR: sem ela o lojista levava um erro 500 sem texto, e nao tinha como
     * saber que o codigo ja estava em outro produto. Aqui ele le o nome do produto que ja usa.</p>
     *
     * <p>Se os dois tivessem o mesmo codigo, o pedido do iFood acharia os dois e o sistema desistiria
     * de escolher — voltaria a nao baixar estoque, que e exatamente o defeito que o codigo conserta.</p>
     *
     * @param idDesteProduto o proprio produto, ao editar — ele nao briga consigo mesmo
     */
    private void recusarCodigoRepetido(Long lojaId, String codigo, Long idDesteProduto) {
        if (codigo == null) return;
        var dono = repo.findFirstByLojaIdAndCodigoExterno(lojaId, codigo);
        if (dono.isEmpty()) return;
        if (idDesteProduto != null && idDesteProduto.equals(dono.get().id)) return;
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "O código \"" + codigo + "\" já está no produto \"" + dono.get().nome
                        + "\". Cada produto precisa de um código diferente.");
    }

    private static String codigoLimpo(String codigo) {
        if (codigo == null) return null;
        String limpo = codigo.trim();
        return limpo.isEmpty() ? null : limpo;
    }

    /**
     * Mexer no estoque: a operacao separada do cadastro.
     *
     * <p>Ver {@link br.com.bora.dto.MexerNoEstoqueRequest} para o porque de ser um endpoint proprio e
     * nao um campo do formulario.</p>
     *
     * <p><b>DEFINIR</b> sempre vence, mesmo que tenha havido vendas entre a leitura da tela e o
     * clique: o lojista andou na prateleira e contou, entao o numero dele e a verdade. <b>AJUSTAR</b>
     * soma no banco, nunca em Java, e recusa descer abaixo de zero.</p>
     *
     * @return o produto com o saldo novo, para a tela mostrar a verdade em vez de recarregar tudo
     */
    @org.springframework.transaction.annotation.Transactional
    public Produto mexerNoEstoque(Long id, br.com.bora.dto.MexerNoEstoqueRequest req) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        Long lojaId = ctx.lojaId();
        Produto p = buscar(id);
        if (req == null || req.tipo() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o tipo: DEFINIR ou AJUSTAR");
        }
        if (req.tipo() == br.com.bora.dto.MexerNoEstoqueRequest.Tipo.DEFINIR) {
            Integer valor = req.quantidade() == null ? null : req.quantidade().intValue();
            if (valor != null && valor < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O estoque nao pode ser negativo");
            }
            repo.definirEstoque(id, lojaId, valor);
        } else {
            if (req.quantidade() == null || req.quantidade().signum() == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe quanto somar ou tirar");
            }
            if (p.estoque == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "\"" + p.nome + "\" nao controla estoque. Informe uma quantidade inicial antes "
                                + "de usar os botoes de ajuste.");
            }
            if (repo.somarAoEstoque(id, lojaId, req.quantidade().intValue()) == 0) {
                // O saldo e lido DEPOIS da tentativa, porque entre o clique e agora pode ter havido
                // venda -- e o numero que o lojista precisa ler e o de agora, nao o da tela dele.
                Integer agora = buscar(id).estoque;
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "\"" + p.nome + "\" esta com " + agora + " em estoque; o ajuste deixaria o "
                                + "saldo negativo. Use a Contagem para informar o valor contado.");
            }
        }
        return buscar(id);
    }

    /**
     * Apaga o produto e o que pertence a ele: a ficha tecnica e os complementos.
     *
     * <h2>O defeito que isto fecha (item 4.3, medido em 21/10/2026)</h2>
     * <p>Era {@code repo.delete(...)} seco. A {@code complemento_grupo} tem chave estrangeira para
     * {@code produto} <b>sem ON DELETE</b> (conferido na producao: e a unica FK que aponta para
     * produto, com {@code confdeltype = 'a'}), entao o banco recusava o DELETE e o lojista levava um
     * <b>erro 500</b> — justamente nos produtos que tem adicional cadastrado, que numa acaiteria sao
     * os que mais vendem.</p>
     *
     * <p>E a {@code produto_insumo} e o contrario: <b>nao tem</b> chave estrangeira. O DELETE passava
     * e deixava as linhas da ficha apontando para um produto que nao existe mais — a mesma ficha
     * fantasma do item 2.5, pelo outro lado. Ninguem le essas linhas depois (o id vem de uma
     * sequencia e nao se repete), mas elas ficam na base para sempre e aparecem em qualquer contagem.</p>
     *
     * <h2>Por que apagar junto, e nao recusar</h2>
     * <p>Ficha e complemento <b>pertencem</b> ao produto: nao fazem sentido sem ele, e a tela de
     * complementos ja apaga e recria o conjunto inteiro a cada gravacao. Recusar obrigaria o lojista a
     * limpar as duas coisas a mao antes de conseguir apagar, sem ganhar nada.</p>
     *
     * <p><b>O historico nao e tocado, e isso foi medido:</b> {@code pedido_item} nao tem chave
     * estrangeira para produto, e o relatorio por produto agrupa pela {@code descricao} gravada no
     * item, nao pelo nome do cadastro ({@code RelatorioService}). Pedido antigo de um produto apagado
     * continua aparecendo no relatorio com o nome que tinha no dia da venda.</p>
     */
    @org.springframework.transaction.annotation.Transactional
    public void excluir(Long id) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        Produto alvo = buscar(id);
        Long loja = ctx.lojaId();

        // Os complementos primeiro, e as opcoes antes dos grupos: a FK da opcao para o grupo e
        // ON DELETE CASCADE, mas apagar por aqui mantem a ordem explicita em vez de depender dela.
        List<Long> gruposDoProduto = grupos.findByLojaIdAndProdutoIdOrderById(loja, id)
                .stream().map(g -> g.id).toList();
        if (!gruposDoProduto.isEmpty()) opcoes.deleteByLojaIdAndGrupoIdIn(loja, gruposDoProduto);
        grupos.deleteByLojaIdAndProdutoId(loja, id);

        // A ficha tecnica, que nao tem FK e por isso ficava orfa em silencio.
        fichas.deleteByLojaIdAndProdutoId(loja, id);

        repo.delete(alvo);
    }

    private void validarNome(String nome) {
        if (nome == null || nome.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do produto é obrigatório");
        }
    }

    private Produto buscar(Long id) {
        return repo.findByIdAndLojaId(id, ctx.lojaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não encontrado"));
    }
}
