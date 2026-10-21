-- O adicional (complemento) passa a poder apontar para um insumo, e a baixar estoque por ele.
--
-- O item 2.6 da varredura de 18/10/2026: `complemento_item` tinha so (id, loja_id, grupo_id, nome,
-- preco). Numa acaiteria metade do valor da venda esta nos adicionais, e nada disso saia do estoque
-- nem entrava no custo. Nao era um `if` esquecido: o modelo nao comportava.
--
-- Das tres saidas possiveis, o dono escolheu ligar o adicional ao INSUMO, e nao dar estoque proprio
-- ao adicional. O motivo: a mesma granola esta na ficha tecnica dos produtos e nos adicionais. Dois
-- lugares para contar a mesma granola nunca fecham entre si -- e o consumo pelo insumo reaproveita
-- o caminho que ja existe, ja e atomico no banco e ja recusa venda sem estoque.
alter table complemento_item add column if not exists insumo_id bigint;
alter table complemento_item add column if not exists quantidade numeric(14,4);

-- A chave estrangeira e de proposito, e e a diferenca em relacao a `produto_insumo`.
--
-- `produto_insumo` NAO tem FK, e foi exatamente isso que produziu a ficha fantasma do item 2.5:
-- apagar o insumo deixava a linha da ficha apontando para o nada, e o estoque vazava calado. O
-- servico recusa apagar insumo que esta em uso e diz onde ele esta -- essa e a mensagem boa, e ela
-- vem primeiro. Esta FK e a rede embaixo: se um dia alguem apagar insumo por outro caminho, o banco
-- recusa em vez de criar fantasma novo. Nenhuma linha existente tem insumo_id (a coluna nasce
-- agora), entao nada pode falhar na subida.
do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'fk_comp_item_insumo') then
        alter table complemento_item
            add constraint fk_comp_item_insumo foreign key (insumo_id) references insumo (id);
    end if;
end $$;

-- Para a recusa do `excluir` ser uma consulta barata: "este insumo esta em algum adicional?"
create index if not exists ix_comp_item_insumo on complemento_item (loja_id, insumo_id);

comment on column complemento_item.insumo_id is
    'Insumo que este adicional consome. Nulo = adicional que nao baixa estoque (o padrao, e o '
    'comportamento de tudo que foi cadastrado antes desta migracao).';
comment on column complemento_item.quantidade is
    'Quanto do insumo sai por unidade do item vendido, na unidade do insumo (ex.: 0,0300 kg de '
    'Leite Ninho). Nulo ou zero com insumo preenchido nao consome nada.';

-- O registro no item do pedido ganha dois campos. Quem le o historico tem que saber disso.
comment on column pedido_item.complementos is
    'JSON com os complementos escolhidos [{id,nome,preco,insumoId,quantidade}]. Nulo = item anterior '
    'ao registro; [] = sem complemento. O insumo e a quantidade ficam gravados AQUI, e nao sao '
    'relidos do cadastro na hora de cancelar: o ComplementoController apaga e recria os adicionais a '
    'cada gravacao, com ids novos, entao reler pelo id devolveria o insumo de OUTRO adicional.';
