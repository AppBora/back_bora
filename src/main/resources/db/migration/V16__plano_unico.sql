-- Plano único (decisão 2026-07-05): R$ 299/mês por loja, pedidos ilimitados, até 15 usuários.
-- Converte todas as lojas e assinaturas dos planos antigos (START/PRO/PREMIUM) para UNICO.
UPDATE loja SET plano = 'UNICO';
ALTER TABLE loja ALTER COLUMN plano SET DEFAULT 'UNICO';
UPDATE assinatura SET plano = 'UNICO', valor = 299.00, atualizado_em = NOW();
