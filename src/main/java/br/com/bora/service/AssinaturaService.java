package br.com.bora.service;

import br.com.bora.entity.Assinatura;
import br.com.bora.entity.Loja;
import br.com.bora.entity.Papel;
import br.com.bora.entity.Plano;
import br.com.bora.entity.StatusAssinatura;
import br.com.bora.entity.Usuario;
import br.com.bora.repository.AssinaturaRepository;
import br.com.bora.repository.LojaRepository;
import br.com.bora.repository.UsuarioRepository;
import br.com.bora.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

/** Cobrança recorrente via Asaas: cria a assinatura e reage aos webhooks de pagamento. */
@Service
public class AssinaturaService {

    private final AssinaturaRepository repo;
    private final LojaRepository lojas;
    private final UsuarioRepository usuarios;
    private final AsaasClient asaas;
    private final AuthContext ctx;

    public AssinaturaService(AssinaturaRepository repo, LojaRepository lojas, UsuarioRepository usuarios,
                             AsaasClient asaas, AuthContext ctx) {
        this.repo = repo;
        this.lojas = lojas;
        this.usuarios = usuarios;
        this.asaas = asaas;
        this.ctx = ctx;
    }

    /** Assinatura da loja logada (ou null se ainda não assinou). */
    public Assinatura status() {
        return repo.findByLojaId(ctx.lojaId()).orElse(null);
    }

    /** Assina o plano atual da loja no Asaas (cria cliente + assinatura mensal). */
    @Transactional
    public Assinatura assinar(String cpfCnpj) {
        ctx.requirePapel("ADMINISTRADOR_LOJA");
        Long lojaId = ctx.lojaId();
        Assinatura jaAtiva = repo.findByLojaId(lojaId).orElse(null);
        if (jaAtiva != null && jaAtiva.getStatus() == StatusAssinatura.ATIVA) {
            return jaAtiva; // já há assinatura ativa — não recria nem reverte para PENDENTE
        }
        Loja loja = lojas.findById(lojaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
        Plano plano = loja.getPlano() == null ? Plano.UNICO : loja.getPlano();
        String email = usuarios.findByLojaId(lojaId).stream()
                .filter(u -> u.getPapel() == Papel.ADMINISTRADOR_LOJA)
                .findFirst().map(Usuario::getEmail).orElse(null);

        Assinatura a = repo.findByLojaId(lojaId).orElseGet(() -> {
            Assinatura nova = new Assinatura();
            nova.setLojaId(lojaId);
            return nova;
        });
        a.setPlano(plano);
        a.setValor(loja.precoEfetivo()); // respeita preço negociado por loja (ex.: fundador R$149)
        if (a.getAsaasCustomerId() == null) {
            a.setAsaasCustomerId(asaas.criarCliente(loja.getNome(), email, cpfCnpj));
        }
        String nextDue = LocalDate.now().plusDays(7).toString(); // 7 dias de cortesia antes da 1ª cobrança
        Map<String, Object> sub = asaas.criarAssinatura(a.getAsaasCustomerId(), loja.precoEfetivo().doubleValue(),
                "BoraHapp " + plano.name() + " - " + loja.getNome(), nextDue);
        a.setAsaasSubscriptionId(sub == null ? null : (String) sub.get("id"));
        a.setStatus(StatusAssinatura.PENDENTE);
        a.setAtualizadoEm(OffsetDateTime.now());
        return repo.save(a);
    }

    /** Reage aos eventos de pagamento do Asaas (webhook): ativa/suspende a loja conforme o pagamento. */
    @Transactional
    public void processarWebhook(String event, String subscriptionId) {
        if (event == null || subscriptionId == null) return;
        repo.findByAsaasSubscriptionId(subscriptionId).ifPresent(a -> {
            switch (event) {
                case "PAYMENT_CONFIRMED", "PAYMENT_RECEIVED" -> {
                    a.setStatus(StatusAssinatura.ATIVA);
                    ativarLoja(a.getLojaId(), true);
                }
                case "PAYMENT_OVERDUE" -> a.setStatus(StatusAssinatura.INADIMPLENTE);
                case "PAYMENT_DELETED", "SUBSCRIPTION_DELETED" -> {
                    a.setStatus(StatusAssinatura.CANCELADA);
                    ativarLoja(a.getLojaId(), false);
                }
                default -> { /* demais eventos: ignorados */ }
            }
            a.setAtualizadoEm(OffsetDateTime.now());
            repo.save(a);
        });
    }

    private void ativarLoja(Long lojaId, boolean ativo) {
        lojas.findById(lojaId).ifPresent(l -> {
            // Pagamento não levanta suspensão administrativa: quem suspendeu foi a plataforma,
            // e só a plataforma reativa. Sem esta guarda, a mensalidade seguinte desfaria a decisão.
            if (ativo && l.bloqueadaPelaPlataforma()) return;
            l.setAtivo(ativo);
            lojas.save(l);
        });
    }

    /**
     * Cancela a assinatura da loja no Asaas por decisão da plataforma (suspender/arquivar cliente).
     * Não lança quando a cobrança não está configurada ou a assinatura não existe — o cancelamento
     * do cliente aqui não pode ficar refém do gateway; devolve o que aconteceu para o admin ver.
     */
    @Transactional
    public String cancelarPorAdministracao(Long lojaId) {
        Assinatura a = repo.findByLojaId(lojaId).orElse(null);
        if (a == null) return "sem assinatura";
        if (a.getStatus() == StatusAssinatura.CANCELADA) return "assinatura já cancelada";
        String sub = a.getAsaasSubscriptionId();
        String resultado;
        if (sub == null || sub.isBlank()) {
            resultado = "assinatura sem id no Asaas";
        } else if (!asaas.configurado()) {
            resultado = "ATENÇÃO: cobrança não configurada, cancele no Asaas manualmente";
        } else {
            try {
                resultado = asaas.cancelarAssinatura(sub)
                        ? "assinatura cancelada no Asaas"
                        : "assinatura não encontrada no Asaas";
            } catch (Exception e) {
                return "ATENÇÃO: falha ao cancelar no Asaas (" + e.getMessage()
                        + ") — cancele manualmente para o cliente parar de ser cobrado";
            }
        }
        a.setStatus(StatusAssinatura.CANCELADA);
        a.setAtualizadoEm(OffsetDateTime.now());
        repo.save(a);
        return resultado;
    }
}
