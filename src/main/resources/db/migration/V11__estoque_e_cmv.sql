-- Ficha técnica / estoque / CMV
ALTER TABLE produto ADD COLUMN IF NOT EXISTS custo           NUMERIC(12,2);
ALTER TABLE produto ADD COLUMN IF NOT EXISTS estoque         INTEGER;       -- NULL = não controla estoque
ALTER TABLE produto ADD COLUMN IF NOT EXISTS estoque_minimo  INTEGER;

ALTER TABLE pedido_item ADD COLUMN IF NOT EXISTS custo_unitario NUMERIC(12,2);
