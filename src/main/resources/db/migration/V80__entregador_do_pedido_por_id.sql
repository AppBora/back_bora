-- O motoboy do pedido passa a ter ID, e nao so o nome digitado.  (item 3 da secao 4, 20/11/2026)
--
-- O DEFEITO, E ELE E DINHEIRO DE MOTOBOY
-- `pedido.entregador` e TEXTO livre, e o acerto agrupa por `lower(trim(entregador))`. Entao renomear
-- o motoboy no cadastro no meio do periodo -- corrigir "Jose" para "Jose Carlos", tirar um apelido,
-- arrumar um acento -- divide as corridas dele em DUAS linhas na tela de acerto: as de antes com o
-- nome velho, as de depois com o novo. Quem fecha o acerto paga uma e nao a outra, e a que ficou
-- para tras nao aparece como pendencia em lugar nenhum -- ela simplesmente virou "outro
-- entregador". O cadastro de entregador JA EXISTE (tabela `entregador`, com tela propria desde a
-- V9); o pedido e que nunca o apontou.
--
-- O QUE ESTA MIGRACAO FAZ, em tres passos:
--   1. a coluna `entregador_id` em `pedido` e em `acerto_entregador`;
--   2. o BACK-FILL do que ja esta gravado, casando pelo nome normalizado dentro da MESMA loja;
--   3. a chave estrangeira e o indice que a consulta do acerto vai usar.
--
-- O TEXTO CONTINUA GRAVADO, de proposito. Ele deixa de ser a chave e passa a ser o retrato do nome
-- no dia da entrega: o cartao do pedido, a comanda impressa e o recibo do motoboy leem dali sem
-- precisar de JOIN, e o historico nao muda quando o cadastro muda. Quem agrupa dinheiro e o id.
--
-- O BACK-FILL NAO ADIVINHA. So preenche quando existe EXATAMENTE UM entregador com aquele nome
-- naquela loja. Dois cadastros com o mesmo nome (o caso real: "Joao" cadastrado duas vezes) ficam
-- com id nulo e seguem agrupando pelo texto, como hoje -- o acerto continua certo, so nao ganha a
-- protecao nova. Chutar um dos dois moveria a taxa de um motoboy para outro, que e pior do que o
-- defeito que estou consertando.
--
-- LOCK_TIMEOUT, mesma razao da V74: a FK abaixo pega ShareRowExclusiveLock em `pedido` e em
-- `entregador`. Com as tabelas pequenas isso e questao de milissegundos, mas se houver uma transacao
-- longa aberta a migracao ESPERA e as gravacoes das lojas ficam na fila atras dela. Com o teto, o
-- pior caso vira "falha em 5 segundos e se repete depois", em vez de "o painel travou".
SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------- 1. as colunas
ALTER TABLE pedido             ADD COLUMN IF NOT EXISTS entregador_id BIGINT;
ALTER TABLE acerto_entregador  ADD COLUMN IF NOT EXISTS entregador_id BIGINT;

-- ---------------------------------------------------------------------------- 2. o back-fill
-- `lower(btrim(...))` e a MESMA normalizacao que as consultas do acerto usam hoje -- o que garante
-- que o id preenchido aqui agrupa as mesmas entregas que o texto agrupava.
UPDATE pedido p
   SET entregador_id = e.id
  FROM entregador e
 WHERE e.loja_id = p.loja_id
   AND lower(btrim(e.nome)) = lower(btrim(p.entregador))
   AND p.entregador IS NOT NULL
   AND btrim(p.entregador) <> ''
   AND p.entregador_id IS NULL
   -- exatamente um candidato, senao nao mexe (ver o comentario do cabecalho)
   AND (SELECT count(*) FROM entregador e2
         WHERE e2.loja_id = p.loja_id
           AND lower(btrim(e2.nome)) = lower(btrim(p.entregador))) = 1;

-- O historico de acertos ja fechados ganha o id pelo mesmo criterio. Serve a relatorio; nenhuma
-- conta de dinheiro e refeita por isto -- acerto fechado e fato consumado.
UPDATE acerto_entregador a
   SET entregador_id = e.id
  FROM entregador e
 WHERE e.loja_id = a.loja_id
   AND lower(btrim(e.nome)) = lower(btrim(a.entregador))
   AND a.entregador IS NOT NULL
   AND btrim(a.entregador) <> ''
   AND a.entregador_id IS NULL
   AND (SELECT count(*) FROM entregador e2
         WHERE e2.loja_id = a.loja_id
           AND lower(btrim(e2.nome)) = lower(btrim(a.entregador))) = 1;

-- ---------------------------------------------------------------------------- 3. FK e indice
-- ON DELETE SET NULL, e isto NAO e detalhe -- e a mesma licao da V74. A tela de entregadores APAGA
-- de verdade (DELETE /api/entregadores/{id}). Sem o ON DELETE, no dia em que alguem apagasse um
-- motoboy que ja tem entrega, a exclusao estouraria violacao de FK e -- por ser Postgres --
-- derrubaria a transacao inteira, devolvendo 500 sem mensagem. Perder o vinculo e aceitavel: o NOME
-- continua gravado no pedido e o acerto volta a agrupar por texto, que e o comportamento de hoje.
-- Travar a exclusao nao e aceitavel.
--
-- Sem NOT VALID: o back-fill acima so escreveu ids que vieram da propria tabela `entregador`, entao
-- nao existe linha orfa por construcao e a validacao e uma varredura numa tabela pequena.
ALTER TABLE pedido
  ADD CONSTRAINT fk_pedido_entregador
  FOREIGN KEY (entregador_id) REFERENCES entregador (id) ON DELETE SET NULL;

ALTER TABLE acerto_entregador
  ADD CONSTRAINT fk_acerto_entregador
  FOREIGN KEY (entregador_id) REFERENCES entregador (id) ON DELETE SET NULL;

-- O irmao do `idx_pedido_acerto_entregador` (V56), na mesma forma e com o mesmo filtro parcial:
-- `acerto_id is null` e a condicao das duas consultas do acerto e a mais seletiva daqui -- entrega
-- ja acertada nunca mais aparece.
CREATE INDEX IF NOT EXISTS idx_pedido_acerto_entregador_id
    ON pedido (loja_id, entregador_id, entregue_em)
 WHERE acerto_id IS NULL AND entregador_id IS NOT NULL;

COMMENT ON COLUMN pedido.entregador_id IS
  'Cadastro do motoboy (tabela entregador). E a chave que o acerto agrupa. NULO = pedido anterior a '
  'V80, ou nome que nao casou com um unico cadastro: ai vale o texto de pedido.entregador.';
COMMENT ON COLUMN pedido.entregador IS
  'O NOME no dia da entrega (retrato). Nao e mais a chave do acerto: ver entregador_id. Fica para o '
  'cartao, a comanda e o recibo lerem sem JOIN, e para o historico nao mudar quando o cadastro muda.';
