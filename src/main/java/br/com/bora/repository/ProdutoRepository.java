package br.com.bora.repository;

import br.com.bora.entity.Produto;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ProdutoRepository extends JpaRepository<Produto, Long> {
    List<Produto> findByLojaIdOrderByNomeAsc(Long lojaId);
    List<Produto> findByLojaIdAndAtivoTrueOrderByCategoriaAscNomeAsc(Long lojaId);
    Optional<Produto> findByIdAndLojaId(Long id, Long lojaId);

    /** Varios produtos de uma vez, para quem monta carrinho e nao quer uma consulta por linha. */
    java.util.List<Produto> findByLojaIdAndIdIn(Long lojaId, java.util.Collection<Long> ids);
    long countByLojaId(Long lojaId);

    /**
     * Baixa o estoque no banco, nao em Java.
     *
     * <p><b>O defeito que isto fecha:</b> a baixa era {@code prod.estoque = prod.estoque - qtd} seguido
     * de {@code save}. Duas vendas do mesmo item ao mesmo tempo — o balcao e o cardapio, ou dois
     * pedidos do cardapio no pico — leem o MESMO estoque antes de qualquer uma gravar, e as duas
     * gravam o mesmo numero: uma das baixas desaparece. O lojista vende o que nao tem.</p>
     *
     * <p>Aqui quem decide e o banco. So mexe em produto que controla estoque
     * ({@code estoque is not null}), igual a baixa antiga — quem nao controla continua sem numero em
     * vez de nascer com saldo inventado.</p>
     *
     * @return 1 se baixou, 0 se o produto nao controla estoque ou nao e desta loja
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Produto p set p.estoque = p.estoque - :qtd
            where p.id = :id and p.lojaId = :lojaId and p.estoque is not null""")
    int baixarEstoque(@org.springframework.data.repository.query.Param("id") Long id,
                      @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                      @org.springframework.data.repository.query.Param("qtd") int qtd);

    /**
     * Baixa o estoque SO SE TIVER. Para o canal em que recusar e a resposta certa.
     *
     * <p><b>A diferenca em relacao ao {@link #baixarEstoque}:</b> aquele baixa sempre e o saldo vai a
     * negativo sem limite. Isso esta certo no balcao — o operador esta com o produto na mao, e travar
     * a venda com o cliente na frente e pior que o numero negativo, que pelo menos denuncia que a
     * contagem esta errada. E esta certo no marketplace, onde o pedido ja foi aceito do outro lado.</p>
     *
     * <p>No <b>cardapio publico</b> esta errado: o cliente nao tem o produto na mao. Vender e depois
     * cancelar e pior que dizer "acabou" na hora. Uma chamada do cardapio podia levar um produto a
     * −4950 (50 itens x 99).</p>
     *
     * <h2>Por que o {@code estoque is null} devolve 1 e nao 0</h2>
     * <p>Produto sem numero de estoque e produto que o lojista escolheu NAO controlar — e o caso de
     * todos os 123 produtos da producao hoje (medido em 20/10/2026). Para esses, a venda tem que
     * passar. A linha casa, o {@code set} e no-op (nulo menos qualquer coisa e nulo) e o retorno e 1.
     * Assim 1 quer dizer "pode seguir" e 0 quer dizer uma coisa so: <b>controla estoque e nao tem o
     * suficiente</b>.</p>
     *
     * <p>Quem garante contra duas vendas simultaneas e esta clausula, no banco — nao uma conferencia
     * em Java antes. Duas chamadas ao mesmo tempo leriam o mesmo saldo e as duas passariam.</p>
     *
     * @return 1 quando pode seguir (baixou, ou o produto nao controla estoque); 0 quando falta
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Produto p set p.estoque = p.estoque - :qtd
            where p.id = :id and p.lojaId = :lojaId
              and (p.estoque is null or p.estoque >= :qtd)""")
    int baixarEstoqueSeTiver(@org.springframework.data.repository.query.Param("id") Long id,
                             @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                             @org.springframework.data.repository.query.Param("qtd") int qtd);

    /** Devolve o estoque no banco, pelo mesmo motivo da baixa. Usada pelo EstornoDaVendaService. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Produto p set p.estoque = p.estoque + :qtd
            where p.id = :id and p.lojaId = :lojaId and p.estoque is not null""")
    int devolverEstoque(@org.springframework.data.repository.query.Param("id") Long id,
                        @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                        @org.springframework.data.repository.query.Param("qtd") int qtd);

    /**
     * Produtos da loja SEM a coluna da foto.
     *
     * <p>Ver {@link br.com.bora.dto.ProdutoResumo}: a foto vive como data URL dentro de
     * {@code imagem_url} (453 kB na Zira), e toda abertura de Produtos, PDV, Estoque, Contagem e Novo
     * Pedido lia isso. Aqui a consulta nem toca na coluna — devolve so se existe foto.</p>
     */
    @org.springframework.data.jpa.repository.Query("""
            select p.id, p.nome, p.categoria, p.preco, p.custo, p.estoque, p.estoqueMinimo, p.ativo,
                   case when p.imagemUrl is null or p.imagemUrl = '' then false else true end,
                   p.codigoExterno
              from Produto p where p.lojaId = :lojaId order by p.nome""")
    java.util.List<Object[]> resumoDaLoja(@org.springframework.data.repository.query.Param("lojaId") Long lojaId);

    /** Quem na loja ja usa este codigo do marketplace. Para avisar ANTES do banco recusar. */
    Optional<Produto> findFirstByLojaIdAndCodigoExterno(Long lojaId, String codigoExterno);

    /** Produtos da loja para casar com o item do marketplace. Uma consulta por pedido, nao por item. */
    java.util.List<Produto> findByLojaId(Long lojaId);
}
