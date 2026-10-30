-- Um bairro, um frete. Um telefone, um cliente.
--
-- POR QUE ISTO EXISTE
-- `pedido` ganhou na V28 o indice unico `uq_pedido_canal_externo` exatamente para o evento repetido
-- do marketplace nao virar segunda linha. Duas tabelas com o MESMO problema ficaram so com indices
-- NAO unicos, e a deduplicacao delas e read-then-insert em Java -- que perde a corrida:
--
--   * `taxa_entrega` (V12): nenhum UNIQUE em (loja, bairro). Com duas linhas para o mesmo bairro, o
--     cardapio mostra o bairro DUAS VEZES com fretes diferentes, e a consulta que cobra escolhia uma
--     delas. O cliente via um preco na tela e pagava outro.
--   * `cliente` (V29 e V35): `idx_cliente_loja_telefone` e `idx_cliente_externo` existem e NAO sao
--     unicos. Dois pedidos do mesmo telefone ao mesmo tempo -- o cliente clicando duas vezes em
--     "confirmar" -- criam dois clientes, e o cashback fica repartido entre os dois.
--
-- O QUE FOI MEDIDO NA PRODUCAO ANTES DESTA MIGRACAO (30/10/2026, leitura apenas)
--   bairro repetido na mesma loja ....... 0 grupos
--   cliente com telefone repetido ....... 0 grupos
--   cliente com id externo repetido ..... 0 grupos
-- Ou seja: nao ha o que limpar hoje. Isto fecha a porta ANTES de a primeira duplicata nascer.

-- ---------------------------------------------------------------- taxa_entrega
--
-- AQUI A LIMPEZA E AUTOMATICA, e pode ser: taxa de entrega e cadastro, nao dinheiro de ninguem.
-- A linha que fica e a de MAIOR id -- a ultima que o lojista gravou --, que e exatamente a regra que
-- o `OperacaoService.salvarTaxa` e o `findFirst...OrderByIdDesc` ja usam. Se a regra daqui fosse
-- outra, a migracao apagaria justo a linha que o sistema estava cobrando.
DELETE FROM taxa_entrega t
 WHERE EXISTS (
   SELECT 1 FROM taxa_entrega mais_nova
    WHERE mais_nova.loja_id = t.loja_id
      AND lower(trim(mais_nova.bairro)) = lower(trim(t.bairro))
      AND mais_nova.id > t.id);

-- `lower(trim(...))`: "Centro", "centro" e " Centro " sao o mesmo bairro para quem digita e para quem
-- escolhe no cardapio, entao tem de ser o mesmo para o banco. Indice sobre expressao, nao sobre a
-- coluna crua -- com a coluna crua, as tres grafias passariam pelo UNIQUE sem reclamar.
CREATE UNIQUE INDEX IF NOT EXISTS uq_taxa_entrega_loja_bairro
    ON taxa_entrega (loja_id, lower(trim(bairro)));

-- ---------------------------------------------------------------- cliente
--
-- AQUI NAO HA LIMPEZA AUTOMATICA, E E DE PROPOSITO.
--
-- Juntar dois clientes duplicados significa juntar SALDO DE CASHBACK, total gasto e historico de
-- compras. Isso e dinheiro de cliente, e nenhuma migracao deve decidir sozinha o que fazer com ele:
-- somar os dois saldos pode criar dinheiro que a loja nao creditou, e ficar com um so pode apagar
-- dinheiro que o cliente ganhou. Quem decide isso e uma pessoa, olhando os dois registros.
--
-- Entao a migracao PARA, com uma mensagem que diz o que fazer. Hoje ela nao para: foram medidos
-- zero grupos duplicados nas duas chaves. Se um dia parar, o deploy fica de fora do ar ate alguem
-- olhar -- o que e ruim, e ainda assim e melhor que mesclar saldo em silencio.
DO $$
DECLARE grupos INT;
BEGIN
  SELECT count(*) INTO grupos FROM (
    SELECT loja_id, telefone FROM cliente
     WHERE telefone IS NOT NULL AND trim(telefone) <> ''
     GROUP BY loja_id, telefone HAVING count(*) > 1) x;
  IF grupos > 0 THEN
    RAISE EXCEPTION 'PAREI: % grupo(s) de cliente com o MESMO telefone na mesma loja. Juntar dois '
      'clientes junta SALDO DE CASHBACK, e isso nao e decisao de migracao. Resolva os duplicados '
      'pelo painel (Clientes) e rode o deploy de novo. Para ver quais: select loja_id, telefone, '
      'count(*) from cliente where telefone is not null and trim(telefone) <> '''' group by 1,2 '
      'having count(*) > 1;', grupos;
  END IF;
END $$;

DO $$
DECLARE grupos INT;
BEGIN
  SELECT count(*) INTO grupos FROM (
    SELECT loja_id, canal_externo, id_externo FROM cliente
     WHERE id_externo IS NOT NULL AND trim(id_externo) <> ''
     GROUP BY loja_id, canal_externo, id_externo HAVING count(*) > 1) x;
  IF grupos > 0 THEN
    RAISE EXCEPTION 'PAREI: % grupo(s) de cliente com o MESMO id externo na mesma loja e canal. '
      'Mesma razao do telefone: juntar cliente junta cashback. Resolva pelo painel e rode de novo.',
      grupos;
  END IF;
END $$;

-- Parciais: cliente de balcao sem telefone e normal, e cliente do canal proprio nao tem id externo.
-- Sem o `WHERE`, todos os nulos (ou todos os vazios) brigariam entre si.
CREATE UNIQUE INDEX IF NOT EXISTS uq_cliente_loja_telefone
    ON cliente (loja_id, telefone)
    WHERE telefone IS NOT NULL AND trim(telefone) <> '';

CREATE UNIQUE INDEX IF NOT EXISTS uq_cliente_loja_canal_id_externo
    ON cliente (loja_id, canal_externo, id_externo)
    WHERE id_externo IS NOT NULL AND trim(id_externo) <> '';
