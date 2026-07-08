-- Cupons de desconto do cardápio digital (por loja).
CREATE TABLE cupom (
    id       BIGSERIAL PRIMARY KEY,
    loja_id  BIGINT NOT NULL REFERENCES loja (id),
    codigo   VARCHAR(30) NOT NULL,
    tipo     VARCHAR(12) NOT NULL DEFAULT 'PERCENTUAL', -- PERCENTUAL | VALOR
    valor    NUMERIC(12,2) NOT NULL,
    ativo    BOOLEAN NOT NULL DEFAULT TRUE,
    validade DATE,
    UNIQUE (loja_id, codigo)
);
