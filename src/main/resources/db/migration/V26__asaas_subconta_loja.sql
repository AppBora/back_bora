-- Recebimento do PIX do cliente direto na conta do lojista, via subconta Asaas (white-label).
-- A plataforma cria a subconta por API no provisionamento; o dinheiro do pedido cai na subconta
-- do lojista e a taxa da plataforma é retida por split. Campos guardados na própria loja.
ALTER TABLE loja ADD COLUMN IF NOT EXISTS asaas_subconta_id     VARCHAR(255);
ALTER TABLE loja ADD COLUMN IF NOT EXISTS asaas_wallet_id       VARCHAR(255);
ALTER TABLE loja ADD COLUMN IF NOT EXISTS asaas_api_key         VARCHAR(255);
ALTER TABLE loja ADD COLUMN IF NOT EXISTS asaas_status          VARCHAR(40);
ALTER TABLE loja ADD COLUMN IF NOT EXISTS asaas_onboarding_url  VARCHAR(500);
-- Token do webhook criado na subconta: e o que autentica a confirmacao de pagamento do PIX.
ALTER TABLE loja ADD COLUMN IF NOT EXISTS asaas_webhook_token   VARCHAR(255);

-- Analise por horario/tempos varre log_status por loja + data_hora; o indice existente so cobre (loja_id, pedido_id).
CREATE INDEX IF NOT EXISTS idx_log_status_loja_data ON log_status(loja_id, data_hora);
