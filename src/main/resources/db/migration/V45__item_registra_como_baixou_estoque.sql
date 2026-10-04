-- Como este item baixou estoque no momento em que o pedido nasceu.
--
-- Sao dois caminhos excludentes: produto COM ficha tecnica consome os insumos da ficha; produto SEM
-- ficha baixa o estoque dele mesmo. Para devolver no cancelamento, era preciso adivinhar olhando a
-- ficha de HOJE -- e quem cadastrou ou apagou uma ficha depois do pedido devolveria o ingrediente
-- errado, ou criaria estoque do nada. Com a marca gravada no item, a devolucao desfaz exatamente o
-- que foi feito.
--
-- null = item criado antes desta migracao. Nesse caso a devolucao volta a olhar a ficha atual, que e
-- a melhor informacao disponivel para o passado.
alter table pedido_item add column if not exists consumiu_ficha boolean;

comment on column pedido_item.consumiu_ficha is
  'true = baixou insumos pela ficha tecnica; false = baixou o estoque do proprio produto; null = item anterior a V45.';
