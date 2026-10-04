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
 * ligar: {@code BORA_COBRANCA_CORTE_POR_ASSINATURA=true} no ambiente do servidor.
 * <b>O nome tem que ter o "COBRANCA" no meio.</b> O comentario daqui dizia {@code BORA_CORTE_POR_ASSINATURA},
 * esse nome foi para o servidor, e o Spring nunca o ligou a esta propriedade: a variavel ficou
 * "true" no arquivo de ambiente e o corte seguiu desligado, sem erro e sem aviso no log.</p>
 */
@Component
public class RegraDeAcesso {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RegraDeAcesso.class);

    private final boolean cortarPorAssinatura;

    public RegraDeAcesso(@Value("${bora.cobranca.corte-por-assinatura:false}") boolean cortarPorAssinatura) {
        this.cortarPorAssinatura = cortarPorAssinatura;
    }

    /**
     * Diz na subida se o corte esta ligado.
     *
     * <p>Foi a falta disto que escondeu o problema por dias: a variavel estava "true" no servidor com o
     * nome errado, a propriedade caiu no padrao "false", e nada no log falava do assunto. Quem subiu o
     * sistema nao tinha como saber que a decisao comercial nao estava valendo. Uma linha na subida e
     * mais barata que uma semana de cliente inadimplente operando de graca — ou que um corte ligado
     * sem ninguem perceber.</p>
     */
    @jakarta.annotation.PostConstruct
    void dizerSeEstaLigado() {
        if (cortarPorAssinatura) log.info(avisoDeSubida()); else log.warn(avisoDeSubida());
    }

    /** O texto do aviso, separado do log para poder ser conferido por teste. */
    String avisoDeSubida() {
        return cortarPorAssinatura
                ? "Corte por falta de pagamento: LIGADO (loja com acesso vencido fica fora do ar)"
                : "Corte por falta de pagamento: DESLIGADO. Para ligar, "
                  + "BORA_COBRANCA_CORTE_POR_ASSINATURA=true no ambiente — o nome tem COBRANCA no meio";
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
