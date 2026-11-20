-- As 22 chaves estrangeiras de `loja_id` que faltavam.  (item 6 da secao 4, 20/11/2026)
--
-- O DEFEITO, medido no schema de verdade (banco limpo com as 81 migracoes aplicadas, 20/11/2026):
-- 31 tabelas tem a coluna `loja_id` e apenas 8 declaravam chave estrangeira para `loja`. Nas outras
-- 23 o banco aceitava, sem reclamar, uma linha apontando para uma loja que nao existe -- produto,
-- pedido, cliente, insumo, assinatura, pagamento. "Multi-tenant" era convencao de codigo: quem
-- garantia o isolamento era o `ctx.lojaId()` em cada consulta, e nada abaixo dele.
--
-- O QUE ISSO PERMITE NA PRATICA, e nao e hipotese: um `loja_id` errado num INSERT (parametro
-- trocado de posicao, variavel errada, um zero que era nulo) grava dado de um cliente dentro de
-- outro, EM SILENCIO. Nenhum erro, nenhum log; a linha simplesmente nao aparece mais para ninguem,
-- ou aparece para quem nao devia. Este projeto ja teve um mutante de INSERT sujar producao quatro
-- vezes -- e nessa familia de erro a FK e a unica rede que pega na hora.
--
-- ORFAS NA PRODUCAO: ZERO. Medido em 20/11/2026 no dump das 12:15, restaurado num Postgres
-- descartavel DENTRO do servidor (o dump nao sai de la: ele tem telefone, endereco e CPF dos
-- clientes de cada loja; so a contagem saiu). As 31 tabelas foram conferidas uma por uma, somando
-- 4.441 linhas com `loja_id`, e nenhuma aponta para loja inexistente. E por isso que as chaves
-- abaixo entram JA VALIDADAS em vez de `NOT VALID`: nao ha nada para limpar antes.
--
-- A MAIOR TABELA TEM 3.298 LINHAS (complemento_item). Validar uma FK varre a tabela e pega
-- ShareRowExclusiveLock nela e em `loja`; nessa escala sao milissegundos. Em outra escala esta
-- migracao precisaria ser `NOT VALID` + `VALIDATE` em passos separados, fora de uma transacao so --
-- e o lugar de escrever isso e aqui, para quem for mexer nisto com a tabela grande.
--
-- SEM ON DELETE em quase todas, de proposito: nada no sistema APAGA loja (conferido -- a
-- plataforma ARQUIVA o cliente, e o proprio javadoc do `PlataformaController` diz "Nunca apaga").
-- Entao a FK sem ON DELETE nao bloqueia nenhum caminho que exista hoje, e no dia em que alguem
-- escrever um "apagar loja" o banco vai exigir que ele decida o que fazer com os dados dela -- que
-- e exatamente a conversa que precisa acontecer antes de apagar o cadastro de um cliente pagante.
--
-- AS DUAS EXCECOES, e as duas tem motivo escrito:
--
--   evento_outbox  FICA SEM FK. A V74 decidiu isso e a razao continua valendo: o INSERT da fila mora
--                  DENTRO da transacao da venda, e no PostgreSQL uma instrucao que falha envenena a
--                  transacao inteira. Uma FK ali transforma "loja fora do cadastro" em VENDA
--                  PERDIDA. Fila orfa e historico sem dono; venda perdida e dinheiro.
--
--   auditoria      ENTRA, mas com ON DELETE SET NULL. A trilha nao pode impedir uma exclusao nem
--                  desaparecer com ela -- e a mesma regra que a V74 ja aplicou ao
--                  `auditoria.usuario_id`. `loja_id` ali e anulavel, entao da para ter as duas
--                  coisas: integridade agora, e sobrevivencia no dia em que a loja for apagada.
SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- Cadastro e operacao da loja
-- ---------------------------------------------------------------------------------------------
ALTER TABLE produto               ADD CONSTRAINT fk_produto_loja               FOREIGN KEY (loja_id) REFERENCES loja (id);
ALTER TABLE produto_insumo        ADD CONSTRAINT fk_produto_insumo_loja        FOREIGN KEY (loja_id) REFERENCES loja (id);
ALTER TABLE insumo                ADD CONSTRAINT fk_insumo_loja                FOREIGN KEY (loja_id) REFERENCES loja (id);
ALTER TABLE categoria_ordem       ADD CONSTRAINT fk_categoria_ordem_loja       FOREIGN KEY (loja_id) REFERENCES loja (id);
ALTER TABLE promocao              ADD CONSTRAINT fk_promocao_loja              FOREIGN KEY (loja_id) REFERENCES loja (id);
ALTER TABLE taxa_entrega          ADD CONSTRAINT fk_taxa_entrega_loja          FOREIGN KEY (loja_id) REFERENCES loja (id);
ALTER TABLE horario_funcionamento ADD CONSTRAINT fk_horario_funcionamento_loja FOREIGN KEY (loja_id) REFERENCES loja (id);
ALTER TABLE forma_pagamento       ADD CONSTRAINT fk_forma_pagamento_loja       FOREIGN KEY (loja_id) REFERENCES loja (id);
ALTER TABLE motivo_cancelamento   ADD CONSTRAINT fk_motivo_cancelamento_loja   FOREIGN KEY (loja_id) REFERENCES loja (id);
ALTER TABLE configuracao_loja     ADD CONSTRAINT fk_configuracao_loja_loja     FOREIGN KEY (loja_id) REFERENCES loja (id);
ALTER TABLE integracao_canal      ADD CONSTRAINT fk_integracao_canal_loja      FOREIGN KEY (loja_id) REFERENCES loja (id);

