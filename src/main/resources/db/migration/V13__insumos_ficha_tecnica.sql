-- Insumos (ingredientes) + ficha técnica (composição dos produtos)
CREATE TABLE IF NOT EXISTS insumo (
    id             BIGSERIAL PRIMARY KEY,
    loja_id        BIGINT NOT NULL,
    nome           VARCHAR(120) NOT NULL,
    unidade        VARCHAR(10) NOT NULL DEFAULT 'un',  -- un, kg, g, L, ml
    custo          NUMERIC(12,4) NOT NULL DEFAULT 0,    -- custo por unidade
    estoque        NUMERIC(14,3),                        -- NULL = não controla
    estoque_minimo NUMERIC(14,3)
);
CREATE INDEX IF NOT EXISTS idx_insumo_loja ON insumo(loja_id);

CREATE TABLE IF NOT EXISTS produto_insumo (
    id          BIGSERIAL PRIMARY KEY,
    loja_id     BIGINT NOT NULL,
    produto_id  BIGINT NOT NULL,
    insumo_id   BIGINT NOT NULL,
    quantidade  NUMERIC(14,4) NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_prodinsumo_loja_prod ON produto_insumo(loja_id, produto_id);
