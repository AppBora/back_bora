-- O produto passa a guardar o codigo que ele tem no iFood e na 99.
--
-- POR QUE: pedido de marketplace nao baixava estoque NENHUM -- nem produto, nem insumo -- e o custo
-- do item nascia vazio, entao CMV e margem ignoravam tudo que vinha de iFood e 99. Numa loja em que
-- o marketplace e o canal que mais vende, isso e o estoque do painel mentindo o dia inteiro e o
-- relatorio de lucro contando metade das vendas.
--
-- A causa raiz era nao ter como saber QUAL produto da loja foi vendido: o `InboundItem` so trazia o
-- nome montado para a tela ("Acai 500ml (+ Granola) -- obs: sem cebola"), que nao casa com
-- `produto.nome` em nenhum item com complemento.
--
-- O gancho ja vinha no payload e era jogado fora. Conferido nos arquivos de pedido real do projeto:
--   iFood (pedido-real-loja-teste.json)  -> "externalCode": "7470", "5307"
--   99 / Open Delivery (pedido-entrega)  -> "externalCode": "PIZZA-CAL", "BORDA-CAT", "COCA600"
-- E o codigo que o PROPRIO lojista cadastrou la, do lado de la. Guardando o mesmo valor aqui, o
-- pedido externo passa a achar o produto.
--
-- A coluna e OPCIONAL. Quem nao preencher continua como hoje para esse produto, e o sistema cai no
-- casamento por nome limpo antes de desistir -- ver PedidoService.resolverProduto.

set lock_timeout = '5s';

alter table produto add column if not exists codigo_externo varchar(60);

comment on column produto.codigo_externo is
  'Codigo deste produto no iFood/99 (o "externalCode" do payload). Opcional. Serve para o pedido de '
  'marketplace achar o produto e baixar estoque. Ver PedidoService.resolverProduto.';

-- Unico POR LOJA, e so quando preenchido: duas lojas podem usar o mesmo codigo, e a maioria dos
-- produtos vai ficar sem nenhum. Indice parcial para nao carregar as linhas vazias.
create unique index if not exists ux_produto_codigo_externo
  on produto (loja_id, codigo_externo)
  where codigo_externo is not null;
