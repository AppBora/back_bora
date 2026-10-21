-- Itens 4.6 e 4.7 da varredura: tirar indice que nenhuma consulta pode usar, e por o que falta.
--
-- A REGRA QUE EU APLIQUEI AQUI, porque ela muda o resultado:
-- um indice sai quando NENHUMA FORMA DE CONSULTA do codigo pode usar ele -- nao quando o contador
-- `idx_scan` esta em zero. A varredura acusou tres indices de "ninguem usa", medindo o contador na
-- producao. Mas a producao tem 5 lojas, 32 pedidos e 46 itens de pedido: nessa escala o Postgres faz
-- varredura sequencial e esta CERTO, entao zero uso nao diz nada sobre o indice -- diz que a tabela e
-- pequena. Jogar fora por esse motivo seria tirar justamente o indice que vai fazer falta quando
-- houver dado. Conferido tambem que os numeros da varredura ("24 MB cada com 800 mil itens") nao sao
-- da producao: os indices de pedido_item tem 16 kB.

set lock_timeout = '5s';

-- ---------------------------------------------------------------- 4.6 o duplicado de verdade
-- `idx_pedido_item_pedido` (V7) e `idx_pedido_item_loja` (V15) tem a definicao IDENTICA:
-- btree (loja_id, pedido_id). Isto nao depende de escala nem de contador -- e o mesmo indice escrito
-- duas vezes, mantido nos dois a cada insert. Fica o da V7, que e o que a producao usa (614 usos
-- contra 0, o que aqui so diz qual dos dois o planejador escolheu).
drop index if exists idx_pedido_item_loja;

-- ---------------------------------------------------------------- 4.6 os que nenhuma consulta alcanca
-- `idx_loja_nao_excluida on loja (id) where excluida_em is null`: indice pela CHAVE PRIMARIA com um
-- filtro parcial. Qualquer busca por id ja usa a pkey, e nenhuma consulta do projeto filtra
-- `excluida_em is null` no SQL -- o `arquivada()` e um metodo Java, decidido depois de carregar a
-- loja. Nao existe forma de consulta que ele sirva, em escala nenhuma.
drop index if exists idx_loja_nao_excluida;

-- `idx_pedido_bairro_entrega on pedido (loja_id, bairro_entrega)`: criado na V48 para "o agrupamento
-- por bairro da tela de Entregas". Medido: esse agrupamento acontece no NAVEGADOR
-- (assets/js/entregas.js:62), sobre a lista que a tela ja carregou. Nenhum SQL filtra nem agrupa por
-- `bairro_entrega` -- a coluna so e lida e devolvida. Mesma conclusao, mesmo motivo.
drop index if exists idx_pedido_bairro_entrega;

-- ---------------------------------------------------------------- 4.6 o que estava INCOMPLETO
-- A varredura chamou `ix_pagamento_assinatura_loja` de inutil. Nao e: a unica consulta que filtra
-- essa tabela por loja filtra TAMBEM a data
-- (`findByLojaIdAndPagoEmGreaterThanEqualAndPagoEmLessThan`, a que distingue clique duplo de segundo
-- pagamento de verdade no mesmo dia). So `(loja_id)` obriga o banco a ler todas as mensalidades da
-- loja e filtrar a data depois; com a data no indice ele le so a janela.
drop index if exists ix_pagamento_assinatura_loja;
create index if not exists ix_pagamento_assinatura_loja_pago_em
    on pagamento_assinatura (loja_id, pago_em);

-- ---------------------------------------------------------------- 4.7 o indice do acerto
-- `idx_pedido_acerto on pedido (loja_id, entregador, acerto_id)` desperdica a coluna do meio: as duas
-- consultas do acerto comparam `lower(trim(entregador))`, e funcao sobre a coluna impede o uso do
-- indice naquela posicao. O banco usava so o prefixo `loja_id` e filtrava o resto lendo linha por
-- linha. Nenhuma consulta do projeto usa `entregador` cru num filtro (conferido), entao o indice
-- antigo nao serve a mais nada e sai.
--
-- O novo e uma EXPRESSAO, na mesma forma que as consultas escrevem, e PARCIAL em `acerto_id is null`
-- -- que e a condicao das duas e a mais seletiva que existe aqui: entrega ja acertada nunca mais
-- aparece. `lower(trim(...))` e IMMUTABLE, que e o que o Postgres exige para indexar expressao.
drop index if exists idx_pedido_acerto;
create index if not exists idx_pedido_acerto_entregador
    on pedido (loja_id, lower(trim(entregador)), entregue_em)
 where acerto_id is null and entregador is not null;

-- ---------------------------------------------------------------- o que eu NAO fiz, e por que
-- NAO criei `(loja_id, criado_em)` para o painel da plataforma: ja existe `idx_pedido_loja`, que e
-- `(loja_id, criado_em DESC)`. Eu ia criar, e medir os indices da producao mostrou que seria um
-- segundo indice identico em tudo menos na direcao -- exatamente o defeito do 4.6 que esta migracao
-- conserta. O que faltava ali era a CONSULTA, nao o indice: ver `ultimoPedidoPorLoja`.
--
-- NAO apaguei `idx_pedido_aguardando`, `idx_pedido_entregue_em`, `idx_pedido_loja_status` nem
-- `idx_pedido_loja_cliente`, todos com zero uso na producao hoje: cada um casa com uma consulta real
-- do codigo (PIX pendente, janela de entrega, quadro por status, ultimo pedido do cliente). Zero uso
-- com 32 pedidos e sobre o tamanho da tabela, nao sobre o indice.
