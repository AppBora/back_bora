-- Desde quando esta integracao esta LIGADA E FUNCIONANDO. E para o aviso de "sem pedido" poder
-- dizer ha quanto tempo.
--
-- O QUE A TELA JA DIZ, E O QUE FALTAVA
-- Desde 30/10/2026 o cartao de Integracoes diz "Conectado, sem pedido ainda" em vez de prometer
-- que os pedidos chegam. O que ele NAO diz e ha quanto tempo esta assim -- e e a duracao que
-- transforma o aviso em diagnostico: ligado ha duas horas sem pedido e uma tarde fraca; ligado ha
-- cinco dias sem nenhum pedido e vinculo errado do lado do marketplace.
--
-- POR QUE NAO DAVA PARA MEDIR COM O QUE JA EXISTIA
-- Nao ha nenhuma coluna de data de criacao nesta tabela (conferido no information_schema em
-- 13/11/2026: as 6 colunas de data sao ultima_sync, token_expira_em, vinculo_expira_em,
-- ultimo_polling_em, token_renovando_em e ultimo_aviso_em). E o `ultima_sync` NAO serve: ele e
-- gravado em quatro caminhos, e um deles -- o webhook do PIX, no PublicController -- nao e pedido
-- nenhum; e o `descontarRecebido` desconta o contador sem mexer na data. Medido hoje na producao:
-- a loja 18 tem ultima_sync de 21/10 com pedidos_recebidos = 0, e o PIX da loja 1 tem data com
-- contador zerado. Ou seja, "ultima_sync" quer dizer "ultima coisa que chegou por aqui", e nao
-- "ultimo pedido".
--
-- QUEM ESCREVE, E POR QUE SO UM LUGAR
-- O `marcarPollingOk`, com `coalesce`: a PRIMEIRA busca que deu certo. E a definicao mais forte
-- disponivel -- nao "alguem marcou a coluna status como CONECTADO", e sim "esta integracao falou
-- com o marketplace e foi atendida". Status CONECTADO e escrito em OITO lugares diferentes no
-- codigo; carimbar a data nos oito seria garantir que o nono esquecesse, em silencio, que e
-- exatamente o padrao que esta lista vive consertando.
--
-- NAO reinicia quando a integracao se levanta de um ERRO, de proposito: uma queda de dez minutos
-- nao desmente uma seca de tres dias, e e a seca que o lojista precisa enxergar. Quem zera e a
-- troca de vinculo (outra loja no marketplace), que e situacao nova de verdade.
--
-- SEM PREENCHIMENTO RETROATIVO, E ISSO E ESCOLHA
-- Nao existe nenhum dado de onde tirar a data real das 6 integracoes que ja estao ligadas. Chutar
-- `now()` faria a tela afirmar "conectado ha 1 minuto" para uma loja ligada desde 10/11 -- trocaria
-- "nao sei" por uma mentira precisa. Ficam nulas: a tela mantem o texto de hoje ate a primeira
-- busca boa depois do deploy (30 segundos), e a conta comeca dali, honesta.
--
-- MEDIDO NA PRODUCAO ANTES DESTA MIGRACAO (13/11/2026, leitura apenas)
--   integracoes ativas ................................. 11
--   CONECTADO com pedidos_recebidos = 0 ................ 8
--   dessas, as 5 lojas da Zira no 99Food, buscando pedido a cada 30 s desde 10/11

ALTER TABLE integracao_canal ADD COLUMN IF NOT EXISTS conectado_em timestamptz;

COMMENT ON COLUMN integracao_canal.conectado_em IS
  'Primeira busca de pedido bem-sucedida desta ligacao. Escrita so por marcarPollingOk, com coalesce. '
  'Nula = ainda nao houve uma (ou canal que nao faz polling: PIX, WhatsApp, manual). Zerada quando o '
  'lojista troca o vinculo. NAO reinicia ao se levantar de um ERRO.';
