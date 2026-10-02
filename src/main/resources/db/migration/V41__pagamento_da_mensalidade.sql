-- Nao existia registro nenhum de mensalidade recebida. O webhook do Asaas chegava, trocava o status da
-- assinatura para ATIVA e jogava fora o resto: o identificador da cobranca, o valor e a data do
-- pagamento. Resultado: para saber o que faturamos no mes era preciso entrar no painel do Asaas, e nao
-- havia onde registrar a nota fiscal de cada mensalidade.
--
-- asaas_payment_id e UNICO de proposito: o Asaas reenvia o mesmo evento quando nao recebe 200, e sem
-- isso a mesma mensalidade entraria duas vezes no faturamento do mes.
CREATE TABLE IF NOT EXISTS pagamento_assinatura (
    id               bigserial PRIMARY KEY,
    loja_id          bigint NOT NULL,
    assinatura_id    bigint,
    asaas_payment_id varchar(60) NOT NULL UNIQUE,
    valor            numeric(12,2) NOT NULL,
    pago_em          timestamptz NOT NULL,
    descricao        varchar(200),
    nota_numero      varchar(40),
    nota_url         varchar(300),
    nota_emitida_em  timestamptz,
    criado_em        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_pagamento_assinatura_pago_em ON pagamento_assinatura (pago_em);
CREATE INDEX IF NOT EXISTS ix_pagamento_assinatura_loja    ON pagamento_assinatura (loja_id);

COMMENT ON TABLE  pagamento_assinatura            IS 'Mensalidades do BoraHapp efetivamente recebidas, uma linha por cobranca paga.';
COMMENT ON COLUMN pagamento_assinatura.asaas_payment_id IS 'Id da cobranca no Asaas. UNICO: o webhook e reenviado e nao pode contar duas vezes.';
COMMENT ON COLUMN pagamento_assinatura.nota_numero      IS 'Numero da NFS-e emitida para esta mensalidade. NULL = ainda sem nota.';
