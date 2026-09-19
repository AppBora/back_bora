-- Cliente vindo de marketplace passa a ser identificado pelo id que o marketplace da a ele.
-- O telefone do pedido do iFood e a central do iFood (0800 + localizador), igual para todos: casar por
-- telefone juntava TODOS os clientes do iFood de uma loja num cadastro so (compras e cashback somados).
ALTER TABLE cliente ADD COLUMN IF NOT EXISTS canal_externo VARCHAR(40);
ALTER TABLE cliente ADD COLUMN IF NOT EXISTS id_externo VARCHAR(160);
CREATE INDEX IF NOT EXISTS idx_cliente_externo ON cliente (loja_id, canal_externo, id_externo);

-- O telefone do pedido do iFood agora leva o codigo do localizador ("0800 705 1020 · código 12345678"),
-- que o entregador precisa para falar com o cliente pela central. Nao cabia em 20 caracteres.
ALTER TABLE pedido ALTER COLUMN cliente_telefone TYPE VARCHAR(60);
