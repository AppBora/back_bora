package br.com.bora.repository;

import br.com.bora.entity.Pedido;
import br.com.bora.entity.StatusPedido;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {

    /** Receita (não cancelada) da loja numa janela — base do termômetro de vendas. */
    // PIX gerado e nao pago nao e faturamento: entrava na conta do dia e ficava la para sempre,
    // porque nada expirava o pedido abandonado no checkout.
    @Query("select coalesce(sum(p.valorTotal), 0) from Pedido p " +
           "where p.lojaId = :lojaId and p.status <> br.com.bora.entity.StatusPedido.CANCELADO " +
           "and (p.aguardandoPagamento is null or p.aguardandoPagamento = false) " +
           "and p.criadoEm >= :inicio and p.criadoEm < :fim")
    BigDecimal somaReceita(@Param("lojaId") Long lojaId,
                           @Param("inicio") OffsetDateTime inicio,
                           @Param("fim") OffsetDateTime fim);

    /**
     * Pedidos válidos da loja numa janela — balancete da rede.
     *
     * <p>Mesma regra da receita: PIX que ninguém pagou não é venda. Faltava aqui, e só aqui, então a
     * receita excluía o pedido pendente e a contagem o incluía — o ticket médio (receita ÷ pedidos)
     * saía menor do que a realidade, e os dois números do mesmo painel não fechavam entre si.</p>
     */
    @Query("select count(p) from Pedido p " +
           "where p.lojaId = :lojaId and p.status <> br.com.bora.entity.StatusPedido.CANCELADO " +
           "and (p.aguardandoPagamento is null or p.aguardandoPagamento = false) " +
           "and p.criadoEm >= :inicio and p.criadoEm < :fim")
    long contaPedidosValidos(@Param("lojaId") Long lojaId,
                             @Param("inicio") OffsetDateTime inicio,
                             @Param("fim") OffsetDateTime fim);

    /** Pedidos cancelados da loja numa janela — balancete da rede. */
    @Query("select count(p) from Pedido p " +
           "where p.lojaId = :lojaId and p.status = br.com.bora.entity.StatusPedido.CANCELADO " +
           "and p.criadoEm >= :inicio and p.criadoEm < :fim")
    long contaPedidosCancelados(@Param("lojaId") Long lojaId,
                                @Param("inicio") OffsetDateTime inicio,
                                @Param("fim") OffsetDateTime fim);

    /**
     * Acerto de entregadores: soma por entregador das entregas realizadas ainda não acertadas, na janela.
     *
     * <p>Agrupa por {@code lower(trim(...))}, a MESMA chave que o {@link #entregasParaAcerto} usa para
     * achar as entregas. Agrupando pelo texto cru, "Joao" e "joao " viravam duas linhas de R$ 5,00 na
     * tela e fechar qualquer uma das duas pagava R$ 10,00, porque o "fazer acerto" juntava as duas.
     * A previa mostrava um valor e o sistema pagava outro.</p>
     *
     * <p>O nome exibido e o {@code min()} do grupo: qualquer uma das variantes serve de entrada para o
     * {@code entregasParaAcerto}, que normaliza os dois lados.</p>
     */
    @Query("select min(p.entregador), count(p), coalesce(sum(p.taxaEntrega), 0), " +
           "coalesce(sum(case when upper(coalesce(p.formaPagamento, '')) like '%DINHEIRO%' then p.valorTotal else 0 end), 0), " +
           "coalesce(sum(p.valorTotal), 0) " +
           "from Pedido p where p.lojaId = :lojaId " +
           "and p.status = br.com.bora.entity.StatusPedido.ENTREGUE and p.acertoId is null " +
           "and p.entregador is not null and trim(p.entregador) <> '' " +
           // Item 6.3: PIX gerado e nunca pago, marcado como entregue, entrava no acerto como venda
           // normal -- o `total` dele ia para "outras formas", como se o dinheiro tivesse entrado por
           // cartao. A conta com o motoboy fechava errada. E a MESMA regra do faturamento ("PIX que
           // ninguem pagou nao e venda"): o pedido fica fora ate o pagamento ser resolvido, e entra
           // no acerto seguinte quando for.
           "and (p.aguardandoPagamento is null or p.aguardandoPagamento = false) " +
           "and p.entregueEm >= :inicio and p.entregueEm < :fim " +
           "group by lower(trim(p.entregador)) order by min(p.entregador)")
    List<Object[]> previaAcerto(@Param("lojaId") Long lojaId,
                                @Param("inicio") OffsetDateTime inicio,
                                @Param("fim") OffsetDateTime fim);

    /** Entregas de um entregador (não canceladas/acertadas) na janela — base do "fazer acerto". */
    // O nome do entregador e texto livre digitado a cada pedido: "Joao", "joao " e "JOAO" eram tres
    // entregadores diferentes, e o acerto de um nao achava as entregas do outro.
    @Query("select p from Pedido p where p.lojaId = :lojaId " +
           "and p.status = br.com.bora.entity.StatusPedido.ENTREGUE and p.acertoId is null " +
           "and lower(trim(p.entregador)) = lower(trim(:entregador)) " +
           // Mesma regra da previa (item 6.3): o que nao aparece na previa nao pode ser amarrado ao
           // acerto, senao a previa mostra um valor e o acerto paga outro.
           "and (p.aguardandoPagamento is null or p.aguardandoPagamento = false) " +
           "and p.entregueEm >= :inicio and p.entregueEm < :fim")
    List<Pedido> entregasParaAcerto(@Param("lojaId") Long lojaId,
                                    @Param("entregador") String entregador,
                                    @Param("inicio") OffsetDateTime inicio,
                                    @Param("fim") OffsetDateTime fim);

    /**
     * Amarra as entregas ao acerto, mas so as que ainda estao livres.
     *
     * <p>Antes isto era um save() comum: dois acertos abertos ao mesmo tempo (duas abas, duas pessoas)
     * liam as mesmas entregas e os dois gravavam, entao o entregador podia ser pago duas vezes pelas
     * mesmas corridas. Aqui o banco decide quem chegou primeiro, e quem perder sabe disso pelo numero
     * de linhas afetadas.</p>
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Pedido p set p.acertoId = :acertoId where p.id in :ids and p.acertoId is null")
    int amarrarAoAcerto(@Param("acertoId") Long acertoId, @Param("ids") java.util.Collection<Long> ids);

    Optional<Pedido> findFirstByLojaIdAndClienteIdOrderByCriadoEmDesc(Long lojaId, Long clienteId);
    List<Pedido> findByLojaIdAndClienteIdAndCriadoEmAfter(Long lojaId, Long clienteId, OffsetDateTime corte);
    long countByLojaIdAndStatus(Long lojaId, StatusPedido status);
    long countByLojaIdAndEntregueEmAfter(Long lojaId, OffsetDateTime dt);
    List<Pedido> findByLojaIdOrderByCriadoEmDesc(Long lojaId);

    /** Pedidos de um dia só — o quadro carrega o dia corrente, não a loja inteira desde a abertura. */
    List<Pedido> findByLojaIdAndCriadoEmGreaterThanEqualAndCriadoEmLessThanOrderByCriadoEmDesc(
            Long lojaId, OffsetDateTime inicio, OffsetDateTime fim);

    /**
     * Pedido que virou o dia sem terminar continua no quadro — senão some da vista da cozinha.
     *
     * <p><b>Lista POSITIVA, não negação</b>, e isso foi medido. A versão anterior era
     * {@code StatusNotIn(ENCERRADOS)}, e nenhum índice servia uma negação: o KDS recarrega a cada 6
     * segundos e cada volta varria todos os pedidos da loja (9,3 ms com 26 mil pedidos). Um índice
     * parcial {@code where status not in (...)} resolvia em 0,23 ms, mas só enquanto o Postgres pudesse
     * olhar os valores dos parâmetros — com plano genérico ele não consegue provar que
     * {@code status not in ($1,$2)} implica o predicado do índice, e cai para 15,9 ms. A forma positiva
     * usa o índice comum {@code (loja_id, status)} <b>sempre</b>: 0,35 ms, conferido em 6 execuções
     * seguidas, inclusive depois do limite em que o Postgres troca para plano genérico. Ver a V54.</p>
     *
     * <p><b>O risco que a troca cria, e o teste que o cobre:</b> a negação pegava status novo de
     * graça; a lista positiva não. Um status acrescentado ao enum e esquecido aqui faria o pedido
     * <b>desaparecer do quadro da cozinha</b> — e o enum tem SETE valores, não seis (o
     * {@code CONFIRMADO} é fácil de esquecer). O {@code AbertosMaisEncerradosCobremTudoTest} reprova
     * se a soma dos dois conjuntos deixar de ser o enum inteiro.</p>
     */
    List<Pedido> findByLojaIdAndStatusInOrderByCriadoEmDesc(Long lojaId, java.util.Collection<StatusPedido> abertos);
    List<Pedido> findByLojaIdAndCriadoEmAfterOrderByCriadoEmDesc(Long lojaId, OffsetDateTime corte);

    /** PIX gerado e nunca pago, mais velho que o corte — o cobrador cancela estes. */
    List<Pedido> findByAguardandoPagamentoTrueAndCriadoEmBefore(OffsetDateTime corte);
    Optional<Pedido> findByIdAndLojaId(Long id, Long lojaId);

    /**
     * O ultimo pedido REPETIVEL de cada cliente da loja, numa consulta so.
     *
     * <p>Antes isto era uma consulta por cliente dentro de um laco: abrir o CRM de uma loja com 500
     * clientes custava 501 idas ao banco, cada uma varrendo os pedidos da loja por falta de indice
     * com {@code cliente_id} (criado na V47).</p>
     *
     * <p>"Repetivel" exclui o que nao faz sentido oferecer de volta: pedido CANCELADO (inclusive o
     * PIX que expirou) e pedido de marketplace, cujos itens nao guardam {@code produto_id} e por
     * isso nao remontam. Sem esse filtro o botao aparecia e levava a carrinho vazio.</p>
     *
     * <p>Usa {@code max(id)} e nao {@code criadoEm} porque o id e sequencial e nao empata.</p>
     */
    @Query("select p.clienteId, max(p.id) from Pedido p "
         + "where p.lojaId = :lojaId and p.clienteId is not null "
         + "and p.status <> br.com.bora.entity.StatusPedido.CANCELADO "
         + "and p.canalExterno is null "
         + "group by p.clienteId")
    List<Object[]> ultimoPedidoRepetivelPorCliente(@Param("lojaId") Long lojaId);
    long countByLojaIdAndCriadoEmAfter(Long lojaId, OffsetDateTime inicio); // RN09 — limite de pedidos/mês
    Optional<Pedido> findFirstByLojaIdAndCanalExternoAndIdExterno(Long lojaId, String canalExterno, String idExterno); // idempotência webhook

    /**
     * Último pedido de cada loja, para o painel de clientes da plataforma.
     *
     * <h2>Item 4.7 da varredura: era um group by da tabela INTEIRA</h2>
     * <p>{@code select p.lojaId, max(p.criadoEm) from Pedido p group by p.lojaId}, sem filtro nenhum:
     * para montar uma coluna da tela da plataforma, o banco lia <b>todos os pedidos de todas as
     * lojas</b>. O Postgres não tem "loose index scan", então um índice em {@code (loja_id,
     * criado_em)} não transforma isso em busca — ele só troca a varredura da tabela pela varredura do
     * índice. Continua custando o tamanho da base, e essa tela é justamente a que o dono abre quando a
     * base cresceu.</p>
     *
     * <p>Agora é uma subconsulta por loja. São poucas lojas (unidades, não milhares) e cada
     * {@code max} vira uma busca no índice {@code idx_pedido_loja}, que é
     * {@code (loja_id, criado_em DESC)} e já existia — ou seja, o que faltava era a consulta, não o
     * índice. <b>Eu ia criar um índice novo aqui</b> e medir os índices da produção mostrou que ele
     * seria duplicado.</p>
     *
     * <p>Sai de {@code Loja}, não de {@code Pedido}, de propósito: assim a tela recebe uma linha por
     * loja, inclusive a que nunca vendeu (com {@code null}). O {@code group by} antigo simplesmente
     * não devolvia essas lojas — e o chamador já tratava a ausência como "sem pedido", então o
     * resultado visível é o mesmo.</p>
     */
    @Query("select l.id, (select max(p.criadoEm) from Pedido p where p.lojaId = l.id) "
         + "from br.com.bora.entity.Loja l")
    java.util.List<Object[]> ultimoPedidoPorLoja();

    /**
     * O balancete da rede inteira numa consulta: receita, pedidos válidos e cancelados por loja.
     *
     * <h2>Item 4.7 da varredura: eram 3 consultas POR LOJA</h2>
     * <p>O {@code RedeService.balancete} chamava {@code somaReceita}, {@code contaPedidosValidos} e
     * {@code contaPedidosCancelados} dentro de um laço pelas lojas. Numa rede de 30 lojas são 90
     * consultas para desenhar uma tabela, cada uma varrendo a janela de datas da sua loja.</p>
     *
     * <p><b>As regras são as MESMAS das três, linha por linha</b>, e isso importa mais que a
     * performance: válido é {@code status <> CANCELADO} e PIX não pendente ({@code aguardandoPagamento}
     * nulo ou falso — "PIX que ninguém pagou não é venda"); cancelado é só o status. Se esta consulta
     * divergisse delas, os números do painel do dono mudariam — e número que muda sozinho é pior que
     * consulta lenta. O {@code BalanceteEmUmaConsultaTest} compara as duas formas sobre o mesmo
     * cenário.</p>
     *
     * <p>Loja sem pedido na janela não aparece no resultado. O chamador trata como zero, igual a
     * antes.</p>
     */
    @Query("select p.lojaId, "
         + "coalesce(sum(case when p.status <> br.com.bora.entity.StatusPedido.CANCELADO "
         + "                   and (p.aguardandoPagamento is null or p.aguardandoPagamento = false) "
         + "                  then p.valorTotal else 0 end), 0), "
         + "coalesce(sum(case when p.status <> br.com.bora.entity.StatusPedido.CANCELADO "
         + "                   and (p.aguardandoPagamento is null or p.aguardandoPagamento = false) "
         + "                  then 1 else 0 end), 0), "
         + "coalesce(sum(case when p.status = br.com.bora.entity.StatusPedido.CANCELADO "
         + "                  then 1 else 0 end), 0) "
         + "from Pedido p where p.lojaId in :lojas "
         + "and p.criadoEm >= :inicio and p.criadoEm < :fim "
         + "group by p.lojaId")
    java.util.List<Object[]> balancetePorLoja(@Param("lojas") java.util.Collection<Long> lojas,
                                              @Param("inicio") OffsetDateTime inicio,
                                              @Param("fim") OffsetDateTime fim);

    /**
     * Desamarra os pedidos de um cliente que está sendo apagado.
     *
     * <h2>Item 4.7 da varredura: o pedido órfão</h2>
     * <p>{@code pedido.cliente_id} não tem chave estrangeira, então apagar o cliente deixava os
     * pedidos dele apontando para uma linha que não existe. O histórico tem que ficar (a venda
     * aconteceu, e o pedido guarda telefone e endereço próprios), mas o ponteiro morto não.</p>
     *
     * <p>Medido em 21/10/2026, o ponteiro morto não era inofensivo: com o saldo de cashback virando
     * SQL no item 1.5, {@code consumir} passou a estourar 409 quando a consulta não mexe em linha
     * nenhuma — e cliente apagado é exatamente isso. Um PIX pago depois do cancelamento, num pedido
     * que usou cashback, de um cliente apagado, devolvia 409 ao webhook do Asaas, que reenvia para
     * sempre. Isso foi consertado no serviço <b>e</b> aqui: as duas pontas.</p>
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Pedido p set p.clienteId = null "
         + "where p.clienteId = :clienteId and p.lojaId = :lojaId")
    int desamarrarCliente(@Param("clienteId") Long clienteId, @Param("lojaId") Long lojaId);

    /**
     * Entregas concluidas SEM motoboy escolhido, ainda nao acertadas, na janela.
     *
     * <h2>Item 6.4 da varredura</h2>
     * <p>O acerto exige {@code entregador is not null and trim(...) <> ''}. Uma entrega concluida sem
     * ninguem escolhido no card simplesmente <b>nunca aparece em acerto nenhum</b> — nem errado, nem
     * certo: ela desaparece. E a KDS e o quadro deixam avancar ate ENTREGUE sem pedir o motoboy.</p>
     *
     * <p>Devolve quantidade, soma das taxas e soma dos valores, no mesmo formato das linhas da previa,
     * para a tela de acerto mostrar isso como uma linha a mais. Quem faz o acerto e justamente quem
     * precisa saber que esse dinheiro esta fora da conta.</p>
     */
    @Query("select count(p), coalesce(sum(p.taxaEntrega), 0), coalesce(sum(p.valorTotal), 0) "
         + "from Pedido p where p.lojaId = :lojaId "
         + "and p.status = br.com.bora.entity.StatusPedido.ENTREGUE and p.acertoId is null "
         + "and (p.entregador is null or trim(p.entregador) = '') "
         + "and (p.aguardandoPagamento is null or p.aguardandoPagamento = false) "
         + "and p.entregueEm >= :inicio and p.entregueEm < :fim")
    java.util.List<Object[]> entregasSemMotoboy(@Param("lojaId") Long lojaId,
                                                @Param("inicio") OffsetDateTime inicio,
                                                @Param("fim") OffsetDateTime fim);

    /** Grava que o marketplace aceitou o aceite automatico. So a coluna da marca, nada mais. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Pedido p set p.aceiteEnviadoEm = :agora where p.id = :id and p.lojaId = :lojaId")
    int marcarAceiteEnviado(@Param("lojaId") Long lojaId, @Param("id") Long id,
                            @Param("agora") OffsetDateTime agora);

    default int marcarAceiteEnviado(Long lojaId, Long id) {
        return marcarAceiteEnviado(lojaId, id, OffsetDateTime.now());
    }

    // ---- Limpeza da movimentacao de teste (LimpezaDeTestesService) ----
    // `deleteByLojaId` derivado faz SELECT e depois um DELETE por linha. Com 12 pedidos tanto faz;
    // numa loja com milhares seria uma viagem por pedido, entao aqui vai como UPDATE unico.
    long countByLojaId(Long lojaId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from Pedido p where p.lojaId = :lojaId")
    int deleteByLojaId(@org.springframework.data.repository.query.Param("lojaId") Long lojaId);

    /**
     * Apaga o pedido pelo id, dentro da loja.
     *
     * <p>Por que nao o {@code delete(entidade)}: o estorno que roda antes da exclusao usa consultas
     * com {@code clearAutomatically = true}, que LIMPAM o contexto do Hibernate. O objeto do pedido
     * fica desanexado, e {@code delete(entidade)} num objeto desanexado faz {@code merge} primeiro --
     * ou seja, grava o estado que esta em memoria de volta na linha, e so depois apaga. Funciona, mas
     * depende de um detalhe do ORM que ninguem lembra de olhar.</p>
     *
     * <p>Aqui a exclusao e pelo id e pela loja, e nao depende do estado do objeto. A clausula da loja
     * tambem e uma segunda tranca de tenant, alem da que o {@code buscarDaLoja} ja faz.</p>
     *
     * @return 1 se apagou, 0 se o pedido nao e desta loja (ou ja tinha sido apagado)
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from Pedido p where p.id = :id and p.lojaId = :lojaId")
    int apagarDaLoja(@org.springframework.data.repository.query.Param("id") Long id,
                     @org.springframework.data.repository.query.Param("lojaId") Long lojaId);
}
