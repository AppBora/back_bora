CREATE TABLE IF NOT EXISTS integracao_canal (
    id                BIGSERIAL PRIMARY KEY,
    loja_id           BIGINT NOT NULL,
    canal             VARCHAR(40) NOT NULL,
    ativo             BOOLEAN NOT NULL DEFAULT FALSE,
    merchant_id       VARCHAR(160),
    client_id         VARCHAR(160),
    client_secret     VARCHAR(400),
    webhook_token     VARCHAR(80),
    auto_aceitar      BOOLEAN NOT NULL DEFAULT TRUE,
    status            VARCHAR(20) NOT NULL DEFAULT 'DESCONECTADO',
    ultima_sync       TIMESTAMPTZ,
    pedidos_recebidos INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_integracao_loja_canal UNIQUE (loja_id, canal)
);
CREATE INDEX IF NOT EXISTS idx_integracao_loja ON integracao_canal(loja_id);

-- marca pedidos vindos de marketplace para sincronização de status
ALTER TABLE pedido ADD COLUMN IF NOT EXISTS canal_externo   VARCHAR(40);
ALTER TABLE pedido ADD COLUMN IF NOT EXISTS id_externo      VARCHAR(120);
