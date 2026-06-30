insert into loja(nome,documento,ativo) values('Sorveteria da Rosa','00000000000100',true);
insert into configuracao_loja(loja_id,nome_exibicao,nome_sistema,cor_primaria,cor_secundaria) values(1,'Sorveteria da Rosa','Bora','#7c3aed','#22c55e');
insert into cliente(loja_id,nome,telefone,endereco,bairro) values(1,'Maria Souza','(21) 99999-0001','Rua A, 123','Centro');
insert into produto(loja_id,nome,categoria,preco) values(1,'Açaí 500ml','Açaí',24.90);
insert into pedido(loja_id,codigo,cliente_id,status,valor_total,forma_pagamento,origem,criado_em,atualizado_em) values(1,'#1001',1,'RECEBIDO',42.90,'Pix','WhatsApp',now(),now());
