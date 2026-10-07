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
    public String merchantId;     // id do estabelecimento no marketplace
    @Column(name = "client_id")
    public String clientId;
    @Column(name = "client_secret")
    public String clientSecret;   // credencial (token/secret) — preenchida pelo lojista

    @Column(name = "webhook_token")
    public String webhookToken;   // segredo que valida os webhooks de entrada

    @Column(name = "auto_aceitar")
    public Boolean autoAceitar = true;

    // ---- OAuth do marketplace (V28) ----
    // O app e da plataforma (env); aqui ficam os tokens que ESTA loja autorizou.
    @Column(name = "access_token")
    public String accessToken;
    @Column(name = "refresh_token")
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

    @Column(name = "ultimo_polling_em")
    public OffsetDateTime ultimoPollingEm;
    @Column(name = "ultimo_erro")
    public String ultimoErro;

    public String status = "DESCONECTADO";   // DESCONECTADO | AGUARDANDO_AUTORIZACAO | CONECTADO | ERRO
    @Column(name = "ultima_sync")
    public OffsetDateTime ultimaSync;
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
