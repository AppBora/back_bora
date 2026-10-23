-- Item 5.8 da varredura: a taxa de entrega do marketplace entra no faturamento da loja mesmo
-- quando quem fica com ela e o marketplace.
--
-- O VALOR da taxa ja estava gravado (pedido.taxa_entrega). O que faltava era QUEM ficou com ela.
-- O payload do iFood diz (delivery.deliveredBy = IFOOD | MERCHANT) e o normalizador ja lia isso --
-- mas so para escrever "Entrega pelo iFood" na observacao, que e texto e nenhuma consulta alcanca.
--
-- Nulo de proposito: pedido do proprio balcao e pedido antigo nao tem essa informacao, e inventar
-- `false` para eles diria "a loja ficou com a taxa" sobre pedido que ninguem mediu.
alter table pedido add column if not exists entrega_pelo_canal boolean;

comment on column pedido.entrega_pelo_canal is
  'Quem entregou: true = o marketplace (e ficou com a taxa de entrega), false = a loja, '
  'null = nao se aplica ou nao informado (balcao, cardapio, pedido antes da V58). '
  'Item 5.8 da varredura. NAO e usado para calcular faturamento: isso e decisao do dono.';
