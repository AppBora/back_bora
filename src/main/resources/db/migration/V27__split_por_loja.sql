-- Split por loja: permite isentar clientes fundadores (0%) mantendo o padrao da plataforma
-- para quem entrar depois. NULL = usa ASAAS_TAXA_PERCENTUAL (padrao global).
ALTER TABLE loja ADD COLUMN IF NOT EXISTS split_percentual NUMERIC(5,2);

-- Os fundadores foram vendidos com "sem taxa por pedido": quem ja tem preco negociado
-- (oferta de fundador) nasce isento de split.
UPDATE loja SET split_percentual = 0 WHERE preco_mensal IS NOT NULL AND split_percentual IS NULL;
