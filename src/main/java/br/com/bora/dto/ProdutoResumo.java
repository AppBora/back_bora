package br.com.bora.dto;

import java.math.BigDecimal;

/**
 * O produto como as telas do painel precisam dele — SEM a foto em base64.
 *
 * <p><b>O peso que isto tira:</b> {@code produto.imagem_url} guarda a foto como data URL dentro da
 * coluna. Na Zirá são <b>453 kB</b> em 31 de 33 produtos (medido em 14/10/2026; 1,36 MB somando as
 * cinco lojas). O {@code GET /api/produtos} devolvia a entidade inteira, então toda abertura de
 * Produtos, Frente de Caixa, Estoque, Contagem e Novo Pedido lia e trafegava esse meio megabyte — e o
 * Hibernate hidratava a coluna mesmo quando ninguém ia olhar a foto.</p>
 *
 * <p>A consulta não traz a coluna; só se existe foto. O {@code imagemUrl} que a tela recebe aponta
 * para {@code /public/loja/{loja}/produto/{id}/imagem}, que já existia, serve a imagem com o tipo
 * certo e o navegador <b>cacheia</b>. Para a tela, nada muda: o atributo continua chamando
 * {@code imagemUrl} e continua servindo de {@code src}.</p>
 *
 * <p><b>Por que não mutar a entidade:</b> trocar {@code p.imagemUrl} num objeto gerenciado dentro da
 * requisição faria o Hibernate gravar a troca no banco no fim dela (o OSIV mantém a sessão aberta) —
 * a foto do lojista seria substituída por uma URL apontando para si mesma, e perdida.</p>
 */
public record ProdutoResumo(
        Long id,
        String nome,
        String categoria,
        BigDecimal preco,
        BigDecimal custo,
        Integer estoque,
        Integer estoqueMinimo,
        Boolean ativo,
        String imagemUrl,
        /** O codigo deste produto no iFood/99. A tela de Produtos edita este campo. */
        String codigoExterno) {

    /** Monta a partir da projeção, resolvendo o endereço da foto. */
    public static ProdutoResumo de(Long id, String nome, String categoria, BigDecimal preco,
                                   BigDecimal custo, Integer estoque, Integer estoqueMinimo,
                                   Boolean ativo, Long lojaId, boolean temImagem,
                                   String codigoExterno) {
        return new ProdutoResumo(id, nome, categoria, preco, custo, estoque, estoqueMinimo, ativo,
                temImagem ? "/public/loja/" + lojaId + "/produto/" + id + "/imagem" : null,
                codigoExterno);
    }
}
