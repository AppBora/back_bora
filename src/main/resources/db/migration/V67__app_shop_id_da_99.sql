-- 99Food (Open Delivery): gravar o APPShopID no merchant_id das lojas da Zira.
--
-- A 99 documenta, em Open Delivery -> Authorization API -> Get Access Token:
--   "ClientId = AppID_AppShopId"  e  "this token is unique for every shop not for application".
-- O nosso OpenDeliveryClient monta {app_id}_{merchant_id}, e o merchant_id guardava o "ID do loja"
-- (numerico). Mandavamos 5764607707103889279_5764615393256407102 quando o certo era
-- 5764607707103889279_zira-acaiteria. Era esse o 403 que atribuimos por dois meses a um bloqueio
-- da 99 -- o defeito era nosso.
--
-- POR QUE UMA COLUNA NOVA, E NAO SO TROCAR O VALOR:
-- o mesmo merchant_id roteia o webhook de entrada, que a 99 identifica pelo header
-- X-App-MerchantId. A documentacao NAO diz qual dos dois identificadores ela manda nesse header.
-- Trocar o valor e torcer significaria, se eu errasse, webhook caindo em 404 e PEDIDO REAL
-- PERDIDO. Entao guardamos os dois: merchant_id passa a ser o APPShopID (o que a API exige) e
-- shop_id_canal guarda o ID do loja (o outro candidato do header). O webhook casa por qualquer um.

ALTER TABLE integracao_canal ADD COLUMN IF NOT EXISTS shop_id_canal VARCHAR(80);

COMMENT ON COLUMN integracao_canal.shop_id_canal IS
  'Identificador alternativo da loja no canal (no 99Food, o "ID do loja" numerico). O merchant_id guarda o APPShopID, que e o que a API exige no client_id; os dois sao aceitos ao rotear o webhook.';

-- Backfill das 5 lojas da Zira. Casado pelo valor atual: se alguem ja tiver corrigido a linha,
-- o UPDATE nao encontra nada e nao faz estrago. Rodar de novo tambem e inofensivo.
UPDATE integracao_canal SET shop_id_canal = '5764615393256407102', merchant_id = 'zira-acaiteria'
 WHERE canal = 'NOVE_NOVE' AND merchant_id = '5764615393256407102';

UPDATE integracao_canal SET shop_id_canal = '5764608427259856166', merchant_id = 'zira-zona-norte'
 WHERE canal = 'NOVE_NOVE' AND merchant_id = '5764608427259856166';

UPDATE integracao_canal SET shop_id_canal = '5764614166724478604', merchant_id = 'zira-sao-bento'
 WHERE canal = 'NOVE_NOVE' AND merchant_id = '5764614166724478604';

UPDATE integracao_canal SET shop_id_canal = '5764608491428513041', merchant_id = 'zira-vila-hortencia'
 WHERE canal = 'NOVE_NOVE' AND merchant_id = '5764608491428513041';

UPDATE integracao_canal SET shop_id_canal = '5764608368417965364', merchant_id = 'zira-nova-esperanca'
 WHERE canal = 'NOVE_NOVE' AND merchant_id = '5764608368417965364';

-- O token guardado foi emitido com o client_id errado: nao serve para nada e, pior, faria o poller
-- esperar o vencimento antes de pedir outro. Zerar forca a renovacao no proximo ciclo.
UPDATE integracao_canal
   SET access_token = NULL, token_expira_em = NULL, ultimo_erro = NULL
 WHERE canal = 'NOVE_NOVE' AND shop_id_canal IS NOT NULL;

-- O webhook passa a buscar pelos dois campos.
CREATE INDEX IF NOT EXISTS idx_integracao_canal_shop_id ON integracao_canal(canal, shop_id_canal);
