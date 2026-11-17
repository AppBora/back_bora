-- A fundacao do ERP: as oito tabelas novas que o modulo principal precisa ter ANTES de qualquer
-- modulo ser acoplado. Passo 1 de 8 da fase A (documentacao-erp/MODELAGEM-DE-DADOS-MODULO-PRINCIPAL.md).
--
-- A REGRA DESTA MIGRACAO, E ELA E O MOTIVO DE ELA VIR PRIMEIRO
-- Nada que ja existe e tocado. Nenhum ALTER, nenhum backfill, nenhuma troca de tipo, nenhum indice
-- novo em tabela existente. Sao oito CREATE TABLE e nada mais. Por isso ela pode subir no meio do
-- expediente, sem janela combinada com lojista nenhum: o pior caso de uma tabela que ninguem le
-- ainda e ocupar alguns kilobytes.
--
-- Os passos que MEXEM no que ja roda (colunas novas, backfill, troca de tipo de coluna, FKs que
-- faltam) vem depois, um por vez e medidos. O passo 5 (`ALTER TYPE` em `pedido_item`) e o unico do
-- plano inteiro que trava tabela, e e o unico que exige horario combinado.
--
-- MEDIDO NA PRODUCAO ANTES DE ESCREVER (17/11/2026)
--   PostgreSQL ......................... 16.14  (gen_random_uuid e nativo desde a 13; sem pgcrypto)
--   schema "public" .................... versao 73, 73 migracoes validadas
--   os oito nomes abaixo ............... nenhum existe em migracao nenhuma
--
-- LOCK_TIMEOUT, E POR QUE ELE ESTA AQUI
-- Cada REFERENCES para uma tabela que ja existe cria trigger de FK NELA. Isso pega
-- ShareRowExclusiveLock em `loja`, `usuario` e `empresa` durante a transacao -- bloqueia INSERT,
-- UPDATE e DELETE nessas tabelas (SELECT nao). Com as tabelas novas vazias isso e questao de
-- milissegundos, mas se houver uma transacao longa aberta numa delas, a migracao ESPERA e todas as
-- gravacoes seguintes ficam na fila atras dela -- com 23 lojas vendendo.
--
-- Com o teto abaixo, o pior caso vira "a migracao falha em 5 segundos, limpa, e se repete depois",
-- em vez de "o painel das 23 lojas trava por tempo indeterminado". SET LOCAL vale so nesta
-- transacao, entao nao vaza para o resto da aplicacao.
SET LOCAL lock_timeout = '5s';

-- POR QUE AS TABELAS NASCEM VAZIAS
-- Nenhuma linha e semeada aqui, de proposito. `licenca_modulo` sem linha quer dizer "modulo
-- desligado", que e o estado correto para as 23 lojas hoje; quem traduz os dois mecanismos que JA
-- funcionam (`config_plataforma['fiscal.habilitado']` e `loja.modulo_ia`) e codigo testavel, nao
-- INSERT escondido numa migracao. Semear aqui seria tomar no escuro uma decisao de negocio por 23
-- clientes.

