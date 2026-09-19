-- O pagamento do iFood agora vai por extenso e por forma de pagamento ("Pago online: Crédito Visa
-- R$ 17,00 + Pago online: Crédito Master R$ 10,00"). Com dois cartões passou de 60 caracteres e o
-- primeiro pedido assim foi recusado pelo banco (19/09) — o evento ficou esperando, nada se perdeu.
ALTER TABLE pedido ALTER COLUMN forma_pagamento TYPE VARCHAR(255);
