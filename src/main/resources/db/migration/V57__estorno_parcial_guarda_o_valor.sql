-- Item 3.6 da varredura: o estorno PARCIAL nao tinha onde guardar quanto voltou.
--
-- O que a varredura dizia e o que medir mostrou (21/10/2026):
--   "Um estorno de R$ 10 tira os R$ 199 do mes" -- JA NAO ERA VERDADE. Existe um ramo proprio para
--   PAYMENT_PARTIALLY_REFUNDED que recusa marcar a cobranca como estornada, justamente para nao
--   tirar a mensalidade inteira, e deixa um aviso no log citando este item.
--   "`estornadoEm` nunca e limpo" -- TAMBEM nao: ele volta a nulo quando a disputa de chargeback e
--   vencida (AssinaturaService, no PAYMENT_AWAITING_CHARGEBACK_REVERSAL).
--
-- O que sobrava de verdade: sem coluna para o valor, a mensalidade conta CHEIA no faturamento depois
-- de uma devolucao parcial, e o unico rastro e uma linha de log que ninguem le ao fechar o mes.
--
-- Agora:
--   estornado_valor NULO  = nao houve estorno, OU houve antes desta migracao (e ai `estornado_em`
--                           preenchido continua valendo como estorno INTEGRAL, que e a leitura
--                           honesta para o passado: era o unico estado que o sistema sabia gravar).
--   estornado_valor > 0   = quanto voltou ao cliente. O faturamento desconta isso do valor, em vez
--                           de tirar a cobranca inteira da conta.
alter table pagamento_assinatura add column if not exists estornado_valor numeric(12,2);

comment on column pagamento_assinatura.estornado_valor is
    'Quanto foi devolvido ao cliente. Nulo com estornado_em preenchido = estorno integral (linha '
    'anterior a V57, ou evento que devolve tudo). O faturamento desconta este valor.';
