CREATE TABLE IF NOT EXISTS entregador (
    id        BIGSERIAL PRIMARY KEY,
    loja_id   BIGINT NOT NULL,
    nome      VARCHAR(120) NOT NULL,
    telefone  VARCHAR(40),
    veiculo   VARCHAR(40),
    ativo     BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX IF NOT EXISTS idx_entregador_loja ON entregador(loja_id);
