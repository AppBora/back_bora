-- Operação Assistida: telefone do cliente no pedido p/ notificações de fase no WhatsApp.
ALTER TABLE pedido ADD COLUMN cliente_telefone VARCHAR(20);
