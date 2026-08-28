-- Integracao OFICIAL com marketplaces (iFood e Open Delivery / 99Food).
--
-- Ate aqui a conexao guardava apenas credenciais coladas pelo lojista e um webhook de entrada.
-- No modelo real, a plataforma e a integradora: o app (clientId/clientSecret) e NOSSO e fica em
-- variavel de ambiente; por loja guardamos o merchantId e os tokens que aquele lojista autorizou.

-- O status ganhou AGUARDANDO_AUTORIZACAO (22 chars) e nao cabia mais em VARCHAR(20).
ALTER TABLE integracao_canal ALTER COLUMN status TYPE VARCHAR(40);

-- Tokens OAuth por loja+canal.
ALTER TABLE integracao_canal ADD COLUMN IF NOT EXISTS access_token       VARCHAR(2000);
ALTER TABLE integracao_canal ADD COLUMN IF NOT EXISTS refresh_token      VARCHAR(2000);
ALTER TABLE integracao_canal ADD COLUMN IF NOT EXISTS token_expira_em    TIMESTAMPTZ;

-- Fluxo de aplicativos distribuidos do iFood: pedimos um userCode, o lojista digita no portal
-- do parceiro e so entao trocamos por token. O verifier precisa sobreviver entre as duas etapas.
ALTER TABLE integracao_canal ADD COLUMN IF NOT EXISTS user_code          VARCHAR(80);
ALTER TABLE integracao_canal ADD COLUMN IF NOT EXISTS code_verifier      VARCHAR(400);
ALTER TABLE integracao_canal ADD COLUMN IF NOT EXISTS verification_url   VARCHAR(500);
ALTER TABLE integracao_canal ADD COLUMN IF NOT EXISTS vinculo_expira_em  TIMESTAMPTZ;

-- Diagnostico do polling: a loja so fica "online" no iFood enquanto consultamos a cada 30s,
-- entao precisamos enxergar quando a ultima consulta rodou e qual foi o ultimo erro.
ALTER TABLE integracao_canal ADD COLUMN IF NOT EXISTS ultimo_polling_em  TIMESTAMPTZ;
ALTER TABLE integracao_canal ADD COLUMN IF NOT EXISTS ultimo_erro        VARCHAR(500);

-- Evita pedido duplicado quando o mesmo evento chega pelo polling mais de uma vez.
CREATE UNIQUE INDEX IF NOT EXISTS uq_pedido_canal_externo
    ON pedido(loja_id, canal_externo, id_externo)
    WHERE canal_externo IS NOT NULL AND id_externo IS NOT NULL;
