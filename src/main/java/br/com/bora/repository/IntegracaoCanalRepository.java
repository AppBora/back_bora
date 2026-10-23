package br.com.bora.repository;

import br.com.bora.entity.IntegracaoCanal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface IntegracaoCanalRepository extends JpaRepository<IntegracaoCanal, Long> {
    List<IntegracaoCanal> findByLojaIdOrderByCanalAsc(Long lojaId);
    Optional<IntegracaoCanal> findByLojaIdAndCanal(Long lojaId, String canal);
    Optional<IntegracaoCanal> findByIdAndLojaId(Long id, Long lojaId);
    List<IntegracaoCanal> findByAtivoTrue();

    List<IntegracaoCanal> findByCanal(String canal);

    /** Webhook do Open Delivery: a 99 identifica a loja pelo header X-App-MerchantId. */
    Optional<IntegracaoCanal> findFirstByCanalAndMerchantId(String canal, String merchantId);

    // ======================================================== ITEM 5.5 DA VARREDURA
    //
    // O ciclo de polling carregava a integracao no comeco (findByAtivoTrue, fora de transacao) e
    // gravava `repo.save(i)` ate 25 segundos depois -- a linha INTEIRA, com os valores velhos de
    // TODAS as colunas. O que o lojista salvasse nesse meio tempo era desfeito sem aviso: desligar a
    // integracao, tirar o aceite automatico, colar uma credencial nova. Ele salvava, via "salvo", e o
    // valor voltava sozinho no ciclo seguinte.
    //
    // E `pedidos_recebidos` era leitura-soma-escrita em cima de um valor velho: dois pedidos no mesmo
    // ciclo contavam um.
    //
    // A cura e cada escrita tocar SO as colunas de quem escreve. As de baixo sao as do poller:
    // telemetria e status. Token NAO entra aqui -- aquelas colunas passam pelo cifrador
    // (@Convert SegredoGuardado), e UPDATE em JPQL nao chama conversor: gravaria em texto puro.
    // Quem mexe em token continua passando pela entidade, mas relendo a linha primeiro.
    //
    // O `and i.lojaId = :lojaId` nao e decoracao para o guarda de inquilino: com ele, um id
    // trocado por engano nao escreve na integracao de OUTRA loja -- vira UPDATE de zero linhas.

    /**
     * O polling funcionou: anota a hora, limpa o erro e tira a integracao do ERRO.
     *
     * <p>O {@code case} existe para nao promover quem esta em AGUARDANDO_AUTORIZACAO (falta o lojista
     * digitar o codigo no portal: dizer CONECTADO prometeria pedido que nao vem) nem rebaixar nada.</p>
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update IntegracaoCanal i set "
            + "i.ultimoPollingEm = :agora, i.ultimoErro = null, "
            + "i.status = case when i.status = 'ERRO' then 'CONECTADO' else i.status end "
            + "where i.id = :id and i.lojaId = :lojaId")
    int marcarPollingOk(@org.springframework.data.repository.query.Param("id") Long id,
                        @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                        @org.springframework.data.repository.query.Param("agora") OffsetDateTime agora);

    /** O polling falhou: a hora da tentativa e a mensagem, sem mexer em mais nada. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update IntegracaoCanal i set "
            + "i.ultimoPollingEm = :agora, i.ultimoErro = :erro where i.id = :id and i.lojaId = :lojaId")
    int marcarPollingFalhou(@org.springframework.data.repository.query.Param("id") Long id,
                            @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                            @org.springframework.data.repository.query.Param("agora") OffsetDateTime agora,
                            @org.springframework.data.repository.query.Param("erro") String erro);

    /** A integracao parou: status e motivo. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update IntegracaoCanal i set "
            + "i.status = 'ERRO', i.ultimoErro = :erro where i.id = :id and i.lojaId = :lojaId")
    int marcarErro(@org.springframework.data.repository.query.Param("id") Long id,
                   @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                   @org.springframework.data.repository.query.Param("erro") String erro);

    /**
     * Recado para a tela de Integracoes <b>sem</b> marcar ERRO: a integracao esta funcionando, foi um
     * aviso que nao passou. Marcar ERRO a jogaria na fila de 10 minutos do item 5.1.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update IntegracaoCanal i set "
            + "i.ultimoErro = :erro where i.id = :id and i.lojaId = :lojaId")
    int marcarAviso(@org.springframework.data.repository.query.Param("id") Long id,
                    @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                    @org.springframework.data.repository.query.Param("erro") String erro);

    /**
     * Tenta pegar a vez de renovar o token: grava a marca se ninguem a tem, ou se a de quem tem
     * venceu. Devolve 1 quando conseguiu, 0 quando outro processo esta renovando.
     *
     * <h2>Item 9.11 da varredura</h2>
     * <p>A trava de antes era {@code synchronized} num objeto da JVM, e trava de JVM nao atravessa
     * processo. No iFood isso e grave: ele <b>invalida o refresh token anterior</b> quando emite o
     * novo, entao duas instancias renovando ao mesmo tempo matam o token uma da outra — e a loja
     * precisa reautorizar do zero, <b>todas as lojas de uma vez</b>, porque todas renovam no mesmo
     * ciclo.</p>
     *
     * <p>E <b>arrendamento</b>, nao {@code select for update}: a renovacao fala com o iFood, e
     * segurar lock de banco durante chamada HTTP e exatamente o defeito dos itens 9.3, 9.4 e 9.5.</p>
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update IntegracaoCanal i set "
            + "i.tokenRenovandoEm = :agora where i.id = :id and i.lojaId = :lojaId "
            + "and (i.tokenRenovandoEm is null or i.tokenRenovandoEm < :venceuAntesDe)")
    int tentarPegarAvezDeRenovar(
            @org.springframework.data.repository.query.Param("id") Long id,
            @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
            @org.springframework.data.repository.query.Param("agora") OffsetDateTime agora,
            @org.springframework.data.repository.query.Param("venceuAntesDe") OffsetDateTime venceuAntesDe);

    /** Devolve a vez: a renovacao terminou (deu certo ou nao). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update IntegracaoCanal i set "
            + "i.tokenRenovandoEm = null where i.id = :id and i.lojaId = :lojaId")
    int devolverAvezDeRenovar(@org.springframework.data.repository.query.Param("id") Long id,
                              @org.springframework.data.repository.query.Param("lojaId") Long lojaId);

    /** Mais um pedido importado. A soma e do BANCO: dois pedidos no mesmo ciclo contavam um. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update IntegracaoCanal i set "
            + "i.pedidosRecebidos = coalesce(i.pedidosRecebidos, 0) + 1, i.ultimaSync = :agora "
            + "where i.id = :id and i.lojaId = :lojaId")
    int contarPedidoRecebido(@org.springframework.data.repository.query.Param("id") Long id,
                             @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                             @org.springframework.data.repository.query.Param("agora") OffsetDateTime agora);
}
