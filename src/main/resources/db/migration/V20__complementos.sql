-- Complementos/adicionais por produto (tamanho, borda, extras) — essencial p/ pizzaria e burger.
CREATE TABLE complemento_grupo (
    id         BIGSERIAL PRIMARY KEY,
    loja_id    BIGINT NOT NULL REFERENCES loja (id),
    produto_id BIGINT NOT NULL REFERENCES produto (id),
    nome       VARCHAR(80) NOT NULL,
    minimo     INT NOT NULL DEFAULT 0,
    maximo     INT NOT NULL DEFAULT 1
);
CREATE TABLE complemento_item (
    id       BIGSERIAL PRIMARY KEY,
    loja_id  BIGINT NOT NULL REFERENCES loja (id),
    grupo_id BIGINT NOT NULL REFERENCES complemento_grupo (id) ON DELETE CASCADE,
    nome     VARCHAR(80) NOT NULL,
    preco    NUMERIC(12,2) NOT NULL DEFAULT 0
);
CREATE INDEX ix_comp_grupo_produto ON complemento_grupo (produto_id);
CREATE INDEX ix_comp_item_grupo ON complemento_item (grupo_id);
