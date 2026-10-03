package br.com.bora.security;

import br.com.bora.entity.Loja;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Se uma loja pode operar agora — painel, cardápio público e marketplaces.
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

    /**
     * Esta loja pode operar agora?
     *
     * <p>Vale para tudo que movimenta a loja, não só o painel. O robô dos marketplaces não perguntava
     * nada disto: loja suspensa seguia online no iFood, puxando e aceitando pedido sozinha, enquanto
     * ninguém conseguia abrir a tela para preparar. O cliente final pedia, o marketplace confirmava, e
     * a cozinha não existia.</p>
     */
    public boolean podeOperar(Loja loja) {
        if (loja == null) return false;
        if (loja.bloqueadaPelaPlataforma()) return false;
        return !(cortarPorAssinatura && loja.acessoVencido());
    }

    /** Mensagem para quem foi barrado pelo prazo — o suporte precisa distinguir isto de senha errada. */
    public static final String RECADO_PRAZO =
            "O período de uso desta loja terminou. Ative a assinatura em Planos ou fale com o BoraHapp.";
}
