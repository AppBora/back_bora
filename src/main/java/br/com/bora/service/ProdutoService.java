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
    private final AuthContext ctx;

    public ProdutoService(ProdutoRepository repo, AuthContext ctx) {
        this.repo = repo;
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
        return repo.resumoDaLoja(lojaId).stream()
                .map(r -> br.com.bora.dto.ProdutoResumo.de(
                        (Long) r[0], (String) r[1], (String) r[2],
                        (java.math.BigDecimal) r[3], (java.math.BigDecimal) r[4],
                        (Integer) r[5], (Integer) r[6], (Boolean) r[7],
                        lojaId, Boolean.TRUE.equals(r[8]), (String) r[9]))
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
        p.estoque = dados.estoque;
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

    public void excluir(Long id) {
        ctx.requirePapel("GERENTE", "ADMINISTRADOR_LOJA");
        repo.delete(buscar(id));
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
