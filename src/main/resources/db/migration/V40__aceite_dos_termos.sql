-- Nenhum cliente jamais aceitou os Termos: a tela de cadastro nao tinha caixa de aceite nem link, e
-- nada era gravado. Na pratica, nao existia contrato aceito com ninguem -- e os Termos publicados
-- falam de pagamento, suspensao, cancelamento e reembolso.
--
-- Guardamos quando o aceite aconteceu, de qual endereco veio e qual versao do texto estava no ar.
-- A versao e a data de "Ultima atualizacao" da propria pagina: sem ela, daqui a um ano ninguem sabe
-- a que texto o lojista disse sim.
ALTER TABLE loja ADD COLUMN IF NOT EXISTS termos_aceitos_em timestamptz;
ALTER TABLE loja ADD COLUMN IF NOT EXISTS termos_versao varchar(20);
ALTER TABLE loja ADD COLUMN IF NOT EXISTS termos_aceitos_de varchar(60);

COMMENT ON COLUMN loja.termos_aceitos_em  IS 'Quando o lojista aceitou os Termos no cadastro. NULL = aceite nao registrado.';
COMMENT ON COLUMN loja.termos_versao      IS 'Versao do texto vigente no momento do aceite (data de revisao da pagina).';
COMMENT ON COLUMN loja.termos_aceitos_de  IS 'Endereco de origem do aceite, como prova minima.';
