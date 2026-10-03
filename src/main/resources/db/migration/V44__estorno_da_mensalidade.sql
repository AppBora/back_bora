-- O evento de estorno do Asaas caia no "default: ignorado". Depois de devolver o dinheiro pela
-- garantia de 7 dias, a linha continuava em pagamento_assinatura: o faturamento do mes somava o valor
-- devolvido e a mensalidade aparecia na lista de "falta emitir nota". Daria para emitir nota fiscal
-- de um dinheiro que voltou para o cliente.
ALTER TABLE pagamento_assinatura ADD COLUMN IF NOT EXISTS estornado_em timestamptz;

COMMENT ON COLUMN pagamento_assinatura.estornado_em IS 'Quando o valor foi devolvido ao cliente. NULL = dinheiro ficou.';
