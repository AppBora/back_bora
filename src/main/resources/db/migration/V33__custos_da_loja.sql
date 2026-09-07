-- Custos que faltavam para sair de "margem" e chegar em lucro: imposto e custo fixo.
-- Ficam por loja porque regime tributario e aluguel/folha variam entre unidades da mesma rede.
ALTER TABLE configuracao_loja ADD COLUMN IF NOT EXISTS aliquota_imposto  NUMERIC(5,2);
ALTER TABLE configuracao_loja ADD COLUMN IF NOT EXISTS custo_fixo_mensal NUMERIC(12,2);
