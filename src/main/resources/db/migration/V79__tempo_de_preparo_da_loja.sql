-- O tempo de preparo passa a ser DA LOJA.  (item 5 da secao 4, 20/11/2026)
--
-- O DEFEITO, medido: o tempo prometido estava cravado no JavaScript -- `BORA_SLA`, em
-- `assets/js/app.js`, uma tabela fixa por canal (Balcao 12, WhatsApp 25, Site 30, Delivery 35,
-- marketplace 40) com 30 minutos de reserva para canal desconhecido. Nenhuma loja escolhe nada
-- disso. Consequencia na tela onde a cozinha trabalha: a pizzaria cujo forno leva 45 minutos ve
-- TODO pedido marcado "Atrasado" -- o cartao fica vermelho sempre, e um alerta que acende sempre
-- deixa de ser alerta. O inverso tambem: o acai que sai em 5 minutos nunca acende nada.
--
-- DUAS COLUNAS, E POR QUE NAO UMA:
--
--   tempo_preparo_min  -- o que a COZINHA leva. E o numero que o lojista sabe de cabeca.
--   tempo_entrega_min  -- o que a RUA leva, somado so quando o pedido e entrega.
--
-- Com uma coluna so, o lojista teria de somar os dois de cabeca e informar um numero que mistura as
-- duas coisas -- e o pedido de RETIRADA (que nao tem rua) herdaria o tempo da entrega, voltando a
-- marcar atraso onde nao ha. Sao grandezas diferentes; quem separa aqui nao precisa adivinhar
-- depois.
--
-- NULO = O PADRAO DE HOJE, e isto e a parte importante. Coluna vazia faz a tela cair exatamente na
-- tabela `BORA_SLA` de sempre. Ou seja: esta migracao, por si, NAO muda o que nenhuma das 23 lojas
-- ve. Quem muda e o lojista, quando preencher os campos em Configuracoes. A alternativa -- semear um
-- valor "razoavel" para todos -- seria decidir o tempo de forno de 23 cozinhas que eu nao conheco.
--
-- SEM CHECK DE FAIXA NO BANCO, de proposito: a faixa (preparo 1..240, entrega 0..180) e conferida no
-- ConfiguracaoLojaService, junto com as outras validacoes da mesma tela, com mensagem que o lojista
-- le. Um CHECK aqui devolveria 500 sem texto, e so para quem escrevesse direto no banco.
--
-- ADITIVA: duas colunas novas, nulas, numa tabela de 23 linhas. Nada de backfill, nada de NOT NULL.
-- A versao anterior da aplicacao sobe contra este schema sem ver as colunas (o passo 2.5 do
-- deploy-api.sh confere isso sozinho).
ALTER TABLE configuracao_loja ADD COLUMN IF NOT EXISTS tempo_preparo_min INTEGER;
ALTER TABLE configuracao_loja ADD COLUMN IF NOT EXISTS tempo_entrega_min INTEGER;

COMMENT ON COLUMN configuracao_loja.tempo_preparo_min IS
  'Minutos que a cozinha leva, escolhidos pelo lojista. NULO = cai na tabela por canal do app.js.';
COMMENT ON COLUMN configuracao_loja.tempo_entrega_min IS
  'Minutos de rua, somados ao preparo APENAS em pedido de entrega. NULO = cai na tabela do app.js.';
