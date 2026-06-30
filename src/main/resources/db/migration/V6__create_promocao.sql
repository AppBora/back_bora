CREATE TABLE promocao (
    id                  BIGSERIAL PRIMARY KEY,
    loja_id             BIGINT NOT NULL,
    tipo                VARCHAR(30),
    descricao           VARCHAR(255),
    codigo              VARCHAR(40),
    percentual_desconto NUMERIC(5,2),
    inicio              TIMESTAMPTZ,
    fim                 TIMESTAMPTZ,
    limite_usos         INTEGER,
    usos                INTEGER NOT NULL DEFAULT 0,
    ativa               BOOLEAN NOT NULL DEFAULT true,
    origem              VARCHAR(20),
    criado_em           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_promocao_loja_ativa ON promocao(loja_id, ativa);
