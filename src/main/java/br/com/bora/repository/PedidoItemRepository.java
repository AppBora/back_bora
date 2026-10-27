package br.com.bora.repository;

import br.com.bora.entity.PedidoItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface PedidoItemRepository extends JpaRepository<PedidoItem, Long> {
    List<PedidoItem> findByLojaIdAndPedidoIdOrderById(Long lojaId, Long pedidoId);
    List<PedidoItem> findByLojaId(Long lojaId);
    List<PedidoItem> findByLojaIdAndPedidoIdIn(Long lojaId, java.util.Collection<Long> pedidoIds);
    void deleteByLojaIdAndPedidoId(Long lojaId, Long pedidoId);

    long countByLojaId(Long lojaId);

    /**
     * Quanto de cada produto foi VENDIDO em cada loja, no periodo — numa consulta para a rede toda.
     *
     * <h2>O N+1 que isto substitui (registrado na v131, atacado em 27/10/2026)</h2>
     * <p>O {@code AgenteRedeService.estoqueEmRisco} fazia TRES consultas por loja: os pedidos da
     * loja, os itens desses pedidos, e os produtos dela. Numa rede de 5 lojas eram 15 consultas para
     * montar um quadro.</p>
     *
     * <p>Em 21/10/2026 eu deixei este N+1 registrado e <b>nao feito</b>, com o motivo: o laco nao e
     * so de consultas, ele calcula cobertura de estoque em memoria, e mexer em calculo sem ter o
     * numero de antes para comparar seria mudar o painel sem perceber. O caminho que eu mesmo
     * escrevi era: congelar o numero num teste, e so entao reescrever.</p>
     *
     * <p><b>O que esta consulta NAO faz, de proposito: nenhuma conta.</b> Ela devolve a soma bruta
     * por (loja, produto) e a aritmetica continua em Java, com os mesmos arredondamentos
     * ({@code porDia} em 2 casas, {@code cobertura} em 1, as duas HALF_UP). Empurrar a divisao para o
     * SQL mudaria numero na tela do dono -- e o defeito era a quantidade de consultas, nao o lugar
     * da conta.</p>
     *
     * <p>Exclui CANCELADO, como o laco excluia. Item sem {@code produtoId} tambem fica fora: o laco
     * fazia {@code if (it.getProdutoId() == null) continue}.</p>
     */
    // `coalesce(quantidade, 1)` e nao `sum(quantidade)`: o laco em Java fazia
    // `it.getQuantidade() == null ? 1 : ...`, e `sum` ignoraria o nulo (contaria 0). A coluna e NOT
    // NULL no banco e a producao tem ZERO itens nulos, entao a diferenca nao e alcancavel hoje -- o
    // coalesce esta aqui para a consulta dizer a MESMA regra que o Java dizia, e nao porque haja
    // estrago. Alinhar as duas custa nada; descobrir a divergencia depois custaria um numero errado
    // no painel.
    @Query("select i.lojaId, i.produtoId, sum(coalesce(i.quantidade, 1)) from PedidoItem i, Pedido p "
         + "where p.id = i.pedidoId and p.lojaId = i.lojaId "
         + "and i.lojaId in :lojas and i.produtoId is not null "
         + "and p.status <> br.com.bora.entity.StatusPedido.CANCELADO "
         + "and p.criadoEm >= :inicio and p.criadoEm < :fim "
         + "group by i.lojaId, i.produtoId")
    java.util.List<Object[]> somaVendidaPorProduto(
            @org.springframework.data.repository.query.Param("lojas") java.util.Collection<Long> lojas,
            @org.springframework.data.repository.query.Param("inicio") java.time.OffsetDateTime inicio,
            @org.springframework.data.repository.query.Param("fim") java.time.OffsetDateTime fim);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from PedidoItem i where i.lojaId = :lojaId")
    int deleteByLojaId(@org.springframework.data.repository.query.Param("lojaId") Long lojaId);
}
