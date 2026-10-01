package br.com.bora.security;

import br.com.bora.entity.Loja;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Quem pode entrar no painel de uma loja.
 *
 * <p>Separa duas coisas que viviam misturadas: a <b>decisão da plataforma</b> (suspender ou arquivar
 * um cliente, que já bloqueava) e o <b>fim do prazo de acesso</b> por falta de pagamento, que até
 * agora não bloqueava nada — o aviso de fatura vencida só trocava uma palavra no banco.</p>
 *
 * <p>O corte por falta de pagamento nasce <b>desligado</b> de propósito. O mecanismo fica pronto e
 * testado, mas ligar isso tira cliente do ar: é decisão comercial do dono, não do código. Para
 * ligar: {@code BORA_CORTE_POR_ASSINATURA=true}.</p>
 */
@Component
public class RegraDeAcesso {

    private final boolean cortarPorAssinatura;

    public RegraDeAcesso(@Value("${bora.cobranca.corte-por-assinatura:false}") boolean cortarPorAssinatura) {
        this.cortarPorAssinatura = cortarPorAssinatura;
    }

    /** O corte por falta de pagamento está ligado? */
    public boolean cortePorAssinaturaLigado() {
        return cortarPorAssinatura;
    }

    /** A equipe desta loja pode usar o painel agora? */
    public boolean podeUsarOPainel(Loja loja) {
        if (loja == null) return false;
        if (loja.bloqueadaPelaPlataforma()) return false;
        return !(cortarPorAssinatura && loja.acessoVencido());
    }

    /** Mensagem para quem foi barrado pelo prazo — o suporte precisa distinguir isto de senha errada. */
    public static final String RECADO_PRAZO =
            "O período de uso desta loja terminou. Ative a assinatura em Planos ou fale com o BoraHapp.";
}
