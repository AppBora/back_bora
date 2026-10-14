-- Duas lacunas que doem na operacao, as duas aditivas e nullable (instantaneo no PG16).
--
-- 1) ENDERECO, BAIRRO E TIPO DE ENTREGA
--    O pedido do cardapio nao guardava nada disso. O endereco que o cliente digitava ia para dentro do
--    TEXTO da observacao ("Cliente: x | Tel: y | End: ..."), e o card do painel mostrava
--    `cliente.endereco` -- que, no cliente recorrente, e o endereco ANTIGO dele. Entregar no lugar
--    errado era questao de o cliente ter se mudado.
--    O bairro escolhido no checkout tambem nao era gravado em lugar nenhum, entao "entregas agrupadas
--    por bairro" jogava todo pedido do canal proprio em "Sem bairro".
--    E retirada no balcao existia apenas como o texto "RETIRADA NO BALCAO" dentro da observacao: a tela
--    de Entregas nao distinguia, e oferecia "Despachar" para quem ia buscar na loja.
--
-- 2) ACEITE JA ENVIADO AO MARKETPLACE
--    O aceite automatico era enviado e, se falhasse, o IfoodClient engolia a excecao com um log.warn.
--    O evento era confirmado ao iFood de qualquer jeito e o ciclo seguinte via "ja importado" e saia
--    sem tentar de novo: o pedido ficava no nosso painel e o iFood o cancelava sozinho por falta de
--    aceite -- depois de a cozinha ter preparado.
--    Nao da para deduzir isso do status: o pedido continua RECEBIDO aqui ate alguem clicar, aceito ou
--    nao. Sem a marca, retentar reconfirmaria -- que foi o "confirm em dobro" visto no primeiro pedido
--    real de iFood, em 19/09. Por isso a coluna.

set lock_timeout = '5s';

alter table pedido add column if not exists endereco_entrega varchar(255);
alter table pedido add column if not exists bairro_entrega   varchar(100);
alter table pedido add column if not exists tipo_entrega      varchar(20);
alter table pedido add column if not exists aceite_enviado_em timestamptz;

comment on column pedido.endereco_entrega is
  'Endereco informado NESTE pedido. Nao usar cliente.endereco para entregar: no cliente recorrente ele e o endereco antigo.';
comment on column pedido.bairro_entrega is
  'Bairro escolhido NESTE pedido, que define o frete. Base do agrupamento por bairro na tela de Entregas.';
comment on column pedido.tipo_entrega is
  'ENTREGA ou RETIRADA. Nulo = pedido anterior a V48; a tela cai na deducao antiga pelo texto da observacao.';
comment on column pedido.aceite_enviado_em is
  'Quando o aceite automatico foi aceito pelo marketplace. Nulo com autoAceitar ligado = ainda ha aceite a reenviar.';

-- Pedido antigo de retirada: a marca estava no texto da observacao e e recuperavel com seguranca.
-- O resto fica nulo de proposito -- deduzir endereco do texto livre e como inventar dado de entrega.
update pedido
   set tipo_entrega = 'RETIRADA'
 where tipo_entrega is null
   and observacao is not null
   and upper(observacao) like '%RETIRADA NO BALC%';

-- O agrupamento por bairro da tela de Entregas passa a ler a coluna do pedido.
create index if not exists idx_pedido_bairro_entrega
    on pedido (loja_id, bairro_entrega)
 where bairro_entrega is not null;
