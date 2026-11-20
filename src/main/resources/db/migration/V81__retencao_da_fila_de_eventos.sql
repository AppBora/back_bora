-- A fila de eventos passa a ter RETENCAO.  (item 8 da secao 4, 20/11/2026)
--
-- O DEFEITO, medido: `evento_outbox` nasceu na V74 e cresce para sempre. Sao ate 6 eventos por
-- pedido, ~620 bytes cada, e NINGUEM apaga nada -- nao ha publicador nem consumidor ainda, entao
-- toda linha gravada fica. Com as 23 lojas vendendo, isso e crescimento continuo num banco que
-- ninguem esta olhando, e a primeira pessoa a notar seria o disco.
--
-- A DECISAO QUE EU TOMEI, E ELA ESTA NA CARA
-- A varredura dizia "a decisao de retencao deve vir JUNTO com o consumidor, nao depois", e isso
-- continua certo: apagar evento que ninguem leu e perder dado de producao para sempre. Entao o que
-- entra aqui e o MECANISMO, com duas chaves em `config_plataforma` (o mesmo jeito de
-- `outbox.habilitado`, que liga e desliga SEM DEPLOY):
--
--   outbox.retencao.dias            apaga evento JA PUBLICADO com mais de N dias.
--   outbox.retencao.dias-pendentes  apaga evento NAO publicado com mais de N dias.
--
-- As duas AUSENTES por padrao, e ausente significa "nao apaga nada". Ou seja: esta migracao e o
-- codigo que vem com ela NAO apagam um unico evento hoje. A segunda chave e a que precisa da
-- palavra do dono, e e por isso que ela e separada da primeira em vez de um numero so -- "limpar o
-- que ja foi entregue" e "jogar fora o que ninguem leu" nao sao a mesma decisao.
--
-- O INDICE, e por que um so
-- As duas limpezas filtram por `criado_em`. Um indice simples em `criado_em` serve as duas, porque
-- a condicao de data e a seletiva: o filtro de `publicado_em` sobra para o proprio DELETE conferir
-- nas poucas linhas que a data trouxe. Dois indices parciais (um por chave) seriam dois indices
-- para manter em TODA gravacao da fila -- e gravacao da fila mora na transacao da venda.
--
-- Nao e `CREATE INDEX CONCURRENTLY`: o Flyway roda a migracao numa transacao, e CONCURRENTLY nao
-- pode rodar em transacao. Isto e seguro aqui por construcao, nao por medicao: a chave
-- `outbox.habilitado` foi ligada em 19/11/2026, entao a tabela tem no maximo o movimento desde
-- aquele dia -- criar indice nela e questao de milissegundos. No dia em que a tabela for grande, o
-- lugar de criar indice novo e com CONCURRENTLY fora do Flyway; e e justamente a retencao que
-- impede esse dia de chegar.
CREATE INDEX IF NOT EXISTS ix_outbox_criado_em ON evento_outbox (criado_em);

COMMENT ON INDEX ix_outbox_criado_em IS
  'Para a retencao (RetencaoDaFila): as duas limpezas filtram por criado_em.';
