CREATE TABLE usuario (
    id          BIGSERIAL PRIMARY KEY,
    loja_id     BIGINT REFERENCES loja(id),
    nome        VARCHAR(120) NOT NULL,
    email       VARCHAR(180) NOT NULL UNIQUE,
    senha_hash  VARCHAR(100) NOT NULL,
    papel       VARCHAR(40)  NOT NULL DEFAULT 'OPERADOR',
    ativo       BOOLEAN      NOT NULL DEFAULT true,
    criado_em   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_usuario_loja ON usuario(loja_id);
