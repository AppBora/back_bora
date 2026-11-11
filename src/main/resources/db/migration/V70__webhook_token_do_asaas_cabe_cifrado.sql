-- A coluna `loja.asaas_webhook_token` passou a ser cifrada em repouso (11/11/2026), e cifrado o
-- valor cresce ~35% mais 19 bytes de nonce e tag, em base64: um token de 36 caracteres vira 95.
--
-- HOJE ELE CABERIA: a coluna é varchar(255) e 95 < 255. Esta migração existe porque "cabe hoje" não
-- é garantia nenhuma, e porque o modo de falhar aqui é ruim:
--
--   O Postgres NÃO CORTA valor maior que o limite da coluna — ele RECUSA o comando, e a recusa
--   desfaz a TRANSAÇÃO INTEIRA. Quem gravaria este token é o `AsaasSubcontaService.criarWebhookPix`,
--   dentro da mesma transação que provisiona a subconta do lojista. Um token mais longo vindo do
--   Asaas (o valor é gerado por nós hoje, mas a coluna é o destino de um dado que sai daqui e volta)
--   derrubaria o provisionamento inteiro, não só o aviso de pagamento.
--
-- As 6 colunas irmãs já são `text` desde a V49 e a V63, pelo mesmo motivo. Esta era a última que
-- tinha ficado atrás — e também a última que estava em TEXTO PURO no banco. Ver SegredoGuardado.
alter table loja alter column asaas_webhook_token type text;

comment on column loja.asaas_webhook_token is
  'Token que autentica o webhook de PIX na SUBCONTA do lojista. Cifrado em repouso quando BORA_SEGREDO_COLUNAS existe (prefixo enc:v1:). `text` porque o valor cifrado e ~35% maior. Ver SegredoGuardado.';
