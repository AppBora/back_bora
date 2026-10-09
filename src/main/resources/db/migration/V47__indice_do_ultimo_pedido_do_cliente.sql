-- O CRM pergunta "qual foi o ultimo pedido de cada cliente desta loja?". Os indices de pedido eram
-- (loja_id, criado_em), (loja_id, entregador, acerto_id) e (loja_id, canal_externo, id_externo):
-- nenhum com cliente_id. O Postgres percorria os pedidos da loja do mais novo para o mais antigo e
-- filtrava cliente a cliente, e o pior caso era justamente o cliente sumido — que e quem o CRM quer
-- alcancar.
--
-- id desc em vez de criado_em desc porque o id e sequencial e nao empata.
create index if not exists idx_pedido_loja_cliente on pedido (loja_id, cliente_id, id desc);

-- A V46 criou a coluna dizendo que ela guardava os ids separados por virgula. Passou a guardar o
-- nome e o preco junto (ver ComplementosDoItem): o id sozinho nao sobrevive, porque salvar o
-- cardapio apaga e recria os complementos com ids novos. Nenhuma linha tinha sido gravada ainda,
-- entao nao ha dado a converter.
comment on column pedido_item.complementos is
    'JSON com os complementos escolhidos [{id,nome,preco}]. Nulo = item anterior ao registro; [] = sem complemento.';
