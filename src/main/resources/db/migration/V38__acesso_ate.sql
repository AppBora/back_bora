-- Até aqui, a loja nascia ativa e sem assinatura, e quem não pagava não perdia nada: o aviso de
-- fatura vencida só marcava a assinatura como INADIMPLENTE e o painel continuava aberto. Os Termos
-- de Uso publicados prometem outra coisa (suspensão em 10 dias, cancelamento em 30).
--
-- 'acesso_ate' é a data em que o acesso da loja vence quando não há assinatura paga:
--   * loja nova          -> nasce com 7 dias (a cortesia que o site anuncia);
--   * pagamento confirmado -> volta a NULL (sem prazo enquanto estiver pagando);
--   * fatura vencida     -> ganha a carência antes do corte.
--
-- NULL = sem prazo. As lojas que já existem ficam NULL de propósito: ninguém é cortado por causa
-- desta migração. O corte em si ainda depende do interruptor bora.cobranca.corte-por-assinatura,
-- que nasce DESLIGADO — a decisão de quando cortar é do dono, não do código.
ALTER TABLE loja ADD COLUMN IF NOT EXISTS acesso_ate timestamptz;

COMMENT ON COLUMN loja.acesso_ate IS
  'Fim do acesso quando nao ha assinatura paga. NULL = sem prazo.';
