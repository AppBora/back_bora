ALTER TABLE loja ADD COLUMN plano VARCHAR(20) NOT NULL DEFAULT 'START';

-- loja demo do seed usa o Pro para demonstrar recursos
UPDATE loja SET plano = 'PRO' WHERE id = 1;
