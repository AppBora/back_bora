-- Os indices que o painel precisa para nao varrer a loja inteira a cada atualizacao.
--
-- POR QUE: as telas do painel recarregam sozinhas a cada 6 a 15 segundos, e tres consultas do laco
-- nao tinham indice que as servisse. Cada atualizacao varria TODOS os pedidos da loja -- por loja
-- aberta, por aba aberta, o dia inteiro.
--
-- MEDIDO POR MIM em 21/10/2026, num Postgres 16 com 26 mil pedidos numa loja e distribuicao realista
-- (quase tudo ja encerrado, um punhado ativo agora):
--
--   consulta                              quem chama          antes      depois
--   ------------------------------------  ------------------  ---------  ---------
--   contagem "em preparo"                 Dashboard, 8 s      8,6 ms     0,40 ms
--   contagem "saiu para entrega"          Dashboard, 8 s      9,9 ms     0,27 ms
--   contagem "entregues na ultima hora"   Dashboard, 8 s      8,0 ms     0,32 ms
--   os pedidos ABERTOS de qualquer dia    KDS, 6 s            9,3 ms     0,35 ms
--
-- As tres do Dashboard viram Index Only Scan: nao tocam mais na tabela. O total por atualizacao do
-- Dashboard cai de 26,5 ms para 1,0 ms.
--
-- Na PRODUCAO de hoje a tabela tem 32 linhas e 160 kB: o ganho e ZERO agora, porque varrer 32 linhas
-- e mais rapido que usar indice. Isto e preventivo -- uma loja que faca 100 pedidos por dia chega aos
-- 26 mil em menos de um ano.
--
-- CUSTO DE ESPACO, medido: 200 kB + 416 kB numa tabela de 14 MB.
--
-- O indice de `entregue_em` e PARCIAL porque a maioria dos pedidos nunca e entregue (fica em
-- CANCELADO, ou ainda esta aberto): indexar as linhas nulas seria carregar peso que a consulta nunca
-- le, ja que `entregue_em > corte` exclui nulo de qualquer forma.
--
-- NAO ha indice parcial por status, e isso foi uma decisao MEDIDA. Um
-- `where status not in ('ENTREGUE','CANCELADO')` servia a consulta do KDS em 0,23 ms, mas so quando o
-- Postgres pode olhar os valores dos parametros: com plano GENERICO forcado ele nao consegue provar
-- que `status not in ($1,$2)` implica o predicado do indice, e cai para 15,9 ms. Em vez de depender
-- dessa heuristica, a consulta do KDS passou a usar lista POSITIVA (`status in (abertos)`), que o
-- indice comum abaixo serve sempre -- conferido em 6 execucoes seguidas, inclusive depois do limite em
-- que o Postgres troca para plano generico.
--
-- `lock_timeout` porque CREATE INDEX trava a tabela contra escrita. Com 32 linhas e instantaneo; com
-- 26 mil seriam poucas dezenas de milissegundos. Se um dia a tabela for grande e a loja estiver
-- vendendo, o certo e CREATE INDEX CONCURRENTLY -- que nao roda dentro de transacao e por isso nao
-- cabe numa migracao do Flyway como esta.

set lock_timeout = '5s';

-- Serve as duas contagens por status do Dashboard (Index Only Scan) e a lista de abertos do KDS.
create index if not exists idx_pedido_loja_status on pedido (loja_id, status);

-- Serve a contagem "entregues na ultima hora".
create index if not exists idx_pedido_entregue_em on pedido (loja_id, entregue_em)
  where entregue_em is not null;
