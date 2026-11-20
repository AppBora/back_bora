-- ================================================================================================
-- O CUPOM DO CARDAPIO DA LOJA 18 ESTAVA DANDO 500. ESTA MIGRACAO FECHA A PORTA.
-- ================================================================================================
--
-- O DEFEITO, medido no dump de producao de 19/11/2026
--
--   select loja_id, upper(codigo), count(*) from promocao group by 1,2 having count(*) > 1;
--   -->  18 | BORA15L18 | 4
--
-- Quatro linhas com o mesmo codigo na mesma loja. O `DescontoService` buscava a promocao com um
-- metodo que devolvia `Optional`, e duas linhas viram `IncorrectResultSizeDataAccessException`:
-- **HTTP 500 no cupom do cardapio publico de um cliente que paga**.
--
-- COMO AS QUATRO NASCERAM
--
-- `PromocaoService.abrirAutomatica` fazia `new Promocao()` com o codigo fixo `'BORA15L' || loja_id`,
-- e o comentario na propria linha dizia "codigo unico por loja". Nada garantia isso: a V6 e a unica
-- migracao que tocava `promocao`, e o unico indice era `idx_promocao_loja_ativa (loja_id, ativa)`,
-- que **nao e unico**. O scheduler desligava a promocao 3 h depois, mas a LINHA ficava; no dia
-- seguinte as duas guardas do servico liberavam (nao havia promocao ATIVA, e a contagem de AUTO era
-- so de HOJE) e nascia a proxima.
--
-- O conserto tem tres partes, e esta e a terceira:
--   1. `PromocaoService` passou a REAPROVEITAR a linha (uma por loja, para sempre);
--   2. `DescontoService` passou a buscar LISTA, para que duplicata nunca mais vire 500;
--   3. aqui: renomear o que ja esta duplicado e criar o indice unico.
--
-- POR QUE RENOMEAR E NAO APAGAR
--
-- Medido antes de decidir: **zero chaves estrangeiras** apontam para `promocao`, e **nenhuma coluna
-- do banco se chama `promocao_id`** -- ou seja, nada se quebraria apagando. Ainda assim eu renomeio,
-- porque apagar linha de producao para satisfazer um indice e trocar um defeito por perda de
-- historico. As quatro linhas da loja 18 estao todas com `ativa = false` e `usos = 0`: ninguem usou
-- nenhuma delas, e nenhum cliente perde cupom com o renome.
--
-- E POR QUE A ORDEM IMPORTA
--
-- Criar o indice unico ANTES de limpar derruba a migracao -- e com ela o deploy inteiro, porque no
-- PostgreSQL a instrucao que falha envenena a transacao. Foi a licao da FK de `produto_insumo`
-- (21/10/2026): limpar primeiro, restringir depois.
-- ================================================================================================

-- ------------------------------------------------------------------------------------------------
-- PASSO 1 · renomear as duplicadas, mantendo a MAIS RECENTE com o codigo original
-- ------------------------------------------------------------------------------------------------
-- A mais recente e a que o `DescontoService` escolheria hoje (ele ordena por `inicio` decrescente),
-- entao manter ela e manter o comportamento. O desempate por `id` cobre `inicio` igual.
-- O sufixo `-<id>` e unico por construcao, porque `id` e a chave primaria.
WITH ordenadas AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY loja_id, upper(codigo)
               ORDER BY inicio DESC NULLS LAST, id DESC
           ) AS posicao
      FROM promocao
     WHERE codigo IS NOT NULL
)
UPDATE promocao p
   SET codigo = p.codigo || '-' || p.id
  FROM ordenadas o
 WHERE o.id = p.id
   AND o.posicao > 1;

-- ------------------------------------------------------------------------------------------------
-- PASSO 2 · o indice unico
-- ------------------------------------------------------------------------------------------------
-- `upper(codigo)` porque a busca do checkout ignora caixa (o Spring Data gera `upper(codigo) =
-- upper(?)` para `IgnoreCase`). Sem o `upper` no indice, `bora15l18` e `BORA15L18` seriam duas
-- linhas distintas para o banco e a MESMA para a busca -- e o 500 voltaria pela porta dos fundos.
--
-- `WHERE codigo IS NOT NULL` porque existe em producao uma promocao com codigo NULO (id 2, loja 1,
-- origem MANUAL). Ela nao e alcancavel pelo checkout (ninguem digita nulo) e nao deve bloquear o
-- indice. Em PostgreSQL varios NULL nao colidem num indice unico, mas o predicado deixa a intencao
-- explicita em vez de depender desse detalhe.
CREATE UNIQUE INDEX IF NOT EXISTS uq_promocao_loja_codigo
    ON promocao (loja_id, upper(codigo))
 WHERE codigo IS NOT NULL;

COMMENT ON INDEX uq_promocao_loja_codigo IS
    'Um codigo de promocao por loja. Existe porque 4 linhas de BORA15L18 na loja 18 faziam o cupom '
    'do cardapio publico responder 500 (IncorrectResultSizeDataAccessException). Ver '
    'V77__codigo_de_promocao_unico_por_loja.sql.';
