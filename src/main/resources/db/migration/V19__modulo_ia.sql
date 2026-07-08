-- Módulo IA (add-on PAGO, liberado loja a loja pelo ADMINISTRADOR_BORA):
-- Recuperador de clientes, Migração de cardápio por foto e Gerente Virtual.
ALTER TABLE loja ADD COLUMN modulo_ia BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE loja ADD COLUMN whatsapp_dono VARCHAR(20);

-- Registro dos disparos de recuperação (mede o ROI: pedido feito até 7 dias após o contato).
CREATE TABLE ia_recuperacao (
    id          BIGSERIAL PRIMARY KEY,
    loja_id     BIGINT NOT NULL REFERENCES loja (id),
    cliente_id  BIGINT NOT NULL,
    telefone    VARCHAR(20),
    enviado_em  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_ia_recuperacao_loja ON ia_recuperacao (loja_id, enviado_em);
