-- Cashback: percentual configuravel por loja e valendo tambem no pedido do cardapio online.
--
-- Ate aqui o cashback era 5% cravado no codigo e so acumulava em pedido lancado no painel com
-- cliente selecionado. Como o cardapio online sempre tem telefone, ligar o cashback la aumenta
-- muito o volume — entao o lojista precisa poder ajustar (ou zerar) o percentual.
ALTER TABLE configuracao_loja ADD COLUMN IF NOT EXISTS cashback_percentual NUMERIC(5,2);

-- Mantem o comportamento atual para quem ja usa: 5% continua sendo o padrao.
UPDATE configuracao_loja SET cashback_percentual = 5 WHERE cashback_percentual IS NULL;

-- O cardapio online identifica o cliente pelo telefone; sem indice isso vira varredura por loja.
CREATE INDEX IF NOT EXISTS idx_cliente_loja_telefone ON cliente(loja_id, telefone);
