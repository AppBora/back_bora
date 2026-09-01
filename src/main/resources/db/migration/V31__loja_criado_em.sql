-- Data de criacao da loja: MODELO_DADOS.md ja prometia o campo, o schema nunca teve.
-- Sem ele o painel de clientes nao tem como mostrar ha quanto tempo o cliente esta na casa.
ALTER TABLE loja ADD COLUMN IF NOT EXISTS criado_em TIMESTAMPTZ;

-- Backfill honesto para as lojas que ja existem: o primeiro rastro conhecido dela
-- (abertura da assinatura ou primeiro pedido). Quem nao tem rastro fica NULL e a tela
-- mostra "--" em vez de inventar uma data.
UPDATE loja l SET criado_em = t.dt
  FROM (
    SELECT loja_id, MIN(dt) AS dt FROM (
      SELECT loja_id, criado_em AS dt FROM assinatura WHERE criado_em IS NOT NULL
      UNION ALL
      SELECT loja_id, criado_em AS dt FROM pedido WHERE criado_em IS NOT NULL
    ) x GROUP BY loja_id
  ) t
 WHERE t.loja_id = l.id AND l.criado_em IS NULL;
