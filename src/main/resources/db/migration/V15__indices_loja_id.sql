-- Índices na coluna de tenant (loja_id) — evita full scan conforme as lojas crescem.
create index if not exists idx_cliente_loja       on cliente(loja_id);
create index if not exists idx_produto_loja       on produto(loja_id);
create index if not exists idx_configuracao_loja  on configuracao_loja(loja_id);
create index if not exists idx_pedido_loja        on pedido(loja_id, criado_em desc);
create index if not exists idx_pedido_item_loja   on pedido_item(loja_id, pedido_id);
create index if not exists idx_log_status_loja    on log_status(loja_id, pedido_id);
