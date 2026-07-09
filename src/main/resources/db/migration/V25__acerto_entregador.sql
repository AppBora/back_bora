-- Acerto de entregadores: fechamento financeiro por período (taxas de entrega a pagar ao motoboy).
CREATE TABLE acerto_entregador (
  id             BIGSERIAL PRIMARY KEY,
  loja_id        BIGINT       NOT NULL,
  entregador     VARCHAR(120) NOT NULL,
  periodo_inicio DATE         NOT NULL,
  periodo_fim    DATE         NOT NULL,
  qtde_entregas  INT          NOT NULL DEFAULT 0,
  valor_taxas    NUMERIC(12,2) NOT NULL DEFAULT 0,
  valor_dinheiro NUMERIC(12,2) NOT NULL DEFAULT 0,
  valor_outras   NUMERIC(12,2) NOT NULL DEFAULT 0,
  valor_total    NUMERIC(12,2) NOT NULL DEFAULT 0,
  valor_a_pagar  NUMERIC(12,2) NOT NULL DEFAULT 0,
  valor_pago     NUMERIC(12,2) NOT NULL DEFAULT 0,
  descontos      NUMERIC(12,2) NOT NULL DEFAULT 0,
  saldo          NUMERIC(12,2) NOT NULL DEFAULT 0,
  observacao     TEXT,
  criado_em      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  criado_por     BIGINT
);
CREATE INDEX idx_acerto_entregador_loja ON acerto_entregador(loja_id, periodo_fim);

-- Marca em qual acerto cada pedido já entrou — impede pagar 2x a mesma entrega.
ALTER TABLE pedido ADD COLUMN acerto_id BIGINT;
CREATE INDEX idx_pedido_acerto ON pedido(loja_id, entregador, acerto_id);
