-- A ordem em que as secoes do cardapio aparecem passa a ser escolha da loja.
--
-- O DEFEITO (medido em 28/10/2026, na Zira)
-- Alinhamos as categorias das tres lojas da Zira aos nomes do cardapio de papel. Os NOMES ficaram
-- iguais; a ORDEM nao, e nao tinha como ficar: as duas telas listam em ordem alfabetica.
--   - a Frente de Caixa faz `[...set].sort()` (pdv.js), entao os chips saem A-Z;
--   - o cardapio publico vinha de `findByLojaIdAndAtivoTrueOrderByCategoriaAscNomeAsc`, entao o
--     servidor entregava os produtos agrupados em A-Z e a tela so preservava essa ordem.
-- O papel da Zira abre com "Especiais para Compartilhar" e fecha com "Pague 4, Leve 5". Em ordem
-- alfabetica, o cliente abre o cardapio em "Acai Fitness" -- duas bebidas proteicas -- e os carros-
-- chefe ficam no meio da pagina. A primeira tela e a vitrine, e ela estava sendo sorteada pelo
-- alfabeto.
--
-- POR QUE UMA TABELA, E NAO UMA COLUNA EM `produto`
-- Porque categoria NAO e cadastro neste sistema: e um campo de texto dentro de CADA produto (e por
-- isso existe o renomear-categoria.js, que regrava produto por produto). Uma coluna `ordem` em
-- `produto` guardaria o mesmo numero repetido em todos os irmaos, e bastaria um produto com o
-- numero diferente para a categoria se partir em duas no cardapio do cliente. Aqui a posicao e
-- gravada UMA vez por categoria, por loja.
--
-- POR QUE `varchar(80)`, EXATAMENTE
-- Porque `produto.categoria` e `varchar(80)` na producao (conferido). Este `nome` guarda o mesmo
-- texto, e o Hibernate com `ddl-auto=validate` NAO confere largura de coluna -- foi assim que um
-- endereco longo perdeu um pedido na v126. Largura diferente da origem e um truncamento esperando
-- acontecer: categoria com 80 caracteres entraria aqui cortada e deixaria de casar com a dos
-- produtos, que e justamente o que a tabela existe para evitar.
--
-- O QUE ACONTECE COM QUEM NAO ESTA AQUI
-- Nada quebra: categoria sem linha nesta tabela vai para o FIM, em ordem alfabetica. Isso vale
-- para toda loja que nunca abriu a tela de ordem (todas, hoje) -- que e exatamente o comportamento
-- de antes. A tabela nasce vazia de proposito: ordenar sozinho o cardapio de 5 lojas seria eu
-- decidindo a vitrine delas.
CREATE TABLE categoria_ordem (
    id      BIGSERIAL PRIMARY KEY,
    loja_id BIGINT       NOT NULL,
    nome    VARCHAR(80)  NOT NULL,
    posicao INTEGER      NOT NULL
);

-- Uma posicao por categoria, por loja. O banco e quem garante: duas abas salvando a ordem ao mesmo
-- tempo passariam juntas por qualquer conferencia feita em Java.
CREATE UNIQUE INDEX ux_categoria_ordem_loja_nome ON categoria_ordem (loja_id, nome);

-- A leitura e sempre "a ordem desta loja", e ela acontece em TODA abertura do cardapio publico.
CREATE INDEX ix_categoria_ordem_loja_posicao ON categoria_ordem (loja_id, posicao);
