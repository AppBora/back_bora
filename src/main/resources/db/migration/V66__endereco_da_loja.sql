-- O cadastro da loja passa a guardar o endereco dela.
--
-- O QUE FALTAVA (medido em 30/10/2026)
-- Nao existia endereco de loja em lugar nenhum do sistema. Procurei nas tres pontas: a tabela `loja`
-- tinha nome, documento, empresa, plano e cobranca; a `configuracao_loja` so marca (nome de exibicao,
-- logo, cores, subdominio, cashback, custos); e a tela de Configuracoes do painel nao tinha um campo
-- de endereco. O unico endereco que o sistema guarda e o do CLIENTE (`cliente.endereco`) e o do
-- PEDIDO (`pedido.endereco_entrega`) -- para onde a comida vai, nunca de onde ela sai.
--
-- A consequencia apareceu quando o dono foi cadastrar as unidades da Zira: seis lojas da mesma
-- empresa, cada uma num bairro, e o unico lugar para dizer qual e qual era o NOME. "Zira - Centro" e
-- "Zira - Zona Norte" nao dizem em que rua a loja esta, e ninguem consegue conferir se a unidade
-- certa foi renomeada. Endereco escrito dentro do nome tambem nao serve para mais nada: nao imprime
-- no cupom, nao aparece no cardapio, nao vai para nota fiscal.
--
-- POR QUE EM `loja`, E NAO EM `configuracao_loja`
-- Porque endereco e CADASTRO, nao aparencia. Fica ao lado do `documento` (CNPJ), que e o outro dado
-- societario da unidade, e nasce junto com a loja no `POST /admin-bora/lojas`. A
-- `configuracao_loja` guarda o que o lojista troca para deixar a loja com a cara dele; trocar de
-- endereco e mudanca de endereco, nao de tema.
--
-- POR QUE CAMPOS SEPARADOS, E NAO UM TEXTO SO
-- Um `endereco varchar(500)` seria mais rapido de escrever e inutil para o resto: o `bairro` precisa
-- bater com `taxa_entrega.bairro` para um dia dizer "esta unidade entrega aqui", a `cidade` e a `uf`
-- sao obrigatorias em nota fiscal, e o `cep` e o unico campo que um dia permite achar a unidade mais
-- perto do cliente. Texto livre nao se consulta sem adivinhar.
--
-- AS LARGURAS, E POR QUE ESTAS
-- `bairro varchar(120)` espelha `taxa_entrega.bairro`, que e varchar(120) desde a V12 e e a coluna
-- mais larga de bairro do sistema. A regra ficou da v126 e vale aqui: o `ddl-auto=validate` do
-- Hibernate NAO confere largura de coluna, entao coluna espelho mais estreita que a origem e
-- truncamento esperando acontecer -- foi assim que um endereco longo perdeu um pedido.
-- `logradouro varchar(200)` cabe nome de rua com titulo por extenso ("Rua Doutor ..."), que e o caso
-- de tres das unidades da Zira. `uf char(2)` porque sigla de estado tem dois caracteres e sempre
-- teve.
--
-- TUDO NASCE NULO, DE PROPOSITO
-- As 5 lojas que ja existem na producao nao tem endereco gravado em lugar nenhum, e deduzir endereco
-- a partir do nome ("Zira - Centro" fica no centro de que cidade?) seria inventar dado de cadastro.
-- Nulo diz a verdade: ninguem informou ainda. Quem quiser cobrar o preenchimento cobra na tela, que
-- e onde o lojista esta.
ALTER TABLE loja
    ADD COLUMN IF NOT EXISTS cep         VARCHAR(9),
    ADD COLUMN IF NOT EXISTS logradouro  VARCHAR(200),
    ADD COLUMN IF NOT EXISTS numero      VARCHAR(20),
    ADD COLUMN IF NOT EXISTS complemento VARCHAR(100),
    ADD COLUMN IF NOT EXISTS bairro      VARCHAR(120),
    ADD COLUMN IF NOT EXISTS cidade      VARCHAR(120),
    ADD COLUMN IF NOT EXISTS uf          VARCHAR(2);

COMMENT ON COLUMN loja.cep IS
  'CEP da unidade, so numeros ou com hifen (varchar(9) cabe 00000-000). Nulo = nao informado.';
COMMENT ON COLUMN loja.logradouro IS
  'Rua/avenida da unidade. varchar(200) para caber nome com titulo por extenso.';
COMMENT ON COLUMN loja.bairro IS
  'Bairro da unidade. varchar(120) espelha taxa_entrega.bairro -- o validate do Hibernate nao confere largura.';
COMMENT ON COLUMN loja.uf IS
  'Sigla do estado, 2 letras maiusculas. O servidor normaliza antes de gravar.';
