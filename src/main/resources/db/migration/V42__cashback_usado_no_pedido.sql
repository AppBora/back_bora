-- O cashback gasto num pedido PIX nunca era debitado do saldo.
--
-- No cardapio o desconto saia do total na hora, mas quem registrava o consumo era o webhook do
-- pagamento -- e ele passava resgate ZERO, porque o valor usado nao era guardado em lugar nenhum
-- (so num texto dentro da observacao). O cliente gastava os mesmos R$ 20 em quantos pedidos quisesse,
-- e ainda ganhava cashback novo sobre o valor ja descontado. Quem pagava a diferenca era o lojista.
ALTER TABLE pedido ADD COLUMN IF NOT EXISTS cashback_usado numeric(12,2);

COMMENT ON COLUMN pedido.cashback_usado IS 'Quanto de cashback o cliente abateu neste pedido. NULL/0 = nenhum.';
