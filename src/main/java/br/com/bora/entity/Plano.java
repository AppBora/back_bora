package br.com.bora.entity;

/**
 * Plano SaaS único (decisão 2026-07-05): R$ 299/mês POR LOJA, pedidos ilimitados,
 * até 15 usuários por loja, todos os recursos de white-label liberados.
 * maxPedidosMes = 0 significa ilimitado (PlanoService não aplica o limite).
 */
public enum Plano {
    UNICO(15, 0, 299.00);

    public final int maxUsuarios;
    public final int maxPedidosMes; // 0 = ilimitado
    public final double precoMensal; // R$/mês por loja

    Plano(int maxUsuarios, int maxPedidosMes, double precoMensal) {
        this.maxUsuarios = maxUsuarios;
        this.maxPedidosMes = maxPedidosMes;
        this.precoMensal = precoMensal;
    }

    public boolean pedidosIlimitados() { return maxPedidosMes <= 0; }

    // White-label: tudo liberado no plano único (PERSONALIZACAO_WHITE_LABEL.md)
    public boolean permiteCoresSecundarias() { return true; }
    public boolean permiteBanner()           { return true; }
    public boolean permiteSubdominio()       { return true; }
    public boolean permiteRemoverMarca()     { return false; } // Enterprise futuro
}
