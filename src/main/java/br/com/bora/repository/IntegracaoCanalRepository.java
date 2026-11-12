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

    /** O mesmo header pode trazer o outro identificador (o "ID do loja"); aceitamos os dois. */
    Optional<IntegracaoCanal> findFirstByCanalAndShopIdCanal(String canal, String shopIdCanal);

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
            + "i.ultimoPollingEm = :agora, i.ultimoErro = null, i.falhasSeguidas = 0, "
            + "i.status = case when i.status = 'ERRO' then 'CONECTADO' else i.status end "
            + "where i.id = :id and i.lojaId = :lojaId")
    int marcarPollingOk(@org.springframework.data.repository.query.Param("id") Long id,
                        @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                        @org.springframework.data.repository.query.Param("agora") OffsetDateTime agora);

    /**
     * O polling falhou: a hora, a mensagem, mais uma na conta de falhas seguidas -- e, no limite,
     * CONECTADO cai para ERRO.
     *
     * <h2>O defeito que isto fecha (aberto desde sempre, reconferido em 10/11/2026)</h2>
     * <p>Esta consulta gravava "a hora da tentativa e a mensagem, sem mexer em mais nada". O caminho
     * inverso existia -- o {@link #marcarPollingOk} promove ERRO para CONECTADO --, mas a queda nao
     * rebaixava: uma integracao CONECTADO que comeca a levar 403 em TODA consulta continuava
     * <b>verde</b> na tela de Integracoes, com o motivo escondido no {@code ultimo_erro}, que
     * ninguem le. Medido na producao em 04/11/2026: a loja 18 com {@code status = CONECTADO} e
     * {@code ultimo_erro = 403 ... forbidden}. E o mesmo "morre em silencio" que custou dois meses no
     * 99Food, e agora que as 5 lojas da Zira dependem desse canal, e a trava que importa.</p>
     *
     * <h2>Por que no LIMITE, e nao na primeira falha</h2>
     * <p>Porque ERRO tem preco: o {@code mereceNovaTentativa()} espaca a tentativa seguinte em 10
     * minutos. Rebaixar no primeiro tropeco faria um 500 passageiro do marketplace atrasar pedido
     * real em ate 10 min -- seria trocar um defeito de silencio por um defeito de atraso. Com o ciclo
     * de 30 s e o limite de {@code FALHAS_PARA_REBAIXAR}, uma queda de verdade fica vermelha em cerca
     * de um minuto e meio.</p>
     *
     * <h2>Duas finezas da consulta</h2>
     * <p>O {@code case} le {@code i.falhasSeguidas} <b>antes</b> do incremento -- num UPDATE, todas as
     * expressoes do SET veem a linha como ela esta --, por isso compara {@code + 1}. E so rebaixa
     * quem esta CONECTADO: DESCONECTADO e AGUARDANDO_AUTORIZACAO nao sao falha de polling, e
     * sobrescrever qualquer um dos dois com ERRO apagaria a informacao de que o lojista ainda tem um
     * passo a dar no portal.</p>
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update IntegracaoCanal i set "
            + "i.ultimoPollingEm = :agora, i.ultimoErro = :erro, "
            + "i.falhasSeguidas = i.falhasSeguidas + 1, "
            + "i.status = case when i.status = 'CONECTADO' and i.falhasSeguidas + 1 >= :limite "
            + "then 'ERRO' else i.status end "
            + "where i.id = :id and i.lojaId = :lojaId")
    int marcarPollingFalhou(@org.springframework.data.repository.query.Param("id") Long id,
                            @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                            @org.springframework.data.repository.query.Param("agora") OffsetDateTime agora,
                            @org.springframework.data.repository.query.Param("erro") String erro,
                            @org.springframework.data.repository.query.Param("limite") int limite);

    /**
     * O mesmo de cima com o limite padrao, para nao espalhar a constante pelos clientes.
     *
     * <p>{@code default} na interface do repositorio: chamada de metodo {@code default} para outro
     * metodo da MESMA interface passa pelo proxy do Spring Data normalmente -- o proxy e o objeto que
     * recebe a chamada de dentro.</p>
     */
    default int marcarPollingFalhou(Long id, Long lojaId, OffsetDateTime agora, String erro) {
        return marcarPollingFalhou(id, lojaId, agora, erro,
                br.com.bora.entity.IntegracaoCanal.FALHAS_PARA_REBAIXAR);
    }

    /**
     * O canal RECUSOU o token em cache (401): joga o token fora, junto com a hora e o motivo.
     *
     * <p>Sem isto, a proxima rodada manda <b>o mesmo token morto</b>, e assim por diante ate o
     * {@code tokenExpiraEm} -- 1 h na 99Food, 6 h no iFood. Com o token nulo, o {@code vigente()} dos
     * dois clientes devolve false e a rodada seguinte (30 s) reautentica. Se a credencial estiver de
     * fato morta, o {@code falha()} do caminho de autenticacao marca ERRO com o motivo certo -- entao
     * a tela passa a dizer a verdade em meio minuto, em vez de horas.</p>
     *
     * <p><b>NAO mexe no {@code refreshToken}</b>: no iFood ele pode continuar valendo, e e por ele
     * que a renovacao acontece sem novo login.</p>
     *
     * <p>Colunas nomeadas uma a uma, como as outras daqui (item 5.5): {@code save(i)} regravaria a
     * linha inteira com valores de ate 25 segundos atras e desfaria o que o lojista salvou agora.</p>
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update IntegracaoCanal i set "
            + "i.accessToken = null, i.tokenExpiraEm = null, "
            + "i.ultimoPollingEm = :agora, i.ultimoErro = :erro "
            + "where i.id = :id and i.lojaId = :lojaId")
    int esquecerTokenRecusado(@org.springframework.data.repository.query.Param("id") Long id,
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
     *
     * <p><b>Grava na coluna propria do recado</b> ({@code ultimo_aviso}), e nao no
     * {@code ultimo_erro}. Era ali que ficava, e o {@link #marcarPollingOk} — que roda a cada 30
     * segundos — apagava: o lojista tinha ate meio minuto para ver, e so com a tela aberta na hora.
     * Ver V61.</p>
     *
     * <p>A hora vai junto porque recado sem data nao da para usar: "a 99 nao aceitou o status do
     * pedido 1234" e outra informacao se foi agora ou se foi semana passada.</p>
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update IntegracaoCanal i set "
            + "i.ultimoAviso = :erro, i.ultimoAvisoEm = :agora where i.id = :id and i.lojaId = :lojaId")
    int marcarAviso(@org.springframework.data.repository.query.Param("id") Long id,
                    @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                    @org.springframework.data.repository.query.Param("erro") String erro,
                    @org.springframework.data.repository.query.Param("agora") OffsetDateTime agora);

    /**
     * A integracao esta ativa mas NAO PODE sincronizar: grava o motivo onde o lojista ve.
     *
     * <h2>O defeito que isto fecha (achado em 25/10/2026, atacando o item 24)</h2>
     * <p>O poller fazia {@code continue} em silencio quando {@code prontaParaSincronizar()} era
     * falso — sem log, sem nada. A integracao ficava {@code CONECTADO} e {@code ativo} para sempre,
     * sem nunca buscar um pedido, e ninguem era avisado. Medido na producao: a loja 17 estava assim,
     * com {@code merchant_id} vazio e {@code ultimo_polling_em} NULO — ou seja, nunca buscou nada
     * desde que foi criada. O painel dizia conectado.</p>
     *
     * <p>Repare no contraste: o caso vizinho no mesmo laco ("a loja nao pode operar") JA logava.
     * Este nao.</p>
     *
     * <h2>Por que no {@code ultimo_erro} e nao no {@code ultimo_aviso}</h2>
     * <p>Isto e SAUDE da integracao, nao recado sobre um pedido: e uma condicao do presente que
     * deixa de existir sozinha quando o lojista preenche o que falta. E o ciclo de vida fecha
     * exatamente certo — quando o dado chega, o polling comeca a rodar, da certo, e o
     * {@link #marcarPollingOk} limpa a mensagem. Por isso NAO vai na coluna do recado, que so o
     * lojista apaga. Ver V61 para a diferenca entre as duas.</p>
     *
     * <p>Nao mexe no status de proposito: {@code ERRO} jogaria a loja na fila de 10 minutos do item
     * 5.1, e isto nao e falha — e configuracao incompleta.</p>
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update IntegracaoCanal i set "
            + "i.ultimoErro = :motivo where i.id = :id and i.lojaId = :lojaId")
    int marcarImpedimento(@org.springframework.data.repository.query.Param("id") Long id,
                          @org.springframework.data.repository.query.Param("lojaId") Long lojaId,
                          @org.springframework.data.repository.query.Param("motivo") String motivo);

    /**
     * O lojista leu o recado e mandou tirar da tela.
     *
     * <p>E o unico jeito de limpar o {@code ultimo_aviso}, de proposito: nada que acontece depois
     * desfaz o fato de que um status foi recusado e o pedido ficou parado do outro lado. Quem decide
     * que o assunto esta resolvido e quem atende a loja.</p>
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update IntegracaoCanal i set "
            + "i.ultimoAviso = null, i.ultimoAvisoEm = null where i.id = :id and i.lojaId = :lojaId")
    int limparAviso(@org.springframework.data.repository.query.Param("id") Long id,
                    @org.springframework.data.repository.query.Param("lojaId") Long lojaId);

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
