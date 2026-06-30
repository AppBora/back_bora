CREATE TABLE pedido_item (
    id             BIGSERIAL PRIMARY KEY,
    loja_id        BIGINT NOT NULL,
    pedido_id      BIGINT NOT NULL,
    produto_id     BIGINT,
    descricao      VARCHAR(160),
    quantidade     INTEGER NOT NULL DEFAULT 1,
    preco_unitario NUMERIC(12,2),
    subtotal       NUMERIC(12,2)
);

CREATE INDEX idx_pedido_item_pedido ON pedido_item(loja_id, pedido_id);
