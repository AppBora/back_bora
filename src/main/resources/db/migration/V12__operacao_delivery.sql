-- Pacote operação de delivery: taxas por bairro, formas de pagamento, horário, motivos de cancelamento
CREATE TABLE IF NOT EXISTS taxa_entrega (
    id        BIGSERIAL PRIMARY KEY,
    loja_id   BIGINT NOT NULL,
    bairro    VARCHAR(120) NOT NULL,
    taxa      NUMERIC(12,2) NOT NULL DEFAULT 0,
    tempo_min INTEGER,
    ativo     BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX IF NOT EXISTS idx_taxa_loja ON taxa_entrega(loja_id);

CREATE TABLE IF NOT EXISTS forma_pagamento (
    id        BIGSERIAL PRIMARY KEY,
    loja_id   BIGINT NOT NULL,
    descricao VARCHAR(80) NOT NULL,
    com_troco BOOLEAN NOT NULL DEFAULT FALSE,
    online    BOOLEAN NOT NULL DEFAULT FALSE,
    ativo     BOOLEAN NOT NULL DEFAULT TRUE,
    ordem     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_formapg_loja ON forma_pagamento(loja_id);

CREATE TABLE IF NOT EXISTS horario_funcionamento (
    id        BIGSERIAL PRIMARY KEY,
    loja_id   BIGINT NOT NULL,
    dia       INTEGER NOT NULL,   -- 0=domingo ... 6=sábado
    abre      VARCHAR(5),         -- "18:00"
    fecha     VARCHAR(5),         -- "23:30"
    aberto    BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX IF NOT EXISTS idx_horario_loja ON horario_funcionamento(loja_id);

CREATE TABLE IF NOT EXISTS motivo_cancelamento (
    id        BIGSERIAL PRIMARY KEY,
    loja_id   BIGINT NOT NULL,
    descricao VARCHAR(120) NOT NULL,
    ativo     BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX IF NOT EXISTS idx_motivo_loja ON motivo_cancelamento(loja_id);

ALTER TABLE pedido ADD COLUMN IF NOT EXISTS taxa_entrega NUMERIC(12,2) NOT NULL DEFAULT 0;