-- ---------------------------------------------------------------------------------------------
-- 1. tenant -- o grupo economico acima de empresa
-- ---------------------------------------------------------------------------------------------
-- Hoje o topo da hierarquia e `empresa` (CNPJ). Falta o nivel de cima: a Zira tem 6 unidades que
-- sao empresas/lojas distintas e pertencem ao mesmo dono. Sem este nivel, "o faturamento do grupo"
-- nao tem onde existir, e cada modulo inventaria o seu proprio jeito de agrupar.
--
-- Nasce vazia e `empresa.tenant_id` so entra na V75: esta migracao nao toca em `empresa`.
CREATE TABLE tenant (
  id        BIGSERIAL PRIMARY KEY,
  nome      TEXT        NOT NULL,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- CUIDADO COM O NOME: no codigo que ja existe, "tenant" sempre quis dizer LOJA -- e o que o token
-- carrega e o que o JwtAuthFilter isola. Esta tabela e outra coisa: o nivel ACIMA de empresa. O
-- isolamento multi-tenant continua sendo por loja_id/empresa_id (decisao D-02 da modelagem); nada
-- transacional ganha tenant_id.
COMMENT ON TABLE tenant IS
  'Grupo economico: o nivel ACIMA de empresa (CNPJ). NAO confundir com o "tenant" do token, que no '
  'codigo existente quer dizer loja. O isolamento continua por loja_id/empresa_id.';

-- ---------------------------------------------------------------------------------------------
-- 2. licenca_modulo -- que modulo esta ligado em que loja
-- ---------------------------------------------------------------------------------------------
-- ISTO NAO E MECANISMO NOVO. Generaliza os DOIS que ja funcionam em producao:
--   config_plataforma['fiscal.habilitado']  -- global, liga/desliga o modulo fiscal para todos
--   loja.modulo_ia                          -- por loja, o add-on pago de IA
-- Os dois continuam valendo e passam a ser lidos ATRAVES desta tabela. Enquanto nao houver linha,
-- o comportamento e identico ao de hoje -- e e por isso que ela pode nascer vazia sem mudar nada.
--
-- AUSENCIA DE LINHA = DESLIGADO, e isto e escolha. A alternativa (semear uma linha por loja por
-- modulo: 23 x 10 = 230 linhas) obrigaria a acertar o estado inicial de 10 modulos para 23 clientes
-- dentro de uma migracao, sem tela e sem como conferir. Ausente significando desligado e a unica
-- leitura que nao pode causar um modulo ligado por engano.
CREATE TABLE licenca_modulo (
  id                 BIGSERIAL PRIMARY KEY,
  loja_id            BIGINT      NOT NULL REFERENCES loja (id),
  modulo             TEXT        NOT NULL CHECK (modulo IN
                     ('CORE','PEDIDOS','FISCAL','ESTOQUE','COMPRAS',
                      'FINANCEIRO','CONTABIL','GESTAO','LOGISTICA','IA')),
  ativo              BOOLEAN     NOT NULL DEFAULT false,
  ativado_em         TIMESTAMPTZ,
  -- ON DELETE SET NULL, e isto NAO e detalhe: o painel da plataforma APAGA usuario de verdade
  -- (DELETE /admin-bora/usuarios/{id}, no ar desde 17/11). Sem o ON DELETE, no dia em que alguem
  -- ligasse um modulo e depois a conta dele fosse excluida, a exclusao estouraria violacao de FK e
  -- -- por ser Postgres -- derrubaria a transacao inteira, devolvendo 500 sem mensagem.
  -- A conta mais provavel de ser apagada e justamente a de quem opera a plataforma, que e quem liga
  -- modulo. Perder o "quem ligou" e aceitavel; travar a exclusao nao e.
  ativado_por        BIGINT      REFERENCES usuario (id) ON DELETE SET NULL,
  desativado_em      TIMESTAMPTZ,
  -- Mesma razao do ativado_por. E existe porque o motivo sozinho nao basta: "falta de pagamento"
  -- volta sozinho quando o cliente paga, "cliente cancelou" nao -- e saber QUEM decidiu e parte da
  -- conversa com o cliente depois.
  desativado_por     BIGINT      REFERENCES usuario (id) ON DELETE SET NULL,
  motivo_desativacao TEXT,
  UNIQUE (loja_id, modulo)
);

-- NAO ha indice separado por loja_id: o UNIQUE (loja_id, modulo) acima ja atende qualquer busca
-- por loja_id, porque ela e o PREFIXO do indice. Um indice a mais so custaria escrita.

COMMENT ON TABLE licenca_modulo IS
  'Modulos do ERP liberados por loja. SEM LINHA = DESLIGADO. Generaliza config_plataforma'
  '[''fiscal.habilitado''] (global) e loja.modulo_ia (por loja), que continuam valendo.';

-- ---------------------------------------------------------------------------------------------
-- 3. deposito -- onde o estoque fica
-- ---------------------------------------------------------------------------------------------
-- O estoque de hoje e um numero por insumo, sem lugar. O modulo de Estoque precisa saber se a
-- caixa de tomate esta na camara fria ou no bar, e a transferencia entre os dois e movimento que o
-- CMV tem de enxergar. Nasce aqui, no principal, porque deposito e CADASTRO -- o modulo le, nao
-- inventa.
CREATE TABLE deposito (
  id        BIGSERIAL PRIMARY KEY,
  loja_id   BIGINT  NOT NULL REFERENCES loja (id),
  nome      TEXT    NOT NULL,
  tipo      TEXT    NOT NULL CHECK (tipo IN
            ('SECO','CAMARA_FRIA','FREEZER','BAR','COZINHA','TERCEIROS')),
  eh_padrao BOOLEAN NOT NULL DEFAULT false,
  ativo     BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX ix_deposito_loja ON deposito (loja_id);

-- UM padrao por loja, e nome unico por loja. Sem estes dois, a tabela aceitava dois depositos
-- `eh_padrao = true` na mesma loja (medido: aceitou) -- e ai "onde entra a mercadoria quando
-- ninguem escolhe" passa a depender da ordem da consulta. Entram agora porque a tabela esta vazia;
-- depois do primeiro dado, cada um destes vira uma limpeza antes da constraint.
CREATE UNIQUE INDEX ux_deposito_padrao ON deposito (loja_id) WHERE eh_padrao AND ativo;
CREATE UNIQUE INDEX ux_deposito_nome   ON deposito (loja_id, nome);

COMMENT ON TABLE deposito IS
  'Local fisico do estoque dentro da loja. Sem linha, o modulo de Estoque usa um deposito implicito.';

-- ---------------------------------------------------------------------------------------------
-- 4. fornecedor -- de quem a loja compra
-- ---------------------------------------------------------------------------------------------
-- Nao existe nada disto no sistema hoje: nao ha fornecedor, requisicao, cotacao, pedido de compra
-- nem recebimento (medido no baseline em 17/11/2026). Compras e o modulo de MENOR risco de
-- acoplamento justamente porque nao toca em nenhum arquivo do fluxo de venda -- mas precisa que o
-- cadastro do fornecedor viva no principal, senao Financeiro e Fiscal teriam cada um o seu.
--
-- `documento` sem mascara e UNICO por empresa: o mesmo CNPJ pode atender duas empresas do grupo,
-- e cada uma tem a sua condicao de pagamento.
CREATE TABLE fornecedor (
  id                        BIGSERIAL PRIMARY KEY,
  empresa_id                BIGINT  NOT NULL REFERENCES empresa (id),
  documento                 TEXT    NOT NULL,
  razao_social              TEXT    NOT NULL,
  nome_fantasia             TEXT,
  regime_tributario         TEXT    CHECK (regime_tributario IN ('SIMPLES','PRESUMIDO','REAL','MEI')),
  inscricao_estadual        TEXT,
  municipio_ibge            TEXT,
  uf                        TEXT,
  contato_email             TEXT,
  contato_telefone          TEXT,
  condicao_pagamento_padrao TEXT,
  ativo                     BOOLEAN NOT NULL DEFAULT true,
  criado_em                 TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (empresa_id, documento)
);

COMMENT ON TABLE fornecedor IS
  'Cadastro de fornecedor, no modulo principal. Compras, Financeiro e Fiscal LEEM daqui.';

-- ---------------------------------------------------------------------------------------------
-- 5. evento_outbox -- a fila que vai alimentar os modulos
-- ---------------------------------------------------------------------------------------------
-- O padrao outbox: o evento e gravado na MESMA transacao do fato que o gerou, e publicado depois
-- do commit. E o que impede os dois erros classicos -- venda gravada sem evento (modulo nunca fica
-- sabendo) e evento publicado de uma venda que deu rollback (modulo processa o que nao aconteceu).
--
-- NESTA MIGRACAO A TABELA SO NASCE. Ninguem escreve nela ainda: enxertar o outbox nos cinco pontos
-- de gravacao do PedidoService e o passo seguinte, e ele e o unico da fase A que fica no caminho de
-- TODA venda -- por isso vai sozinho, medido em producao antes e depois.
--
-- SEM CHAVE ESTRANGEIRA PARA loja/empresa, de proposito: fila nao pode falhar por integridade
-- referencial. Se a loja for apagada, o evento vira historico orfao e isso e melhor do que a
-- gravacao da venda ser recusada.
--
-- `evento_id` UNICO e a chave de idempotencia do consumidor -- a unicidade e do BANCO, nunca de
-- checagem em memoria. Esta licao ja foi paga aqui: `POST /api/pedidos` sem idempotencia gravou a
-- mesma venda duas vezes em producao.
--
-- `ocorrido_em` COM FUSO e `data_operacao` separados: a hora e o instante, a data e o dia
-- operacional da loja. Foi um fuso em UTC que travou o deploy das 21h a meia-noite e produziu duas
-- datas de cobranca erradas -- aqui as duas coisas nascem distintas.
CREATE TABLE evento_outbox (
  id            BIGSERIAL PRIMARY KEY,
  evento_id     UUID        NOT NULL UNIQUE DEFAULT gen_random_uuid(),
  tipo          TEXT        NOT NULL,
  versao_schema TEXT        NOT NULL,
  empresa_id    BIGINT,
  loja_id       BIGINT      NOT NULL,
  agregado_tipo TEXT        NOT NULL,
  agregado_id   BIGINT      NOT NULL,
  payload       JSONB       NOT NULL,
  ocorrido_em   TIMESTAMPTZ NOT NULL,
  data_operacao DATE        NOT NULL,
  criado_em     TIMESTAMPTZ NOT NULL DEFAULT now(),
  publicado_em  TIMESTAMPTZ,
  tentativas    INT         NOT NULL DEFAULT 0,
  ultimo_erro   TEXT
);

-- O indice PARCIAL e o que faz o publicador varrer so o que falta publicar. Sem o `WHERE`, ele
-- cresceria junto com o historico inteiro da fila e a varredura ficaria mais lenta a cada mes.
-- O indice e por `id`, nao por `publicado_em`: dentro do filtro parcial a coluna `publicado_em` e
-- sempre NULL, entao indexa-la nao ordena nada. Por `id` o publicador le a fila em ordem de
-- chegada (`WHERE publicado_em IS NULL ORDER BY id LIMIT n`) sem passo de ordenacao -- e evento
-- fora de ordem e um dos casos que mais dao trabalho do lado de quem consome.
CREATE INDEX ix_outbox_pendente ON evento_outbox (id) WHERE publicado_em IS NULL;
CREATE INDEX ix_outbox_agregado ON evento_outbox (agregado_tipo, agregado_id);

COMMENT ON TABLE evento_outbox IS
  'Fila de eventos do modulo principal, gravada na mesma transacao do fato. Publicada depois do '
  'commit. Nesta versao ninguem escreve nela ainda.';
COMMENT ON COLUMN evento_outbox.evento_id IS
  'Chave de idempotencia do consumidor. A unicidade e do banco, nunca de checagem em memoria.';

-- ---------------------------------------------------------------------------------------------
-- 6. evento_consumido -- a idempotencia do lado de quem le
-- ---------------------------------------------------------------------------------------------
-- A chave primaria composta (consumidor, evento_id) E a trava: o segundo processamento do mesmo
-- evento pelo mesmo modulo viola a PK e para ali, no banco. Cada modulo tem a sua linha, porque o
-- mesmo evento e consumido por varios -- um pedido finalizado interessa ao Fiscal, ao Financeiro,
-- ao Contabil e ao Estoque.
CREATE TABLE evento_consumido (
  consumidor   TEXT        NOT NULL,
  evento_id    UUID        NOT NULL,
  consumido_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (consumidor, evento_id)
);

COMMENT ON TABLE evento_consumido IS
  'Idempotencia do consumidor: a PK composta impede o mesmo modulo de processar o mesmo evento duas vezes.';

-- ---------------------------------------------------------------------------------------------
-- 7. auditoria -- quem mudou o que, e como estava antes
-- ---------------------------------------------------------------------------------------------
-- Hoje a auditoria existe so no log da aplicacao (as linhas "AUDITORIA plataforma: ..."), que e
-- texto e some com a rotacao do container. Para um ERP isso nao basta: fiscal e contabil precisam
-- responder "quem mudou este preco, quando, e qual era o valor anterior".
--
-- `antes` e `depois` em JSONB para nao precisar de uma tabela por entidade auditada. Sem FK para
-- `entidade_id`, porque ela aponta para tabelas diferentes conforme a linha.
CREATE TABLE auditoria (
  id          BIGSERIAL PRIMARY KEY,
  empresa_id  BIGINT,
  loja_id     BIGINT,
  -- ON DELETE SET NULL pela mesma razao do licenca_modulo.ativado_por: a trilha NAO pode impedir
  -- a exclusao de um usuario, e tem de sobreviver a ela. Null aqui significa "a conta que fez isto
  -- nao existe mais", que e honesto; um id pendurado apontando para nada so pareceria resolvivel.
  usuario_id  BIGINT      REFERENCES usuario (id) ON DELETE SET NULL,
  entidade    TEXT        NOT NULL,
  entidade_id BIGINT      NOT NULL,
  acao        TEXT        NOT NULL,
  antes       JSONB,
  depois      JSONB,
  origem_ip   TEXT,
  ocorrido_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_auditoria_entidade ON auditoria (entidade, entidade_id);
CREATE INDEX ix_auditoria_loja_data ON auditoria (loja_id, ocorrido_em);

COMMENT ON TABLE auditoria IS
  'Trilha de auditoria persistida. O log da aplicacao some com a rotacao do container; isto nao.';

-- ---------------------------------------------------------------------------------------------
-- 8. certificado_digital -- o ponteiro, NUNCA o arquivo
-- ---------------------------------------------------------------------------------------------
-- A emissao de nota exige certificado A1 da empresa. `referencia_cofre` guarda um PONTEIRO para o
-- cofre (KMS/HSM/arquivo cifrado), nunca o certificado e nunca a senha.
--
-- O sistema ja tem cifragem em repouso ligada desde 30/10/2026 -- 40 credenciais cifradas, 0 em
-- texto puro. Esta coluna segue o mesmo principio, que e o que impede o certificado de ir junto em
-- todo backup legivel: O BANCO NAO GUARDA SEGREDO LEGIVEL.
CREATE TABLE certificado_digital (
  id                BIGSERIAL PRIMARY KEY,
  empresa_id        BIGINT      NOT NULL REFERENCES empresa (id),
  tipo              TEXT        NOT NULL DEFAULT 'A1',
  referencia_cofre  TEXT        NOT NULL,
  validade_de       DATE        NOT NULL,
  validade_ate      DATE        NOT NULL,
  ativo             BOOLEAN     NOT NULL DEFAULT true,
  criado_em         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_certificado_empresa ON certificado_digital (empresa_id, ativo);

COMMENT ON COLUMN certificado_digital.referencia_cofre IS
  'PONTEIRO para o cofre (KMS/HSM). Nunca o certificado, nunca a senha.';
