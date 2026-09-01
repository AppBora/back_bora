-- Empresa: o fato societario (quem e a dona), separado de usuario_loja (quem tem acesso).
-- Sem isso nao ha fonte de verdade para "estas lojas sao da mesma dona", e o endpoint de vincular
-- usuario a outra loja nao teria como recusar um vinculo para a loja de OUTRO cliente.
CREATE TABLE IF NOT EXISTS empresa (
    id           BIGSERIAL PRIMARY KEY,
    razao_social VARCHAR(160) NOT NULL,
    cnpj         VARCHAR(20),
    criado_em    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- CNPJ unico quando informado; loja sem documento nao impede o cadastro.
CREATE UNIQUE INDEX IF NOT EXISTS uq_empresa_cnpj ON empresa (cnpj) WHERE cnpj IS NOT NULL;

ALTER TABLE loja ADD COLUMN IF NOT EXISTS empresa_id BIGINT REFERENCES empresa (id);

-- Backfill 1: um CNPJ distinto = uma empresa, agrupando as lojas que ja o compartilham.
INSERT INTO empresa (razao_social, cnpj)
SELECT MIN(nome), documento
  FROM loja
 WHERE documento IS NOT NULL AND btrim(documento) <> ''
 GROUP BY documento
ON CONFLICT DO NOTHING;

UPDATE loja l SET empresa_id = e.id
  FROM empresa e
 WHERE e.cnpj = l.documento AND l.empresa_id IS NULL;

-- Backfill 2: loja sem documento ganha empresa propria (nao da para adivinhar dona).
DO $$
DECLARE r RECORD; nova BIGINT;
BEGIN
    FOR r IN SELECT id, nome FROM loja WHERE empresa_id IS NULL LOOP
        INSERT INTO empresa (razao_social, cnpj)
        VALUES (COALESCE(NULLIF(btrim(r.nome), ''), 'Empresa'), NULL)
        RETURNING id INTO nova;
        UPDATE loja SET empresa_id = nova WHERE id = r.id;
    END LOOP;
END $$;

CREATE INDEX IF NOT EXISTS idx_loja_empresa ON loja (empresa_id);
