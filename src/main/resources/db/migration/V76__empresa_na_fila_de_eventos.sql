-- A fila de eventos passa a saber de QUEM e a venda, nao so em qual loja ela aconteceu.
--
-- O QUE ESTAVA ERRADO
--
-- A V74 criou `evento_outbox` com a coluna `empresa_id`, e a entrega A2 (o PublicadorDeEventos)
-- gravou `null` literal nela. Nao era descuido de digitacao: o `Pedido` nao carrega a empresa, so a
-- loja, e buscar a empresa custava uma consulta a mais no caminho de toda venda -- entao ficou para
-- depois e ninguem reclamou, porque coluna opcional vazia nao reclama de nada.
--
-- Por que isso importa: `loja_id` diz em qual unidade a venda aconteceu; `empresa_id` diz de quem e
-- a unidade. O isolamento do ERP e por empresa (decisao D-02 da modelagem). Sem a coluna, quem
-- consome a fila tem de fazer JOIN com `loja` em todo evento para saber de quem e a venda -- ou,
-- pior, tratar cada loja como cliente separado.
--
-- MEDIDO EM PRODUCAO (18/11/2026, no dump das 18:26 UTC restaurado num Postgres descartavel):
--
--   lojas ................................. 8
--   lojas SEM empresa ..................... 0
--   empresas .............................. 4
--   lojas apontando para empresa inexistente .. 0
--   eventos na fila ....................... 0
--
--   loja 1 -> empresa 1        loja 18 -> empresa 3
--   loja 17 -> empresa 2       loja 19 -> empresa 3
--                              loja 20 -> empresa 3
--                              loja 21 -> empresa 3
--                              loja 22 -> empresa 3   <- as 5 lojas da Zira sao UMA empresa
--   loja 23 -> empresa 4
--
-- A rede da Zira e o caso que justifica a coluna: cinco unidades, um cliente. Sem `empresa_id` o
-- ERP veria cinco clientes.
--
-- POR QUE AGORA, E POR QUE AGORA E DE GRACA
--
-- A fila tem ZERO eventos: ela nasceu desligada e o interruptor nunca foi clicado (medido no mesmo
-- dump -- a chave `outbox.habilitado` nao existe em `config_plataforma`, e sem a linha ela e
-- desligada). Entao o retrofit abaixo nao toca em nada hoje. Se a fila fosse ligada primeiro, cada
-- venda nasceria um evento sem empresa e o retrofit passaria a ser correcao de historico -- barato
-- com 43 pedidos, caro depois.

-- ---------------------------------------------------------------------------------------------
-- 1. Retrofit: evento que ja existe ganha a empresa da sua loja
-- ---------------------------------------------------------------------------------------------
-- Em producao isto afeta 0 linhas (medido acima). Vai aqui de qualquer forma porque a migracao tem
-- de estar certa para QUALQUER banco em que rodar -- a instancia de desenvolvimento e a de teste
-- podem ter eventos de ensaio gravados com a versao antiga.
--
-- `l.empresa_id is not null` no filtro: gravar NULL onde ja ha NULL e trabalho sem efeito, e o
-- filtro deixa a instrucao idempotente -- rodar duas vezes afeta 0 linhas na segunda.
UPDATE evento_outbox e
   SET empresa_id = l.empresa_id
  FROM loja l
 WHERE l.id = e.loja_id
   AND e.empresa_id IS NULL
   AND l.empresa_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- 2. O indice que faz a tela poder perguntar "a coluna esta sendo preenchida?"
-- ---------------------------------------------------------------------------------------------
-- A empresa do evento sai de uma subconsulta que devolve NULL EM SILENCIO quando nao acha a loja.
-- Silencio e justamente o que essa coluna fez desde a V74. Trocar um NULL silencioso por outro nao
-- seria conserto -- por isso a tela da fila passa a mostrar `eventosSemEmpresa`, e por isso este
-- indice existe.
--
-- PARCIAL, igual ao `ix_outbox_pendente`: no estado saudavel ele esta VAZIO, entao
-- `count(*) ... where empresa_id is null` e instantaneo e CONTINUA instantaneo com a fila crescendo.
-- Um indice cheio seria o sintoma: quer dizer loja sem empresa no cadastro, e o lugar de consertar
-- passa a ser o cadastro, nao a fila.
--
-- `if not exists` para a migracao poder ser reaplicada num banco de desenvolvimento sem estourar.
CREATE INDEX IF NOT EXISTS ix_outbox_sem_empresa ON evento_outbox (id) WHERE empresa_id IS NULL;

-- ---------------------------------------------------------------------------------------------
-- 3. O contrato da coluna, escrito onde o proximo a olhar o banco vai ler
-- ---------------------------------------------------------------------------------------------
-- NAO ganha NOT NULL nem chave estrangeira, e isso e deliberado -- e a mesma decisao da V74. Este
-- INSERT mora na transacao da VENDA: no PostgreSQL uma instrucao que falha envenena a transacao
-- inteira, entao uma recusa por integridade referencial aqui nao atrasaria um evento, derrubaria a
-- venda. Loja sem empresa e lacuna de cadastro; venda perdida e dinheiro. Entre as duas, a coluna
-- aceita NULL e a tela avisa.
COMMENT ON COLUMN evento_outbox.empresa_id IS
  'De quem e a venda (a empresa da loja), preenchida por subconsulta no proprio INSERT. '
  'Opcional e SEM chave estrangeira de proposito: a fila nao pode recusar uma venda por '
  'integridade referencial. NULL aqui quer dizer loja sem empresa no cadastro -- a tela da fila '
  'mostra a contagem, e o indice parcial ix_outbox_sem_empresa a torna barata.';
