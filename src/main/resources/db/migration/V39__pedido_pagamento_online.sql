-- O pedido com PIX online nascia "RECEBIDO" com a forma "PIX (aguardando)" e entrava na cozinha e no
-- faturamento como qualquer outro. Quem desistia de pagar deixava uma venda fantasma para sempre, e a
-- unica forma de saber se tinha sido pago era comparar um texto ("PIX (pago)") entre duas partes do
-- codigo -- contrato fragil, e o relatorio nem olhava.
--
-- Agora o pagamento online e um fato com data:
--   aguardando_pagamento = true  -> o cliente gerou o PIX e ainda nao pagou
--   pago_em                      -> quando o pagamento foi confirmado pelo Asaas
--
-- Pedido de balcao, WhatsApp ou marketplace nao muda: nasce com aguardando_pagamento = false.
ALTER TABLE pedido ADD COLUMN IF NOT EXISTS aguardando_pagamento boolean NOT NULL DEFAULT false;
ALTER TABLE pedido ADD COLUMN IF NOT EXISTS pago_em timestamptz;

-- Os pedidos de PIX que ja estao no banco: o texto da forma de pagamento e a unica pista que temos.
UPDATE pedido SET aguardando_pagamento = true
 WHERE forma_pagamento = 'PIX (aguardando)'
   AND status <> 'CANCELADO'
   AND pago_em IS NULL;

UPDATE pedido SET pago_em = coalesce(atualizado_em, criado_em)
 WHERE forma_pagamento = 'PIX (pago)' AND pago_em IS NULL;

-- O trabalho que o cobrador faz a cada poucos minutos: achar o que venceu sem pagar.
CREATE INDEX IF NOT EXISTS idx_pedido_aguardando ON pedido (loja_id, criado_em)
 WHERE aguardando_pagamento = true;
