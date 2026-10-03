-- O telefone era gravado de dois jeitos. O cardapio e os marketplaces guardam so digitos
-- (FidelidadeService.normalizar); a tela de clientes guardava exatamente o que o lojista digitasse,
-- com mascara: "(15) 99999-0001". A busca do cardapio e por digitos, entao o cliente cadastrado pelo
-- lojista nao era encontrado quando pedia pelo cardapio: nascia um SEGUNDO cadastro, e o cashback
-- acumulado ficava preso no primeiro, inacessivel para ele.
--
-- Aqui so endireitamos o que ja esta gravado. Quem grava daqui para frente ja normaliza.
UPDATE cliente
   SET telefone = NULLIF(regexp_replace(telefone, '\D', '', 'g'), '')
 WHERE telefone IS NOT NULL
   AND telefone <> COALESCE(NULLIF(regexp_replace(telefone, '\D', '', 'g'), ''), '');

COMMENT ON COLUMN cliente.telefone IS 'Somente digitos. E a chave de busca do cardapio e dos marketplaces.';
