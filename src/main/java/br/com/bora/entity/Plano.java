package br.com.bora.entity;

/** Planos SaaS (PLANOS_SAAS.md) com seus limites e recursos de white-label. */
public enum Plano {
    START(2, 300),
    PRO(5, 1000),
    PREMIUM(10, 3000);

    public final int maxUsuarios;
    public final int maxPedidosMes;

    Plano(int maxUsuarios, int maxPedidosMes) {
        this.maxUsuarios = maxUsuarios;
        this.maxPedidosMes = maxPedidosMes;
    }

    // White-label permitido por plano (PERSONALIZACAO_WHITE_LABEL.md)
    public boolean permiteCoresSecundarias() { return this != START; }
    public boolean permiteBanner()           { return this != START; }
    public boolean permiteSubdominio()       { return this == PREMIUM; }
    public boolean permiteRemoverMarca()     { return false; } // Enterprise futuro
}
