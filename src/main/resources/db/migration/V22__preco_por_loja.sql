-- Preço mensal específico por loja (oferta de fundador R$149 vitalício; NULL = preço de tabela do plano).
ALTER TABLE loja ADD COLUMN preco_mensal NUMERIC(12,2);
