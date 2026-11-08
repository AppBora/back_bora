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

-- =====================================================================================
-- O QUE EU TINHA ESQUECIDO, E ERA O PRINCIPAL (medido na producao em 08/11/2026)
--
-- Trocar o merchant_id NAO basta. O OpenDeliveryClient.clientIdDaLoja decide assim:
--
--   if (!credencialDaLoja(i)) return appId() + "_" + i.merchantId;   // usa o merchant_id
--   String proprio = i.clientId.trim();
--   if (proprio.contains("_")) return proprio;                       // USA O CAMPO CRU
--
-- Ou seja: loja com client_id/client_secret PROPRIOS e com "_" no client_id tem o campo mandado
-- cru, e o merchant_id nunca e consultado. Medido no banco: a loja 18 (Montreal) -- a unica
-- marcada CONECTADO -- esta exatamente nesse caso, com
-- client_id = '5764607707103889279_5764615393256407102' (App ID + ID do loja).
--
-- Sem a linha abaixo, a loja 18 continuaria mandando o identificador errado e levando 403 depois
-- da migracao, e eu teria anunciado conserto medindo a tabela em vez da chamada.
--
-- A condicao e exata: so reescreve quando o client_id TERMINA em "_" + o ID do loja que acabamos
-- de guardar em shop_id_canal, e troca so esse final pelo APPShopID. Roda depois dos UPDATEs
-- acima, que e onde shop_id_canal e merchant_id ganham os valores certos. Rodar de novo nao faz
-- nada: o final ja nao casa mais.
UPDATE integracao_canal
   SET client_id = left(client_id, length(client_id) - length(shop_id_canal)) || merchant_id
 WHERE canal = 'NOVE_NOVE'
   AND shop_id_canal IS NOT NULL
   AND merchant_id IS NOT NULL
   AND client_id IS NOT NULL
   AND right(client_id, length(shop_id_canal) + 1) = '_' || shop_id_canal;
