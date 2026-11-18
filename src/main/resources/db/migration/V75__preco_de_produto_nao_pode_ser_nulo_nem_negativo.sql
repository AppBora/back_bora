-- Preco de produto: nunca NULO, nunca NEGATIVO. ZERO continua valendo.
--
-- POR QUE NO BANCO, se a aplicacao ja valida
-- ----------------------------------------------------------------------------------------------
-- Porque a aplicacao valida em UMA porta e existem outras. Em 18/11/2026, quem grava produto:
--   ProdutoService.criar/atualizar ....... agora valida (PrecoDoCardapio.exigirValido)
--   IaService.importarCardapio ........... agora recusa e relata o item sem preco legivel
--   CopiaDeCardapioService ............... copia `n.preco = o.preco` SEM conferir nada
--   SQL direto na instancia .............. nenhuma conferencia
-- A copia de cardapio e o caminho de abrir unidade nova (foi assim na 6a loja da Zira). Se a loja
-- de origem tivesse um produto sem preco, a copia levava o defeito para a loja nova, em silencio.
--
-- ZERO E LEGITIMO. NULO NAO E.
-- ----------------------------------------------------------------------------------------------
-- Medido no cardapio publico em 18/11/2026: dos 182 produtos ativos das 6 lojas reais, NENHUM tem
-- preco nulo e 5 tem preco zero de proposito:
--   "Monte Seu Acai" nas lojas 18, 19 e 20 (Zira) -- preco 0,00 porque quem carrega o preco e o
--     grupo de complemento "Recipientes", que tem minimo 1 e opcoes de R$ 12,90 a R$ 50,00.
--     O item nao pode sair por zero: o minimo nao deixa.
--   "Brinde Gratis" e "Brinde Gratuito" na loja 1 -- brinde e zero de proposito.
-- Entao `preco > 0` quebraria 5 produtos em 4 lojas reais. A regra e `preco >= 0`, nao `> 0`.
-- Zero diz "e de graca". Nulo diz "ninguem disse" -- e quem nao sabe o preco nao vende.
--
-- POR QUE "NOT VALID"
-- ----------------------------------------------------------------------------------------------
-- NOT VALID faz o Postgres aplicar a regra em todo INSERT e UPDATE a partir de agora, mas PULAR a
-- varredura da tabela inteira para conferir as linhas que ja existem. Duas razoes:
--
--  1. Nao da para eu conferir as 23 lojas daqui. Eu medi as 6 reais pelo cardapio publico, que so
--     mostra `ativo = true`. Produto INATIVO e produto das 17 lojas de teste eu nao vi. Se uma
--     dessas linhas tiver preco nulo, uma constraint validada FALHA A MIGRACAO e, com ela, O DEPLOY
--     INTEIRO -- por causa de um produto desativado de uma loja de teste.
--
--  2. O conserto honesto para uma linha dessas e o lojista DIGITAR o preco, nao a migracao chutar
--     um. Um `UPDATE produto SET preco = 0 WHERE preco IS NULL` aqui transformaria "ninguem disse"
--     em "e de graca" calado -- exatamente o defeito que esta migracao existe para fechar.
--
-- Para validar depois, quando o dono quiser, primeiro ACHAR e CORRIGIR as linhas:
--   select id, loja_id, nome, preco from produto where preco is null or preco < 0;
--   -- corrigir cada uma pelo painel, e so entao:
--   alter table produto validate constraint ck_produto_preco_valido;   -- varre a tabela
-- Enquanto nao validar, nada NOVO entra errado, que e o que importa hoje.

alter table produto drop constraint if exists ck_produto_preco_valido;

alter table produto
    add constraint ck_produto_preco_valido
    check (preco is not null and preco >= 0)
    not valid;
