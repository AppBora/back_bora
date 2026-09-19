-- Item do iFood vem com complementos e personalizações por extenso ("PRODUTO 2 (COMBO) (+ Complemento 1,
-- ... Complemento 4 [Customização 1, 2, 3])"): o combo do primeiro pedido real passou de 160 caracteres
-- e o banco recusou o pedido (19/09). Endereço junta rua, número, complemento e ponto de referência.
ALTER TABLE pedido_item ALTER COLUMN descricao TYPE TEXT;
ALTER TABLE cliente ALTER COLUMN endereco TYPE VARCHAR(500);