-- ---------------------------------------------------------------------------------------------
-- A venda
-- ---------------------------------------------------------------------------------------------
ALTER TABLE pedido             ADD CONSTRAINT fk_pedido_loja             FOREIGN KEY (loja_id) REFERENCES loja (id);
ALTER TABLE pedido_item        ADD CONSTRAINT fk_pedido_item_loja        FOREIGN KEY (loja_id) REFERENCES loja (id);
ALTER TABLE log_status         ADD CONSTRAINT fk_log_status_loja         FOREIGN KEY (loja_id) REFERENCES loja (id);
ALTER TABLE pedido_de_senha    ADD CONSTRAINT fk_pedido_de_senha_loja    FOREIGN KEY (loja_id) REFERENCES loja (id);
ALTER TABLE cliente            ADD CONSTRAINT fk_cliente_loja            FOREIGN KEY (loja_id) REFERENCES loja (id);

-- ---------------------------------------------------------------------------------------------
-- Entrega
-- ---------------------------------------------------------------------------------------------
ALTER TABLE entregador         ADD CONSTRAINT fk_entregador_loja         FOREIGN KEY (loja_id) REFERENCES loja (id);
ALTER TABLE acerto_entregador  ADD CONSTRAINT fk_acerto_entregador_loja  FOREIGN KEY (loja_id) REFERENCES loja (id);

-- ---------------------------------------------------------------------------------------------
-- Dinheiro da plataforma (assinatura do lojista)
-- ---------------------------------------------------------------------------------------------
ALTER TABLE assinatura           ADD CONSTRAINT fk_assinatura_loja           FOREIGN KEY (loja_id) REFERENCES loja (id);
ALTER TABLE pagamento_assinatura ADD CONSTRAINT fk_pagamento_assinatura_loja FOREIGN KEY (loja_id) REFERENCES loja (id);

-- ---------------------------------------------------------------------------------------------
-- Modulo IA e trilha
-- ---------------------------------------------------------------------------------------------
ALTER TABLE ia_analise_rede ADD CONSTRAINT fk_ia_analise_rede_loja FOREIGN KEY (loja_id) REFERENCES loja (id);
-- A trilha sobrevive a exclusao da loja, e nao a impede. Ver "AS DUAS EXCECOES" no cabecalho.
ALTER TABLE auditoria       ADD CONSTRAINT fk_auditoria_loja       FOREIGN KEY (loja_id) REFERENCES loja (id) ON DELETE SET NULL;

COMMENT ON TABLE evento_outbox IS
  'Fila de eventos do modulo principal, gravada na mesma transacao do fato. SEM chave estrangeira '
  'para loja/empresa DE PROPOSITO (V74 e V82): o INSERT mora na transacao da venda, e uma FK aqui '
  'transformaria loja fora do cadastro em venda perdida.';
