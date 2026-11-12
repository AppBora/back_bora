-- Quantas vezes seguidas a busca de pedido falhou. E para a loja parar de ficar VERDE quando cai.
--
-- O DEFEITO
-- `marcarPollingFalhou` gravava a hora e a mensagem, e NAO mexia no status. O caminho inverso
-- existia (sucesso promove ERRO -> CONECTADO), mas a queda nao rebaixava nada. Resultado: uma loja
-- CONECTADO que comeca a levar 403 em toda consulta continua verde na tela, com o motivo escondido
-- numa coluna que ninguem le. Foi exatamente isso na loja 18 em 04/11/2026
-- (`status = CONECTADO`, `ultimo_erro = 403 ... forbidden`), e e o mesmo "morre em silencio" que
-- custou dois meses no 99Food.
--
-- POR QUE UM CONTADOR, E NAO REBAIXAR NA PRIMEIRA FALHA
-- Porque ERRO tem preco: `mereceNovaTentativa()` espaca a proxima tentativa em 10 MINUTOS. Rebaixar
-- no primeiro tropeco faria um 500 passageiro da 99 atrasar pedido real em ate 10 min -- trocaria um
-- defeito de silencio por um defeito de atraso. Com o ciclo de 30 s e o limite de 3, uma queda de
-- verdade fica vermelha em ~1,5 min e um tropeco isolado nao custa nada.
--
-- MEDIDO NA PRODUCAO ANTES DESTA MIGRACAO (11/11/2026, leitura apenas)
--   integracoes cadastradas ................ 15
--   integracoes ativas ..................... 11
--   CONECTADO com ultimo_erro preenchido ... 1 (comeca do zero aqui)
-- O default 0 e proposital: comecar do zero significa "ainda nao falhou nesta serie". A proxima
-- rodada que der certo confirma o zero; a proxima que falhar comeca a contar.

ALTER TABLE integracao_canal ADD COLUMN IF NOT EXISTS falhas_seguidas integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN integracao_canal.falhas_seguidas IS
  'Falhas CONSECUTIVAS de polling. Zerada por marcarPollingOk. Ao chegar no limite, marcarPollingFalhou '
  'rebaixa CONECTADO para ERRO. Nao conta falha de autenticacao, que ja tem caminho proprio.';
