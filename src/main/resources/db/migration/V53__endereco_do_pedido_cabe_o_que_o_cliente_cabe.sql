-- O endereco do pedido passa a caber o mesmo que o endereco do cliente.
--
-- POR QUE: o MESMO valor que vem do marketplace ia para dois lugares com tamanhos diferentes.
-- `PedidoService.upsertCliente` cortava em 500, porque `cliente.endereco` e varchar(500) desde a V37
-- -- largada justamente por causa de endereco de marketplace que nao caberia em 255. Mas
-- `criarExterno` gravava o mesmo texto, SEM corte, em `pedido.endereco_entrega`, que nasceu
-- varchar(255) na V48.
--
-- O resultado: endereco com mais de 255 caracteres faz o Postgres recusar o INSERT
-- ("value too long for type character varying(255)") e O PEDIDO INTEIRO SE PERDE. Nao e um campo
-- truncado: e a venda que nao entra no painel, com o entregador do iFood a caminho.
--
-- O `ddl-auto: validate` do Hibernate NAO pega isso: ele confere se a coluna existe e o tipo, nao a
-- largura. Por isso existe tambem um teste que compara a largura real do banco com o numero que o
-- codigo usa para cortar (ver LarguraDeColuna e LarguraDasColunasBateComOBancoIT).
--
-- O `id_externo` sobe para 160 pelo mesmo motivo de simetria: `cliente.id_externo` ja e 160, e o id
-- do pedido vinha de uma coluna de 120. Nenhum marketplace manda id tao longo (os do iFood sao UUID
-- de 36), mas a assimetria era gratuita.
--
-- MEDIDO NA PRODUCAO em 21/10/2026, antes de mexer: NENHUM pedido tem endereco_entrega preenchido, e
-- o maior endereco de cliente da base tem 43 caracteres. Ou seja: o defeito nunca disparou. Isto e
-- preventivo, e chega antes do volume de marketplace.

set lock_timeout = '5s';

alter table pedido alter column endereco_entrega type varchar(500);
alter table pedido alter column id_externo type varchar(160);

comment on column pedido.endereco_entrega is
  'Endereco informado NESTE pedido. varchar(500) para caber o mesmo que cliente.endereco: o valor do '
  'marketplace vai para os dois, e a diferenca de tamanho perdia o pedido inteiro. Ver V53.';
