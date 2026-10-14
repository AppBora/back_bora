-- As colunas de credencial de terceiros passam a caber CIFRADAS.
--
-- A V49 vem junto com o SegredoGuardado (AES-256/GCM nas credenciais de Asaas e marketplace). Valor
-- cifrado e mais longo que o original: o formato gravado e `enc:v1:` + base64(nonce 12B || cifra ||
-- tag 16B), ou seja, cerca de 7 + ceil((N+28)/3)*4 caracteres para um valor de N.
--
-- Medido contra as larguras de hoje, ANTES de ligar a cifra:
--   access_token   varchar(2000) -> um token de 2000 do iFood cifrado da ~2711.  ESTOURAVA
--   refresh_token  varchar(2000) -> idem.                                        ESTOURAVA
--   webhook_token  varchar(80)   -> 32 hex cifrados dao ~87.                     ESTOURAVA
--   asaas_api_key  varchar(255)  -> chave do Asaas (~160) cifrada da ~259.       ESTOURAVA
--   client_secret  varchar(400)  -> secret de 300 cifrado da ~447.               ESTOURAVA
--
-- Sem isto, ligar a cifra fazia a renovacao do token do iFood falhar na gravacao -- e o comentario do
-- MarketplaceHttp avisa que o iFood invalida o refresh token anterior, entao a loja precisaria
-- reautorizar do zero. Falha caladona, do pior tipo.
--
-- `alter column ... type text` em varchar NAO reescreve a tabela no PostgreSQL: e mudanca de catalogo,
-- instantanea. E `text` em vez de um varchar maior porque o limite nunca protegeu nada aqui: estes
-- valores vem de terceiro e o tamanho e deles.

set lock_timeout = '5s';

alter table integracao_canal alter column access_token  type text;
alter table integracao_canal alter column refresh_token type text;
alter table integracao_canal alter column client_secret type text;
alter table integracao_canal alter column webhook_token type text;
alter table integracao_canal alter column code_verifier type text;
alter table loja             alter column asaas_api_key type text;

comment on column loja.asaas_api_key is
  'Credencial do LOJISTA. Cifrada em repouso quando BORA_SEGREDO_COLUNAS existe (prefixo enc:v1:). Ver SegredoGuardado.';
comment on column integracao_canal.access_token is
  'Cifrada em repouso quando BORA_SEGREDO_COLUNAS existe. `text` porque o valor cifrado e ~35% maior que o original.';
comment on column integracao_canal.refresh_token is
  'Idem access_token. Perder a chave de cifra obriga a loja a reautorizar o marketplace do zero.';
