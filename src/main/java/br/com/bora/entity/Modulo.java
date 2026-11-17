package br.com.bora.entity;

/**
 * Os módulos do BoraHapp ERP.
 *
 * <p><b>CORE e PEDIDOS são o módulo principal</b> — o que já está em produção. Não se liga nem se
 * desliga: está sempre incluído. Os outros são produtos que o cliente contrata ou não.</p>
 *
 * <p>Os nomes aqui são os mesmos do {@code CHECK} da coluna {@code licenca_modulo.modulo} (V74).
 * Se divergirem, a gravação falha no banco — que é onde se quer que falhe.</p>
 */
public enum Modulo {

    /** Cadastros mestres, base fiscal, plataforma. Sempre incluído. */
    CORE(true),
    /** Pedidos, caixa, KDS — o que já roda hoje. Sempre incluído. */
    PEDIDOS(true),

    /** Documentos fiscais, apuração, split payment. */
    FISCAL(false),
    /** Ficha técnica, baixa automática, inventário, custo médio e CMV. */
    ESTOQUE(false),
    /** Requisição, cotação, pedido de compra, recebimento. */
    COMPRAS(false),
    /** Pagar/receber, conciliação, fluxo de caixa. */
    FINANCEIRO(false),
    /** Contabilidade por evento, demonstrações, SPED. */
    CONTABIL(false),
    /** Painel do Dono, KPIs, alertas. */
    GESTAO(false),
    /** Entregas, roteirização, acerto de entregador. */
    LOGISTICA(false),
    /** Add-on pago de IA — já existe em produção, por {@code loja.modulo_ia}. */
    IA(false);

    /** Faz parte do módulo principal: está sempre ligado e não depende de licença. */
    public final boolean sempreIncluido;

    Modulo(boolean sempreIncluido) {
        this.sempreIncluido = sempreIncluido;
    }
}
