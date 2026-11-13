package br.com.bora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

/** Conexão de uma loja com um marketplace (iFood, 99Food, Rappi, Uber Eats…). */
@Entity
@Table(name = "integracao_canal", uniqueConstraints = @UniqueConstraint(columnNames = {"loja_id", "canal"}))
@Getter
@Setter
public class IntegracaoCanal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "loja_id")
    public Long lojaId;

    public String canal;          // código: IFOOD, NOVE_NOVE, RAPPI, UBER_EATS, AIQFOME, GOOMER...
    public Boolean ativo = false;

    @Column(name = "merchant_id")
    public String merchantId;     // id do estabelecimento no marketplace (na 99, o APPShopID)

    /**
     * Identificador alternativo da loja no canal — na 99Food, o "ID do loja" numerico.
     *
     * <p>Existe porque o {@code merchantId} precisa ser o APPShopID (a 99 exige
     * {@code ClientId = AppID_AppShopId}), mas o webhook de entrada chega com o header
     * {@code X-App-MerchantId} e a documentacao nao diz qual dos dois identificadores ela manda.
     * Guardamos os dois e aceitamos qualquer um ao rotear — errar aqui perde pedido de verdade.</p>
     */
    @Column(name = "shop_id_canal")
    public String shopIdCanal;
    @Column(name = "client_id")
    public String clientId;
    @Column(name = "client_secret")
    @jakarta.persistence.Convert(converter = br.com.bora.config.SegredoGuardado.class)
    public String clientSecret;   // credencial (token/secret) — preenchida pelo lojista

    @Column(name = "webhook_token")
    @jakarta.persistence.Convert(converter = br.com.bora.config.SegredoGuardado.class)
    public String webhookToken;   // segredo que valida os webhooks de entrada

    @Column(name = "auto_aceitar")
    public Boolean autoAceitar = true;

    // ---- OAuth do marketplace (V28) ----
    // O app e da plataforma (env); aqui ficam os tokens que ESTA loja autorizou.
    @Column(name = "access_token")
    @jakarta.persistence.Convert(converter = br.com.bora.config.SegredoGuardado.class)
    public String accessToken;
    @Column(name = "refresh_token")
    @jakarta.persistence.Convert(converter = br.com.bora.config.SegredoGuardado.class)
    public String refreshToken;
    @Column(name = "token_expira_em")
    public OffsetDateTime tokenExpiraEm;

    // Fluxo distribuido do iFood: o lojista digita o userCode no portal do parceiro.
    @Column(name = "user_code")
    public String userCode;
    @Column(name = "code_verifier")
    public String codeVerifier;
    @Column(name = "verification_url")
    public String verificationUrl;
    @Column(name = "vinculo_expira_em")
    public OffsetDateTime vinculoExpiraEm;

    /**
     * Quando alguem comecou a renovar o token desta integracao (item 9.11).
     *
     * <p>Arrendamento de {@value #ARRENDAMENTO_MIN} minutos: passado esse tempo, outro processo pode
     * assumir — quem tinha a marca provavelmente morreu no meio. Nulo = ninguem renovando.</p>
     */
    @Column(name = "token_renovando_em")
    public OffsetDateTime tokenRenovandoEm;

    /** Quanto tempo o arrendamento da renovacao vale. Curto: renovar leva segundos. */
    public static final long ARRENDAMENTO_MIN = 2;

    @Column(name = "ultimo_polling_em")
    public OffsetDateTime ultimoPollingEm;

    /**
     * Falhas CONSECUTIVAS da busca de pedido. Zerada pelo {@code marcarPollingOk}.
     *
     * <p>Existe para a loja parar de ficar VERDE quando cai. O {@code marcarPollingFalhou} gravava a
     * hora e a mensagem e nao mexia no status: uma integracao CONECTADO que comeca a levar 403 em
     * toda consulta seguia verde na tela, com o motivo escondido numa coluna que ninguem le. Foi
     * assim na loja 18 em 04/11/2026, e e o mesmo "morre em silencio" que custou dois meses no
     * 99Food.</p>
     *
     * <p><b>Por que contar em vez de rebaixar na primeira falha:</b> ERRO tem preco. O
     * {@link #mereceNovaTentativa()} espaca a tentativa seguinte em {@value #ESPERA_APOS_ERRO_MIN}
     * minutos, entao rebaixar no primeiro tropeco faria um 500 passageiro do marketplace atrasar
     * pedido real em ate 10 min -- trocaria o defeito de silencio por um defeito de atraso. Com o
     * ciclo de 30 s e o limite de {@value #FALHAS_PARA_REBAIXAR}, uma queda de verdade fica vermelha
     * em cerca de um minuto e meio, e um tropeco isolado nao custa nada.</p>
     *
     * <p>Falha de AUTENTICACAO nao passa por aqui: ela tem caminho proprio, que marca ERRO na hora e
     * com o motivo certo.</p>
     */
    @Column(name = "falhas_seguidas", nullable = false)
    public int falhasSeguidas = 0;

    /** Quantas falhas seguidas rebaixam CONECTADO para ERRO. Ver {@link #falhasSeguidas}. */
    public static final int FALHAS_PARA_REBAIXAR = 3;

    /**
     * SAUDE da integracao: falha de polling, de autenticacao, de vinculo.
     *
     * <p>E apagada por {@code marcarPollingOk} quando o polling volta a funcionar — e tem de ser,
     * senao a tela mostra erro de uma integracao que esta boa.</p>
     *
     * <p><b>Recado sobre um pedido nao vai aqui.</b> Ver {@link #ultimoAviso}.</p>
     */
    @Column(name = "ultimo_erro")
    public String ultimoErro;

    /**
     * RECADO para o lojista sobre um pedido — hoje, "o marketplace recusou este status".
     *
     * <p>Coluna propria porque o significado e o oposto do {@link #ultimoErro}: o polling bom NAO
     * apaga este campo. Antes os dois moravam no {@code ultimo_erro}, e o poller (a cada 30s) apagava
     * o recado — o lojista tinha ate meio minuto para ve-lo, e so com a tela aberta na hora.</p>
     *
     * <p>Por que isso importa: na 99 a ordem dos status e obrigatoria, entao depois de um status
     * recusado os seguintes DO MESMO PEDIDO tambem sao recusados. O lojista avanca no painel, ve
     * tudo verde do lado dele, e lá dentro o pedido ficou parado. Ver V61.</p>
     */
    @Column(name = "ultimo_aviso")
    public String ultimoAviso;

    /** Quando o {@link #ultimoAviso} foi gravado. Recado sem data nao da para usar. */
    @Column(name = "ultimo_aviso_em")
    public OffsetDateTime ultimoAvisoEm;

    /**
     * DESCONECTADO | PRONTO | AGUARDANDO_AUTORIZACAO | CONECTADO | ERRO.
     *
     * <p>O {@code PRONTO} faltava nesta lista e existe desde sempre no {@code IntegracaoService}:
     * credencial cadastrada e canal ainda nao ligado. Na producao de 12/11/2026 ha uma integracao
     * assim (o iFood da loja 18). Quem rebaixa status olhando esta coluna tem de tratar os CINCO --
     * e por isso que o {@code marcarPollingFalhou} so rebaixa quem esta {@code CONECTADO}, em vez de
     * "qualquer coisa que nao seja ERRO".</p>
     */
    public String status = "DESCONECTADO";
    /**
     * ULTIMA COISA QUE CHEGOU por este canal -- e nao "ultimo pedido", por mais que o nome sugira.
     *
     * <p>Medido em 13/11/2026 antes de usar isto para contar tempo: quatro caminhos escrevem aqui,
     * e um deles ({@code PublicController}, webhook do PIX) nao e pedido nenhum; alem disso o
     * {@code descontarRecebido} desconta o contador sem mexer na data. Na producao daquele dia a
     * loja 18 tinha {@code ultima_sync} de 21/10 com {@code pedidos_recebidos = 0}, e o PIX da loja
     * 1 tinha data com o contador zerado. Para "ha quanto tempo esta ligada" use
     * {@link #conectadoEm}.</p>
     */
    @Column(name = "ultima_sync")
    public OffsetDateTime ultimaSync;

    /**
     * Desde quando esta integracao esta ligada E FUNCIONANDO: a primeira busca de pedido que deu
     * certo nesta ligacao.
     *
     * <p>E o que falta para o aviso "Conectado, sem pedido ainda" virar diagnostico. Ligado ha duas
     * horas sem pedido e uma tarde fraca; ligado ha cinco dias sem nenhum pedido e vinculo errado do
     * lado do marketplace -- e so a duracao separa os dois.</p>
     *
     * <p><b>Escrita num lugar so</b>, o {@code marcarPollingOk}, com {@code coalesce}. Nao em quem
     * grava o status: {@code CONECTADO} e escrito em OITO lugares do codigo, e carimbar a data nos
     * oito e garantir que o nono esqueca, calado -- o padrao que este projeto vive consertando. Alem
     * disso "o poller foi atendido pelo marketplace" e uma afirmacao mais forte do que "alguem
     * marcou a coluna".</p>
     *
     * <p><b>Nao reinicia</b> quando a integracao se levanta de um ERRO, de proposito: uma queda de
     * dez minutos nao desmente uma seca de tres dias, e e a seca que o lojista precisa ver. Quem
     * zera e a troca de vinculo, que e situacao nova de verdade.</p>
     *
     * <p>Nula em canal que nao faz polling (PIX, WhatsApp, manual) e nas integracoes anteriores a
     * V73 -- ali nao ha de onde tirar a data real, e inventar {@code now()} trocaria "nao sei" por
     * uma mentira precisa.</p>
     */
    @Column(name = "conectado_em")
    public OffsetDateTime conectadoEm;
    @Column(name = "pedidos_recebidos")
    public Integer pedidosRecebidos = 0;

    /** Tem o mínimo para falar com a API do canal: loja identificada e vínculo concluído. */
    public boolean prontaParaSincronizar() {
        return merchantId != null && !merchantId.isBlank() && "CONECTADO".equals(status);
    }

    /** Minutos entre duas tentativas de uma integracao que caiu em ERRO. */
    private static final long ESPERA_APOS_ERRO_MIN = 10;

    /**
     * Integracao em ERRO merece nova tentativa, de vez em quando?
     *
     * <p>Antes, cair em ERRO era definitivo: o ciclo so olhava quem estava CONECTADO, entao a
     * integracao saia da fila e nunca mais tentava — mesmo depois de o problema ser resolvido. Em
     * 06/10/2026 isso aconteceu de verdade: uma credencial foi trocada por engano, a loja caiu em
     * ERRO, a credencial foi restaurada e ela continuou parada. So voltou quando alguem clicou em
     * Conectar. Ninguem descobre isso pela tela, que mostra "ultimo erro" sem dizer que o sistema
     * desistiu.</p>
     *
     * <p>Tentar a cada 30s com credencial errada seria martelar o marketplace e arriscar bloqueio.
     * Por isso a nova tentativa e espacada: uma a cada {@value #ESPERA_APOS_ERRO_MIN} minutos. Se der
     * certo, o proprio cliente limpa o erro e devolve o status para CONECTADO.</p>
     */
    public boolean mereceNovaTentativa() {
        if (merchantId == null || merchantId.isBlank() || !"ERRO".equals(status)) return false;
        return ultimoPollingEm == null
                || ultimoPollingEm.isBefore(OffsetDateTime.now().minusMinutes(ESPERA_APOS_ERRO_MIN));
    }
}
