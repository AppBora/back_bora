-- Item 9.11 da varredura: a trava que impede duas renovacoes simultaneas do token do iFood era
-- `synchronized` num objeto da JVM (MarketplaceHttp.trava). Trava de JVM nao atravessa processo.
--
-- Por que isso e grave no iFood especificamente: ele INVALIDA o refresh token anterior quando emite
-- o novo. Duas instancias renovando ao mesmo tempo emitem dois tokens; o que gravar por ultimo
-- vence, e o outro ja matou o primeiro -- a loja precisa REAUTORIZAR do zero, e isso vale para
-- TODAS as lojas de uma vez, porque todas renovam no mesmo ciclo.
--
-- Na 99 (Open Delivery) o risco NAO existe: lá o token sai de client_credentials, que nao invalida o
-- anterior. Conferido no OpenDeliveryClient antes de mexer: ele nem guarda refresh token.
--
-- Hoje roda UMA instancia, entao o defeito esta dormindo. O application.yml planeja a segunda, e
-- quando ela subir o estrago e silencioso e simultaneo em todas as lojas -- o pior tipo.
--
-- A trava e um ARRENDAMENTO (lease), nao um lock de banco: quem consegue gravar a marca renova; quem
-- nao consegue espera e rele. Lease em vez de `select for update` de proposito -- a renovacao fala
-- com o iFood, e segurar lock de banco durante chamada HTTP e o defeito dos itens 9.3, 9.4 e 9.5.
alter table integracao_canal add column if not exists token_renovando_em timestamptz;

comment on column integracao_canal.token_renovando_em is
  'Quando alguem comecou a renovar o token desta integracao. Arrendamento de 2 minutos: passado '
  'isso, outro processo pode assumir (quem tinha a marca provavelmente morreu). Nulo = ninguem '
  'renovando. Item 9.11 da varredura.';
