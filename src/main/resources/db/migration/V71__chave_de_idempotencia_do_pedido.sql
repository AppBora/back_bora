-- A mesma venda, gravada uma vez. Nao duas.
--
-- O DEFEITO, QUE E DE HOJE E NAO PRECISA DE INTERNET RUIM
-- `POST /api/pedidos` nao tem chave de idempotencia. Se a resposta se perde na volta -- e perder a
-- VOLTA e o caso comum: o pedido foi gravado, o navegador nao soube --, o balconista clica
-- "Finalizar" de novo e a venda nasce DUAS VEZES. O estrago e triplo e todo em dinheiro:
--   * estoque baixado duas vezes (e insumo da ficha consumido duas vezes);
--   * cashback acumulado duas vezes para o cliente;
--   * faturamento inflado, e o lojista lendo um numero que nao aconteceu.
--
-- O contraste que mostra que isto e um esquecimento, e nao uma escolha: o pedido de MARKETPLACE ja
-- tem essa trava desde a V28 (`uq_pedido_canal_externo`), posta justamente para o evento repetido do
-- canal nao virar segunda linha. O pedido do BALCAO -- o caminho mais usado do sistema -- ficou sem.
--
-- POR QUE A TRAVA TEM DE SER NO BANCO, E NAO EM JAVA
-- Dois cliques quase juntos sao duas requisicoes em paralelo. "Procura e, se nao achar, grava" perde
-- essa corrida: as duas procuram antes de qualquer uma gravar, as duas nao acham, as duas gravam.
-- E a mesma licao da V65. O indice unico e o unico lugar onde a decisao e serializada.
--
-- MEDIDO NA PRODUCAO ANTES DESTA MIGRACAO (11/11/2026, leitura apenas)
--   pedidos no banco ..................... 43
--   com chave_idempotencia ............... 0 (a coluna nao existia)
-- Nada a limpar: isto fecha a porta. Duplicatas que ja tenham acontecido nao sao identificaveis
-- depois do fato -- duas vendas iguais no mesmo minuto podem ser dois clientes pedindo o mesmo acai.

ALTER TABLE pedido ADD COLUMN IF NOT EXISTS chave_idempotencia text;

COMMENT ON COLUMN pedido.chave_idempotencia IS
  'Chave que o cliente HTTP gera uma vez por carrinho e repete no reenvio. Nula nos pedidos '
  'anteriores a V71, nos de marketplace (que tem a propria trava, uq_pedido_canal_externo) e nos do '
  'cardapio publico.';

-- Parcial: a esmagadora maioria das linhas tem a coluna nula -- pedido antigo, de marketplace ou do
-- cardapio. Sem o WHERE, todos os nulos brigariam entre si e nenhum segundo pedido entraria.
-- Por loja, e nao global: a chave vem do navegador do lojista, e duas lojas podem sortear a mesma.
CREATE UNIQUE INDEX IF NOT EXISTS uq_pedido_loja_chave_idempotencia
    ON pedido (loja_id, chave_idempotencia)
    WHERE chave_idempotencia IS NOT NULL;
